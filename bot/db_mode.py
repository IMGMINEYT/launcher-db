"""Database mode for the file bot (owner only, additive).

Owner sends /db to turn database mode on, /db off to turn it off. While it is
on, files the owner sends are NOT renamed: they are added to the public addon
database (GitHub release + data/addons.json) and a direct download link is
returned. Everyone else, and the owner with the mode off, get the normal
rename behaviour untouched.

In database mode:
  file                -> the addon
  file caption        -> custom name (default: the file name)
  photo               -> banner (before or after the file, within 5 minutes)
  photo caption       -> description
Missing banner / description are looked up on MCPEDL by the addon name, but
only when the owner did not give a custom name, and only for the part that is
missing.

Needs env GH_TOKEN (fine-grained token for GH_REPO, Contents read/write).
"""
import asyncio
import base64
import html
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from telethon import events

GH_REPO = os.environ.get("GH_REPO", "IMGMINEYT/launcher-db")
GH_TOKEN = os.environ.get("GH_TOKEN", "")
BRANCH = "main"
DATA_PATH = "data/addons.json"
PENDING_TTL = 300          # seconds a lone photo waits for its file (and vice versa)
MAX_DESC = 700
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"

STATE_PATH = Path(__file__).resolve().parent / "db_state.json"


# ---------------------------------------------------------------- state
def _load_state():
    try:
        return json.loads(STATE_PATH.read_text())
    except Exception:
        return {"on": False}


def _save_state(st):
    try:
        STATE_PATH.write_text(json.dumps(st))
    except Exception:
        pass


# ---------------------------------------------------------------- GitHub
def _gh(method, url, body=None, raw=None, headers=None, timeout=120):
    h = {"Authorization": "Bearer " + GH_TOKEN, "Accept": "application/vnd.github+json",
         "User-Agent": "addon-db-bot"}
    if headers:
        h.update(headers)
    data = raw
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            txt = r.read()
            return r.status, (json.loads(txt) if txt else {})
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def _purge(path):
    """Best effort: ask jsDelivr to drop its cached copy so the site shows changes fast."""
    try:
        urllib.request.urlopen(urllib.request.Request(
            "https://purge.jsdelivr.net/gh/%s@%s/%s" % (GH_REPO, BRANCH, path),
            headers={"User-Agent": "addon-db-bot"}), timeout=15).read()
    except Exception:
        pass


def _api(path):
    return "https://api.github.com/repos/%s/%s" % (GH_REPO, path)


def _slug(name):
    s = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return (s or "addon")[:60].strip("-") or "addon"


def _read_data():
    st, j = _gh("GET", _api("contents/%s?ref=%s" % (DATA_PATH, BRANCH)))
    if st == 404:
        return {"schema": 1, "updated": "", "addons": []}, None
    if st != 200:
        raise RuntimeError("read data %s" % st)
    doc = json.loads(base64.b64decode(j["content"]).decode("utf-8"))
    doc.setdefault("addons", [])
    return doc, j["sha"]


def _write_data(doc, sha, msg):
    doc["updated"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    b = {"message": msg, "branch": BRANCH,
         "content": base64.b64encode(json.dumps(doc, indent=1, ensure_ascii=False).encode("utf-8")).decode()}
    if sha:
        b["sha"] = sha
    return _gh("PUT", _api("contents/" + DATA_PATH), body=b)[0]


def _update_data(mutator, msg):
    """Read-modify-write data/addons.json with retry on a concurrent commit."""
    for _ in range(4):
        doc, sha = _read_data()
        mutator(doc)
        st = _write_data(doc, sha, msg)
        if st in (200, 201):
            _purge(DATA_PATH)
            return doc
        if st not in (409, 422):
            raise RuntimeError("write data %s" % st)
        time.sleep(1.5)
    raise RuntimeError("write data conflict")


def _put_banner(addon_id, img_bytes, ext):
    path = "banners/%s.%s" % (addon_id, ext)
    st, cur = _gh("GET", _api("contents/%s?ref=%s" % (path, BRANCH)))
    b = {"message": "Banner " + addon_id, "branch": BRANCH,
         "content": base64.b64encode(img_bytes).decode()}
    if st == 200:
        b["sha"] = cur["sha"]
    st, _ = _gh("PUT", _api("contents/" + path), body=b)
    if st not in (200, 201):
        raise RuntimeError("banner %s" % st)
    _purge(path)
    return "https://raw.githubusercontent.com/%s/%s/%s" % (GH_REPO, BRANCH, path)


def _create_release_and_upload(tag, title, file_path, asset_name):
    st, rel = _gh("POST", _api("releases"), body={
        "tag_name": tag, "target_commitish": BRANCH, "name": title,
        "body": "", "draft": False, "prerelease": False})
    if st not in (200, 201):
        raise RuntimeError("release %s" % st)
    try:
        return _upload_asset(rel, file_path, asset_name)
    except Exception:
        _delete_release(tag)  # no half-made release left behind
        raise


def _upload_asset(rel, file_path, asset_name):
    size = os.path.getsize(file_path)
    url = "https://uploads.github.com/repos/%s/releases/%s/assets?name=%s" % (
        GH_REPO, rel["id"], urllib.parse.quote(asset_name))
    with open(file_path, "rb") as f:
        req = urllib.request.Request(url, data=f, method="POST", headers={
            "Authorization": "Bearer " + GH_TOKEN, "Accept": "application/vnd.github+json",
            "Content-Type": "application/octet-stream", "Content-Length": str(size),
            "User-Agent": "addon-db-bot"})
        try:
            with urllib.request.urlopen(req, timeout=1800) as r:
                asset = json.loads(r.read())
        except urllib.error.HTTPError as e:
            raise RuntimeError("asset %s" % e.code)
    return asset["browser_download_url"], rel["id"], size


def _delete_release(tag):
    st, rel = _gh("GET", _api("releases/tags/" + urllib.parse.quote(tag)))
    if st == 200:
        _gh("DELETE", _api("releases/%s" % rel["id"]))
    _gh("DELETE", _api("git/refs/tags/" + urllib.parse.quote(tag)))


# ---------------------------------------------------------------- MCPEDL lookup
def _http_text(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(1_500_000).decode("utf-8", "ignore")


def _meta(page, prop):
    for pat in (r'<meta[^>]+(?:property|name)="%s"[^>]+content="([^"]*)"' % re.escape(prop),
                r'<meta[^>]+content="([^"]*)"[^>]+(?:property|name)="%s"' % re.escape(prop)):
        m = re.search(pat, page)
        if m:
            return html.unescape(m.group(1)).strip()
    return ""


def _words(s):
    return set(w for w in re.findall(r"[a-z0-9]+", s.lower()) if w not in
               ("addon", "mod", "mcpe", "minecraft", "by", "for", "v", "the", "and", "bedrock", "pe"))


def _mcpedl_lookup(name):
    """Best effort: returns {"description","image","url"} (any may be empty) or None."""
    try:
        q = urllib.parse.quote("mcpedl " + name + " minecraft addon")
        res = _http_text("https://www.bing.com/search?q=" + q)
        urls = []
        for m in re.findall(r'<h2><a[^>]*href="([^"]+)"', res):
            m = html.unescape(m)
            u = urllib.parse.parse_qs(urllib.parse.urlparse(m).query).get("u", [""])[0]
            if u.startswith("a1"):
                try:
                    u = base64.urlsafe_b64decode(u[2:] + "=" * (-len(u[2:]) % 4)).decode()
                except Exception:
                    continue
            else:
                u = m
            p = urllib.parse.urlparse(u)
            if p.netloc.endswith("mcpedl.com") or p.netloc.endswith("mcpedl.org"):
                parts = [x for x in p.path.split("/") if x]
                if len(parts) >= 1 and parts[0] not in ("category", "page", "tag", "author", "wp-content", "search"):
                    urls.append(u)
        want = _words(name)
        if not want:
            return None
        for u in urls[:4]:
            try:
                page = _http_text(u)
            except Exception:
                continue
            title = _meta(page, "og:title")
            have = _words(title)
            if not have or len(want & have) / float(len(want)) < 0.6:
                continue
            return {"description": _meta(page, "og:description") or _meta(page, "description"),
                    "image": _meta(page, "og:image"), "url": u}
    except Exception:
        return None
    return None


def _clean_name(stem):
    """File name -> tidy addon name: drop 'by <channel>' promo suffixes and leftover separators."""
    n = stem.replace("_", " ")
    if " " not in n.strip():
        n = n.replace(".", " ")
    n = re.sub(r"\s+", " ", n).strip()
    for _ in range(3):
        n = re.sub(r"[\s\-\u2013\u2014|:~(\[]*\bby\s+[^()\[\]]*[)\]]?\s*$", "", n, flags=re.I)
        n = re.sub(r"[\s\-\u2013\u2014|:~(\[]*\bfree\s*mc\s*market\s*place\b[)\]]?\s*$", "", n, flags=re.I)
        n = n.strip(" -\u2013\u2014|:~([_.")
    return n[:80] or stem[:80]


def _extract_icon(path):
    """Find pack_icon.png/jpg inside an addon zip (also inside nested .mcpack/.zip). Returns (bytes, ext) or (None, None)."""
    import io
    import zipfile

    def scan(zf, depth):
        names = [n for n in zf.namelist() if not n.endswith("/")]
        best = None
        for n in names:
            base = n.rsplit("/", 1)[-1].lower()
            if base in ("pack_icon.png", "pack_icon.jpg", "pack_icon.jpeg") and zf.getinfo(n).file_size < 5_000_000:
                if best is None or n.count("/") < best.count("/"):
                    best = n
        if best:
            return zf.read(best), ("png" if best.lower().endswith(".png") else "jpg")
        if depth < 2:
            for n in names:
                if n.lower().endswith((".mcpack", ".zip", ".mcaddon")) and zf.getinfo(n).file_size < 150_000_000:
                    try:
                        res = scan(zipfile.ZipFile(io.BytesIO(zf.read(n))), depth + 1)
                    except Exception:
                        continue
                    if res[0]:
                        return res
        return None, None
    try:
        with zipfile.ZipFile(path) as zf:
            return scan(zf, 0)
    except Exception:
        return None, None


def _fetch_image(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read(8_000_001)
        ctype = r.headers.get("Content-Type", "")
    if len(data) > 8_000_000 or not ctype.startswith("image/"):
        return None, None
    ext = {"image/png": "png", "image/webp": "webp", "image/gif": "gif"}.get(ctype.split(";")[0], "jpg")
    return data, ext


# ---------------------------------------------------------------- bot wiring
def setup(client, owner_id, tmp_dir):
    state = _load_state()
    queue = asyncio.Queue()
    lock = asyncio.Lock()
    pending = {"photo": None}      # {"bytes","ext","desc","t"}
    pending_file = {"item": None}  # last added addon that may still get a banner {"id","t","has_banner"}

    def is_owner(event):
        return owner_id is not None and event.sender_id == owner_id

    def mode_on():
        return bool(state.get("on")) and bool(GH_TOKEN)

    # ---- /db commands -------------------------------------------------
    @client.on(events.NewMessage(pattern=r"^/db(?:\s+(.*))?$"))
    async def on_db(event):
        if not is_owner(event):
            return  # not the owner: behave as before
        arg = (event.pattern_match.group(1) or "").strip()
        low = arg.lower()
        if not GH_TOKEN:
            await event.reply("Database mode is not configured yet.")
        elif low == "on" or low == "":
            state["on"] = True
            _save_state(state)
            await event.reply(
                "Database mode ON. Files you send now go to the addon database, not the renamer.\n"
                "Caption on file = custom name. Photo = banner. Photo caption = description.\n"
                "Send /db off to go back to renaming.")
        elif low == "off":
            state["on"] = False
            _save_state(state)
            await event.reply("Database mode OFF. Normal renaming is back.")
        elif low.startswith("del "):
            target = _slug(arg[4:].strip())
            try:
                def mut(doc):
                    n = len(doc["addons"])
                    doc["addons"] = [a for a in doc["addons"] if a.get("id") != target]
                    if len(doc["addons"]) == n:
                        raise KeyError(target)
                async with lock:
                    await asyncio.to_thread(_update_data, mut, "Remove " + target)
                    await asyncio.to_thread(_delete_release, target)
                await event.reply("Removed: " + target)
            except KeyError:
                await event.reply("No addon with id: " + target)
            except Exception:
                await event.reply("Could not remove it. Try again.")
        elif low == "list":
            try:
                doc, _ = await asyncio.to_thread(_read_data)
                ids = [a.get("id", "?") for a in doc["addons"]][-30:]
                await event.reply("In database (%d): %s" % (len(doc["addons"]), ", ".join(ids) or "(empty)"))
            except Exception:
                await event.reply("Could not read the database.")
        else:
            await event.reply("Usage: /db on, /db off, /db list, /db del <id>")
        raise events.StopPropagation

    # ---- photos (banner) ---------------------------------------------
    @client.on(events.NewMessage())
    async def on_photo(event):
        msg = event.message
        if msg.out or not msg.photo or msg.document or not is_owner(event) or not mode_on():
            return
        data = await client.download_media(msg.photo, file=bytes)
        desc = (msg.message or "").strip()[:MAX_DESC]
        now = time.time()
        it = pending_file["item"]
        if it and now - it["t"] < PENDING_TTL and not it["has_banner"]:
            # photo arrived after the file: attach to that addon
            it["has_banner"] = True
            try:
                async with lock:
                    url = await asyncio.to_thread(_put_banner, it["id"], data, "jpg")

                    def mut(doc):
                        for a in doc["addons"]:
                            if a.get("id") == it["id"]:
                                a["banner"] = url
                                if desc:
                                    a["description"] = desc
                    await asyncio.to_thread(_update_data, mut, "Banner " + it["id"])
                await event.reply("Banner added to " + it["id"])
            except Exception:
                await event.reply("Could not add the banner. Send the photo again.")
        else:
            pending["photo"] = {"bytes": data, "ext": "jpg", "desc": desc, "t": now}
            await event.reply("Banner saved. Now send the addon file (within 5 minutes).")
        raise events.StopPropagation

    # ---- documents (the addon) ---------------------------------------
    @client.on(events.NewMessage())
    async def on_file(event):
        msg = event.message
        if msg.out or not msg.document or not is_owner(event) or not mode_on():
            return  # everyone else, and owner with mode off: unchanged renamer path
        status = await msg.reply("Added to database queue.")
        await queue.put((msg, status))
        raise events.StopPropagation

    async def add_one(msg, status):
        doc = msg.document
        fname = next((a.file_name for a in doc.attributes if getattr(a, "file_name", None)), "addon")
        fname = fname.replace("/", "_").replace("\\", "_")
        caption = (msg.message or "").strip()
        if getattr(msg, "fwd_from", None):
            caption = ""  # forwarded posts carry channel promo captions, not a chosen name
        custom = bool(caption)
        stem = os.path.splitext(fname)[0]
        name = caption.splitlines()[0][:80] if custom else _clean_name(stem)
        ext = os.path.splitext(fname)[1].lower()
        typ = {".mcaddon": "addon", ".mcpack": "addon", ".mcworld": "map", ".mctemplate": "map"}.get(ext, "file")
        tmp = Path(tmp_dir) / ("db_%s" % msg.id)
        aid = None
        committed = False
        try:
            tmp.parent.mkdir(exist_ok=True)
            last = {"t": 0.0}

            async def prog(done, total):
                now = time.monotonic()
                if total and now - last["t"] > 5:
                    last["t"] = now
                    try:
                        await status.edit("Database: downloading %d%%" % (done * 100 // total))
                    except Exception:
                        pass
            await client.download_file(doc, file=str(tmp), part_size_kb=512,
                                       file_size=doc.size or None, progress_callback=prog)
            try:
                await status.edit("Database: creating download link")
            except Exception:
                pass
            async with lock:
                doc0, _ = await asyncio.to_thread(_read_data)
                taken = set(a.get("id") for a in doc0["addons"])
                base = _slug(name)
                aid, n = base, 2
                while aid in taken:
                    aid = "%s-%d" % (base, n)
                    n += 1
                dl_url, _rid, size = await asyncio.to_thread(
                    _create_release_and_upload, aid, name, str(tmp), fname)
            # 1) link first
            await msg.reply("Download link:\n" + dl_url)
            # banner / description: the owner's own first
            banner_url = None
            is_icon = False
            desc = ""
            source = ""
            now = time.time()
            pp = pending["photo"]
            have_img = False
            if pp and now - pp["t"] < PENDING_TTL:
                pending["photo"] = None
                have_img = True
                desc = pp["desc"]
                async with lock:
                    banner_url = await asyncio.to_thread(_put_banner, aid, pp["bytes"], pp["ext"])
            # no photo: use the pack icon from inside the addon file
            if not have_img:
                idata, iext = await asyncio.to_thread(_extract_icon, str(tmp))
                if idata:
                    async with lock:
                        banner_url = await asyncio.to_thread(_put_banner, aid, idata, iext)
                    have_img = True
                    is_icon = True
            # lookup only without a custom name, and only for the missing parts
            if not custom and (not have_img or not desc):
                try:
                    await status.edit("Database: looking up details")
                except Exception:
                    pass
                found = await asyncio.to_thread(_mcpedl_lookup, name)
                if found:
                    source = found.get("url", "")
                    if not desc and found.get("description"):
                        desc = found["description"][:MAX_DESC]
                    if not have_img and found.get("image"):
                        data, iext = await asyncio.to_thread(_fetch_image, found["image"])
                        if data:
                            async with lock:
                                banner_url = await asyncio.to_thread(_put_banner, aid, data, iext)
                            have_img = True
            entry = {"id": aid, "name": name, "description": desc, "banner": banner_url,
                     "download": dl_url, "size": size, "type": typ, "filename": fname,
                     "added": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
            if is_icon and banner_url:
                entry["icon"] = True
            if source:
                entry["source"] = source

            def mut(doc):
                doc["addons"] = [a for a in doc["addons"] if a.get("id") != aid]
                doc["addons"].append(entry)
            async with lock:
                await asyncio.to_thread(_update_data, mut, "Add " + aid)
            committed = True
            pending_file["item"] = {"id": aid, "t": time.time(), "has_banner": have_img}
            extra = "" if have_img else "\nNo banner yet: send a photo within 5 minutes to add one."
            await status.edit("Added to the database: %s\nIt shows on the site in about a minute.%s" % (name, extra))
        except Exception:
            if aid and not committed:
                try:
                    await asyncio.to_thread(_delete_release, aid)
                except Exception:
                    pass
            try:
                await status.edit("Could not add this file to the database. Nothing was published. Try again.")
            except Exception:
                pass
        finally:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass

    async def db_worker():
        while True:
            msg, status = await queue.get()
            try:
                await add_one(msg, status)
            except Exception:
                pass

    client.loop.create_task(db_worker())
