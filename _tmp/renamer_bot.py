"""FREE MC MARKET PLACE file-renamer bot (Telethon / official MTProto).

- Public bot: users send a document, bot returns it renamed (bytes untouched).
- Serial queue: one file processed at a time, metadata-only queue, no
  per-user cap; global safety cap with an explicit "queue full" reply.
- One progress message per active file, edited in place (throttled).
- Data minimization: temp file deleted after delivery or failure; temp dir
  swept on startup; logs carry no file names or content.
- Owner-only /add, /remove, /words commands manage the custom strip-words
  list persisted in config.json.

Credentials come from environment variables (provisioned via vault at
deploy time): RENAMER_API_ID, RENAMER_API_HASH, RENAMER_BOT_TOKEN,
RENAMER_OWNER_ID. Optional: RENAMER_MAX_FILE_MB (default 400),
RENAMER_SELF_CHECK (when set, byte-identity proof via streamed re-download).
"""
import asyncio
import itertools
import hashlib
import json
import logging
import os
import re
import sys
import threading
import time
from pathlib import Path

from telethon import TelegramClient, events, types

from rename_logic import rename, RenameRejected
from marketplace_lookup import lookup_filename, file_caption
from stall_guard import JobFailed, run_with_stall_guard

try:
    from telethon.errors import FloodWaitError
    FLOOD_ERRORS = (FloodWaitError,)
except Exception:
    FLOOD_ERRORS = ()

API_ID = int(os.environ.get("RENAMER_API_ID") or os.environ["API_ID"])
API_HASH = os.environ.get("RENAMER_API_HASH") or os.environ["API_HASH"]
BOT_TOKEN = os.environ.get("RENAMER_BOT_TOKEN") or os.environ["BOT_TOKEN"]
# Owner id is provisioned from a verified session, not guessed.
# Until set, owner-only commands deny everyone.
OWNER_ID = int(os.environ.get("RENAMER_OWNER_ID", "0")) or None
MAX_FILE_BYTES = int(os.environ.get("RENAMER_MAX_FILE_MB", "400")) * 1024 * 1024

BASE_DIR = Path(__file__).resolve().parent
TMP_DIR = BASE_DIR / "tmp_jobs"
CONFIG_PATH = BASE_DIR / "config.json"
MAX_QUEUE = 500         # global pending jobs (metadata only, nothing downloaded);
                        # safety valve only - local measured metadata cost about 4.6 KB/job
PROGRESS_MIN_INTERVAL = 5.0  # seconds between progress edits
PART_SIZE_KB = 512      # max Telegram part size: fewer round trips per file

# Logging allowlist: a filter on the single root handler passes ONLY this
# bot's own approved line formats. Everything else - third-party library
# records (telethon network chatter, server IPs, paths), asyncio error
# tracebacks, and any unexpected message - is dropped before it can reach
# the console.
class _ApprovedLogFilter(logging.Filter):
    _ALLOWED = re.compile(
        r"^(?:"
        r"renamer bot started \(telethon=\S+ crypto=\S+\)"
        r"|\[(?:DOWNLOAD|UPLOAD)\] size=\d+(?:\.\d+)?MB duration=\d+(?:\.\d+)?s "
        r"speed=\d+(?:\.\d+)?MB/s status=(?:SUCCESS|FAILED)"
        r"|SELF_CHECK bytes_match=(?:True|False) verify_dl=\d+(?:\.\d+)?s"
        r"|SELF_CHECK failed category=[A-Za-z_][A-Za-z0-9_]*"
        r")$"
    )

    def filter(self, record):
        if record.name != "renamer" or record.exc_info:
            return False
        try:
            return bool(self._ALLOWED.match(record.getMessage()))
        except Exception:
            return False


_handler = logging.StreamHandler()
_handler.setFormatter(logging.Formatter("%(levelname)s %(message)s"))
_handler.addFilter(_ApprovedLogFilter())
logging.basicConfig(level=logging.INFO, handlers=[_handler], force=True)
logging.raiseExceptions = False
log = logging.getLogger("renamer")


def _safe_excepthook(exc_type, exc, tb):
    # Uncaught exceptions must not leak file paths or internals to the log.
    sys.stderr.write("fatal error (details suppressed)\n")


sys.excepthook = _safe_excepthook
threading.excepthook = lambda args: sys.stderr.write("fatal error (details suppressed)\n")

# NOTE FOR REVIEW: user-visible strings (English per owner preference).
MSG_START = ("Send me any file up to {max_mb} MB and I will send it back renamed.\n"
             "One file is processed at a time; if there is a queue, please wait.")
MSG_QUEUED = "Your file is queued (position {pos}). Please wait."
MSG_QUEUE_FULL = "The queue is full right now. Please try again in a few minutes."
MSG_TOO_BIG = "Sorry, this file is too large ({size_mb} MB). Maximum is {max_mb} MB."
MSG_CANT_RENAME = "Sorry, I could not rename this file safely. Please rename it yourself."
MSG_PROGRESS = "Processing your file: {stage}"
MSG_DONE = "Done."
MSG_FAILED = "Sorry, something went wrong while processing your file."
MSG_WORD_ADDED = "Added word: {word}"
MSG_WORD_REMOVED = "Removed word: {word}"
MSG_WORDS_LIST = "Custom strip-words: {words}"
MSG_NOT_OWNER = "Only the bot owner can change settings."

STAGE_QUEUED = "in queue"
STAGE_DOWNLOAD = "downloading {pct}%"
STAGE_RENAME = "renaming"
STAGE_UPLOAD = "uploading {pct}%"


def load_config():
    if CONFIG_PATH.exists():
        try:
            return json.loads(CONFIG_PATH.read_text())
        except Exception:
            pass
    return {"custom_words": []}


def save_config(cfg):
    CONFIG_PATH.write_text(json.dumps(cfg, indent=1))


def sweep_tmp():
    TMP_DIR.mkdir(exist_ok=True)
    for p in TMP_DIR.iterdir():
        try:
            p.unlink(missing_ok=True)
        except OSError:
            pass


JOB_SEQ = itertools.count(1)


class JobQueue:
    def __init__(self):
        self.q = asyncio.Queue()

    def can_accept(self, uid):
        # No per-user cap: every file is accepted number-wise up to the
        # global safety cap. Nothing is downloaded until a job's turn.
        return self.q.qsize() < MAX_QUEUE

    def next_pos(self):
        return self.q.qsize() + 1

    def enqueue(self, job):
        job["seq"] = next(JOB_SEQ)
        self.q.put_nowait(job)

    async def get(self):
        return await self.q.get()


jobs = JobQueue()
client = TelegramClient(str(BASE_DIR / "bot.session"), API_ID, API_HASH)


async def edit_progress(msg, stage, last=[0.0]):
    now = time.monotonic()
    if now - last[0] < PROGRESS_MIN_INTERVAL and not stage.startswith("uploading 100"):
        return
    last[0] = now
    try:
        await msg.edit(MSG_PROGRESS.format(stage=stage))
    except Exception:
        pass


def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


async def process_job(job):
    msg = job["message"]
    status = job["status"]
    seq = job.get("seq", 0)
    tmp_path = TMP_DIR / f"job_{msg.id}"
    final_path = None
    delivered = False
    lookup_task = None
    try:
        await edit_progress(status, STAGE_QUEUED)
        doc = msg.document
        original_name = next(
            (a.file_name for a in doc.attributes if getattr(a, "file_name", None)),
            "file",
        )
        t0 = time.monotonic()
        t_enq = job.get("t_enq", t0)
        cfg = load_config()
        try:
            new_name = rename(original_name, cfg["custom_words"])
        except RenameRejected:
            await status.edit(MSG_CANT_RENAME)
            return

        # Lookup overlaps transfer; it never blocks file delivery.
        lookup_task = asyncio.create_task(lookup_filename(original_name))

        dl_prog = {"last": time.monotonic()}

        async def dl_progress(received, total):
            dl_prog["last"] = time.monotonic()
            dl_prog["cur"] = received
            if total:
                await edit_progress(status, STAGE_DOWNLOAD.format(pct=received * 100 // total))

        async def dl_attempt(_attempt):
            # Drop partial bytes from a failed attempt before restarting.
            tmp_path.unlink(missing_ok=True)
            t_at = time.monotonic()
            try:
                await client.download_file(
                    doc,
                    file=str(tmp_path),
                    part_size_kb=PART_SIZE_KB,
                    file_size=doc.size or None,
                    progress_callback=dl_progress,
                )
            except Exception:
                got = dl_prog.get("cur", 0)
                took = time.monotonic() - t_at
                log.info("[DOWNLOAD] size=%.2fMB duration=%.1fs speed=%.3fMB/s status=FAILED",
                         got / 1e6, took, got / max(took, 0.001) / 1e6)
                raise

        t_dl0 = time.monotonic()
        await run_with_stall_guard(
            "download", seq, dl_attempt, dl_prog, flood_errors=FLOOD_ERRORS
        )
        t_dl = time.monotonic()
        size_bytes = tmp_path.stat().st_size
        dl_s = t_dl - t_dl0
        log.info("[DOWNLOAD] size=%.2fMB duration=%.1fs speed=%.3fMB/s status=SUCCESS",
                 size_bytes / 1e6, dl_s, size_bytes / max(dl_s, 0.001) / 1e6)

        self_check = bool(os.environ.get("RENAMER_SELF_CHECK"))
        h1 = None
        if self_check:
            # Byte-identity proof: hash the original bytes BEFORE rename/upload.
            h1 = _sha256_file(str(tmp_path))

        await edit_progress(status, STAGE_RENAME)
        # Rename on disk so the uploaded document always carries the new filename.
        safe_name = new_name.replace("/", "_").replace("\\", "_")
        final_path = TMP_DIR / f"{msg.id}_{safe_name}"
        os.replace(tmp_path, final_path)
        t_ren = time.monotonic()
        await status.edit(MSG_PROGRESS.format(stage=STAGE_UPLOAD.format(pct=0)))

        ul_prog = {"last": time.monotonic()}

        async def ul_progress(sent_bytes, total):
            ul_prog["last"] = time.monotonic()
            ul_prog["cur"] = sent_bytes
            if total:
                await edit_progress(status, STAGE_UPLOAD.format(pct=sent_bytes * 100 // total))

        # Explicit upload + document attributes: original attributes kept,
        # only the filename attribute replaced with the computed name.
        attrs = [a for a in doc.attributes if not isinstance(a, types.DocumentAttributeFilename)]
        attrs.append(types.DocumentAttributeFilename(file_name=new_name))
        async def ul_attempt(_attempt):
            t_at = time.monotonic()
            try:
                handle = await client.upload_file(
                    str(final_path),
                    part_size_kb=PART_SIZE_KB,
                    file_name=new_name,
                    progress_callback=ul_progress,
                )
            except Exception:
                got = ul_prog.get("cur", 0)
                took = time.monotonic() - t_at
                log.info("[UPLOAD] size=%.2fMB duration=%.1fs speed=%.3fMB/s status=FAILED",
                         got / 1e6, took, got / max(took, 0.001) / 1e6)
                raise
            return handle

        handle = await run_with_stall_guard(
            "upload", seq, ul_attempt, ul_prog, flood_errors=FLOOD_ERRORS
        )
        match = None
        if lookup_task.done() and not lookup_task.cancelled():
            try:
                match = lookup_task.result()
            except Exception:
                pass
        # Plain caption, no Markdown interpretation of external title text.
        caption = file_caption(match)
        sent = await client.send_file(
            msg.chat_id,
            handle,
            force_document=True,
            caption=caption,
            parse_mode=None,
            attributes=attrs,
            mime_type=doc.mime_type or "application/octet-stream",
        )
        delivered = True
        t_ul = time.monotonic()
        ul_s = t_ul - t_ren
        sent_name = next(
            (a.file_name for a in sent.document.attributes if getattr(a, "file_name", None)),
            None,
        )
        # Anonymous per-file transfer metrics (no names/IDs): sizes, durations,
        # and rates in KiB/s, so slow windows can be attributed (route vs host).
        dl_s = max(t_dl - t_dl0, 0.001)
        ul_s = max(t_ul - t_ren, 0.001)
        # Anonymous per-file transfer metrics: size in MB, durations in seconds,
        # speeds in MB/s, success/failure status. No filename, content, user/chat/
        # message IDs, local paths or secrets.
        log.info("[UPLOAD] size=%.2fMB duration=%.1fs speed=%.3fMB/s status=SUCCESS",
                 size_bytes / 1e6, ul_s, size_bytes / max(ul_s, 0.001) / 1e6)
        await status.edit(MSG_DONE)
        if self_check and h1 is not None:
            # Verify the returned copy by streaming a re-download (no disk copy).
            # Runs after Done, so the user never waits on it.
            try:
                h = hashlib.sha256()
                t_sc0 = time.monotonic()
                async for chunk in client.iter_download(sent.document):
                    h.update(chunk)
                log.info("SELF_CHECK bytes_match=%s verify_dl=%.1fs",
                         h.hexdigest() == h1, time.monotonic() - t_sc0)
            except Exception as sc_exc:
                log.error("SELF_CHECK failed category=%s", type(sc_exc).__name__)
    except JobFailed:
        try:
            await status.edit(MSG_FAILED)
        except Exception:
            pass
    except Exception:
        if not delivered:
            try:
                await status.edit(MSG_FAILED)
            except Exception:
                pass
    finally:
        if lookup_task is not None and not lookup_task.done():
            lookup_task.cancel()
        for p in (tmp_path, final_path):
            if p is None:
                continue
            try:
                p.unlink(missing_ok=True)
            except OSError:
                pass


async def worker():
    while True:
        job = await jobs.get()
        await process_job(job)


@client.on(events.NewMessage(pattern=r"^/(start|help)$"))
async def on_start(event):
    await event.reply(MSG_START.format(max_mb=MAX_FILE_BYTES // (1024 * 1024)))


@client.on(events.NewMessage(pattern=r"^/add\s+(.+)$"))
async def on_add(event):
    if OWNER_ID is None or event.sender_id != OWNER_ID:
        await event.reply(MSG_NOT_OWNER)
        return
    word = event.pattern_match.group(1).strip()
    cfg = load_config()
    if word and word not in cfg["custom_words"]:
        cfg["custom_words"].append(word)
        save_config(cfg)
    await event.reply(MSG_WORD_ADDED.format(word=word))


@client.on(events.NewMessage(pattern=r"^/remove\s+(.+)$"))
async def on_remove(event):
    if OWNER_ID is None or event.sender_id != OWNER_ID:
        await event.reply(MSG_NOT_OWNER)
        return
    word = event.pattern_match.group(1).strip()
    cfg = load_config()
    if word in cfg["custom_words"]:
        cfg["custom_words"].remove(word)
        save_config(cfg)
    await event.reply(MSG_WORD_REMOVED.format(word=word))


@client.on(events.NewMessage(pattern=r"^/words$"))
async def on_words(event):
    if OWNER_ID is None or event.sender_id != OWNER_ID:
        await event.reply(MSG_NOT_OWNER)
        return
    cfg = load_config()
    words = ", ".join(cfg["custom_words"]) or "(none)"
    await event.reply(MSG_WORDS_LIST.format(words=words))


@client.on(events.NewMessage())
async def on_document(event):
    msg = event.message
    if not msg.document or msg.out:
        return
    size = msg.document.size or 0
    if size > MAX_FILE_BYTES:
        await msg.reply(MSG_TOO_BIG.format(size_mb=size // (1024 * 1024),
                                           max_mb=MAX_FILE_BYTES // (1024 * 1024)))
        return
    if not jobs.can_accept(msg.sender_id):
        await msg.reply(MSG_QUEUE_FULL)
        return
    status = await msg.reply(MSG_QUEUED.format(pos=jobs.next_pos()))
    jobs.enqueue({"message": msg, "user_id": msg.sender_id, "status": status,
                  "t_enq": time.monotonic()})


def main():
    sweep_tmp()
    client.loop.create_task(worker())
    client.start(bot_token=BOT_TOKEN)
    try:
        import cryptg  # noqa: F401
        backend = "cryptg"
    except Exception:
        backend = "pure-python"
    log.info("renamer bot started (telethon=%s crypto=%s)",
             __import__("telethon").__version__, backend)
    client.run_until_disconnected()


if __name__ == "__main__":
    main()
