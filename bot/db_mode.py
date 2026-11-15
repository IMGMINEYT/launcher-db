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

from telethon import events, errors

GH_REPO = os.environ.get("GH_REPO", "IMGMINEYT/launcher-db")
GH_TOKEN = os.environ.get("GH_TOKEN", "")
BRANCH = "main"
DATA_PATH = "data/addons.json"
PENDING_TTL = 300          # seconds a lone photo waits for its file (and vice versa)
MAX_DESC = 700
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"

TYPES = {".mcaddon": "addon", ".mcworld": "world", ".mcpack": "pack", ".mctemplate": "template"}

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


def _replace_asset(tag, file_path, new_name):
    """Swap the file of an existing release. Keeps the old asset name (so the link stays
    identical) when the extension matches. Returns (download_url, size)."""
    st, rel = _gh("GET", _api("releases/tags/" + urllib.parse.quote(tag)))
    if st != 200:
        raise RuntimeError("release missing")
    old = rel.get("assets", [])
    name = new_name
    if old and os.path.splitext(old[0]["name"])[1].lower() == os.path.splitext(new_name)[1].lower():
        name = old[0]["name"]
    for a in old:
        _gh("DELETE", _api("releases/assets/%s" % a["id"]))
    url, _rid, size = _upload_asset(rel, file_path, name)
    return url, size


def _delete_release(tag):
    st, rel = _gh("GET", _api("releases/tags/" + urllib.parse.quote(tag)))
    if st == 200:
        _gh("DELETE", _api("releases/%s" % rel["id"]))
    _gh("DELETE", _api("git/refs/tags/" + urllib.parse.quote(tag)))


# ---------------------------------------------------------------- duplicate check
def _sha256_file(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _sha256_url(url):
    import hashlib
    h = hashlib.sha256()
    req = urllib.request.Request(url, headers={"User-Agent": "addon-db-bot"})
    with urllib.request.urlopen(req, timeout=600) as r:
        for chunk in iter(lambda: r.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _find_dup_by_name(addons, fname):
    f = (fname or "").strip().lower()
    for a in addons:
        if f and (a.get("filename") or "").strip().lower() == f:
            return a
    return None


def _find_dup_by_content(addons, sha, size):
    """Same bytes. Entries with a stored sha256 compare directly; older entries without one
    are only downloaded and hashed when their size matches."""
    for a in addons:
        if a.get("sha256") == sha:
            return a
    for a in addons:
        if a.get("sha256") or not a.get("download"):
            continue
        if a.get("size") and size and int(a["size"]) != int(size):
            continue
        try:
            if _sha256_url(a["download"]) == sha:
                return a
        except Exception:
            continue
    return None


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
    edit = {"id": None, "t": 0.0}
    EDIT_TTL = 600

    def edit_active():
        return bool(GH_TOKEN) and edit["id"] is not None and time.time() - edit["t"] < EDIT_TTL

    def touch_edit():
        edit["t"] = time.time()

    pending_file = {"item": None}  # last added addon that may still get a banner {"id","t","has_banner"}

    promo = {"slot": None, "link": "", "t": 0.0}
    PROMO_TTL = 300

    def promo_active():
        return bool(GH_TOKEN) and promo["slot"] is not None and time.time() - promo["t"] < PROMO_TTL

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
            edit["id"] = None
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
        elif low.startswith("edit"):
            what = arg[4:].strip()
            if what.lower() in ("", "done", "off", "stop"):
                edit["id"] = None
                await event.reply("Edit mode closed.")
            else:
                try:
                    doc, _ = await asyncio.to_thread(_read_data)
                    q = _slug(what)
                    hits = [a for a in doc["addons"] if a.get("id") == q]
                    if not hits:
                        hits = [a for a in doc["addons"] if q in a.get("id", "") or what.lower() in a.get("name", "").lower()]
                    if not hits:
                        await event.reply("No addon found for: " + what + "\nUse /db list.")
                    elif len(hits) > 1:
                        await event.reply("More than one match, send /db edit <id> with one of: " +
                                          ", ".join(a["id"] for a in hits[:10]))
                    else:
                        edit["id"] = hits[0]["id"]
                        touch_edit()
                        await event.reply(
                            "Editing: %s (%s)\n"
                            "Send a photo = new banner (photo caption = new description).\n"
                            "Send a file = replaces the addon file (file caption = new name).\n"
                            "/db name <text>, /db desc <text> change text. /db edit done to finish." %
                            (hits[0].get("name"), hits[0]["id"]))
                except Exception:
                    await event.reply("Could not open edit mode. Try again.")
        elif low.startswith("name ") or low.startswith("desc "):
            if not edit_active():
                await event.reply("Open an addon first: /db edit <name>")
            else:
                field = "name" if low.startswith("name ") else "description"
                val = arg[5:].strip()[:80 if field == "name" else MAX_DESC]
                eid = edit["id"]
                try:
                    def mut(doc):
                        for a in doc["addons"]:
                            if a.get("id") == eid:
                                a[field] = val
                    async with lock:
                        await asyncio.to_thread(_update_data, mut, "Edit %s %s" % (field, eid))
                    touch_edit()
                    await event.reply("Updated %s of %s." % (field, eid))
                except Exception:
                    await event.reply("Could not save that. Try again.")
        elif low.startswith("channel"):
            rest = arg[7:].strip().split()
            if rest and rest[0].lower() in ("on", "off") and len(rest) == 2:
                try:
                    ent = await client.get_entity(int(rest[1]) if re.match(r"^-?\d+$", rest[1]) else rest[1])
                    from telethon import utils as _tu
                    cid = _tu.get_peer_id(ent)
                    await _set_channel(cid, rest[0].lower() == "on")
                    await event.reply("Channel import %s for %s (%s)." % (rest[0].lower(), getattr(ent, "title", "channel"), cid))
                except Exception:
                    await event.reply("Could not find that channel. Make sure the bot is an admin there, or post /db channel on inside the channel.")
            else:
                await event.reply("Channel import is on for: %s\nUsage: /db channel on <id or @name>, /db channel off <id or @name>. Or post /db channel on inside the channel (bot must be admin)." %
                                  (", ".join(str(c) for c in state.get("channels") or []) or "none"))
        elif low.startswith("promo"):
            rest = arg[5:].strip()
            m = re.match(r"^del(?:ete)?\s+([12])$", rest, re.I)
            m2 = re.match(r"^([12])(?:\s+(https?://\S+))?$", rest, re.I)
            if m:
                slot = int(m.group(1))
                try:
                    def mut(doc):
                        p = list(doc.get("promos") or [])
                        while len(p) < 2:
                            p.append(None)
                        p[slot - 1] = None
                        while p and not p[-1]:
                            p.pop()
                        doc["promos"] = p
                    async with lock:
                        await asyncio.to_thread(_update_data, mut, "Remove promo %d" % slot)
                    await event.reply("Promo %d removed." % slot)
                except Exception:
                    await event.reply("Could not remove the promo. Try again.")
            elif m2:
                promo["slot"] = int(m2.group(1))
                promo["link"] = m2.group(2) or ""
                promo["t"] = time.time()
                await event.reply("Promo %d: now send the image as a photo within 5 minutes.%s" %
                                  (promo["slot"], "" if promo["link"] else " (No link set; add one with /db promo %d <https link>.)" % promo["slot"]))
            else:
                await event.reply("Usage: /db promo 1 [https link] then send a photo. /db promo del 1")
        elif low == "list":
            try:
                doc, _ = await asyncio.to_thread(_read_data)
                ids = [a.get("id", "?") for a in doc["addons"]][-30:]
                await event.reply("In database (%d): %s" % (len(doc["addons"]), ", ".join(ids) or "(empty)"))
            except Exception:
                await event.reply("Could not read the database.")
        else:
            await event.reply("Usage: /db on, /db off, /db list, /db del <id>, /db edit <name>, /db promo 1|2, /db promo del 1|2")
        raise events.StopPropagation

    # ---- photos (banner) ---------------------------------------------
    @client.on(events.NewMessage())
    async def on_photo(event):
        msg = event.message
        if msg.out or not msg.photo or msg.document or not is_owner(event) or not (mode_on() or edit_active() or promo_active()):
            return
        data = await client.download_media(msg.photo, file=bytes)
        if promo_active():
            slot, link = promo["slot"], promo["link"]
            promo["slot"] = None
            try:
                async with lock:
                    path = "banners/promo-%d-%d.jpg" % (slot, int(time.time()))
                    st, _ = await asyncio.to_thread(_gh, "PUT", _api("contents/" + path), {
                        "message": "Promo %d" % slot, "branch": BRANCH,
                        "content": base64.b64encode(data).decode()})
                    if st not in (200, 201):
                        raise RuntimeError("promo img %s" % st)
                    url = "https://raw.githubusercontent.com/%s/%s/%s" % (GH_REPO, BRANCH, path)

                    def mut(doc):
                        p = list(doc.get("promos") or [])
                        while len(p) < 2:
                            p.append(None)
                        p[slot - 1] = {"image": url, "link": link} if link else {"image": url}
                        doc["promos"] = p
                    await asyncio.to_thread(_update_data, mut, "Set promo %d" % slot)
                await event.reply("Promo %d set." % slot)
            except Exception:
                await event.reply("Could not set the promo. Send /db promo %d again." % slot)
            raise events.StopPropagation
        desc = (msg.message or "").strip()[:MAX_DESC]
        now = time.time()
        if edit_active():
            eid = edit["id"]
            try:
                async with lock:
                    url = await asyncio.to_thread(_put_banner, eid, data, "jpg")

                    def mut(doc):
                        for a in doc["addons"]:
                            if a.get("id") == eid:
                                a["banner"] = url
                                a.pop("icon", None)
                                if desc:
                                    a["description"] = desc
                    await asyncio.to_thread(_update_data, mut, "Edit banner " + eid)
                touch_edit()
                await event.reply("Banner updated for " + eid)
            except Exception:
                await event.reply("Could not update the banner. Send the photo again.")
            raise events.StopPropagation
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
        if msg.out or not msg.document or not is_owner(event) or not (mode_on() or edit_active()):
            return  # everyone else, and owner with mode off: unchanged renamer path
        eid = edit["id"] if edit_active() else None
        now = time.time()
        in_batch = (not eid) and (not queue.empty() or now - batch["last"] < 10)
        batch["last"] = now
        if in_batch:
            await queue.put((msg, _Quiet(), None, {"quiet": True, "batch": True}))
            raise events.StopPropagation
        try:
            status = await msg.reply("Replacing the file of %s..." % eid if eid else "Added to database queue.")
        except errors.FloodWaitError:
            status = _Quiet()
        await queue.put((msg, status, eid, {"batch": not eid}))
        raise events.StopPropagation

    class _Quiet:
        async def edit(self, *a, **k):
            return None

    async def add_one(msg, status, quiet=False, photo=None, ignore_caption=False, result=None):
        if result is None:
            result = {}
        doc = msg.document
        fname = next((a.file_name for a in doc.attributes if getattr(a, "file_name", None)), "addon")
        fname = fname.replace("/", "_").replace("\\", "_")
        caption = (msg.message or "").strip()
        if ignore_caption or getattr(msg, "fwd_from", None):
            caption = ""  # forwarded posts carry channel promo captions, not a chosen name
        custom = bool(caption)
        stem = os.path.splitext(fname)[0]
        name = caption.splitlines()[0][:80] if custom else _clean_name(stem)
        ext = os.path.splitext(fname)[1].lower()
        typ = TYPES.get(ext, "file")
        tmp = Path(tmp_dir) / ("db_%s" % msg.id)
        aid = None
        committed = False

        async def say_dup(a):
            result["dup"] = True
            text = "This file is already in the database: %s (%s). Nothing was added." % (
                a.get("name") or a.get("id"), a.get("id"))
            try:
                if getattr(msg, "is_channel", False) and not getattr(msg, "is_group", False):
                    await client.send_message(owner_id, text + " (channel post, file %s)" % fname)
                else:
                    await msg.reply(text)
            except Exception:
                pass
        try:
            tmp.parent.mkdir(exist_ok=True)
            d0, _ = await asyncio.to_thread(_read_data)
            hit = _find_dup_by_name(d0["addons"], fname)
            if hit:
                await say_dup(hit)
                return
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
            sha = await asyncio.to_thread(_sha256_file, str(tmp))
            async with lock:
                doc0, _ = await asyncio.to_thread(_read_data)
                hit = _find_dup_by_name(doc0["addons"], fname) or await asyncio.to_thread(
                    _find_dup_by_content, doc0["addons"], sha, os.path.getsize(str(tmp)))
                if hit:
                    await say_dup(hit)
                    return
                taken = set(a.get("id") for a in doc0["addons"])
                base = _slug(name)
                aid, n = base, 2
                while aid in taken:
                    aid = "%s-%d" % (base, n)
                    n += 1
                dl_url, _rid, size = await asyncio.to_thread(
                    _create_release_and_upload, aid, name, str(tmp), fname)
            # 1) link first
            if not quiet:
                await msg.reply("Download link:\n" + dl_url)
            # banner / description: the owner's own first
            banner_url = None
            is_icon = False
            desc = caption[:MAX_DESC] if custom else ""
            source = ""
            now = time.time()
            pp = pending["photo"]
            have_img = False
            if photo:
                have_img = True
                async with lock:
                    banner_url = await asyncio.to_thread(_put_banner, aid, photo[0], photo[1])
            elif pp and now - pp["t"] < PENDING_TTL:
                pending["photo"] = None
                have_img = True
                desc = pp["desc"] or desc
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
            if not custom and not have_img:
                try:
                    await status.edit("Database: looking up details")
                except Exception:
                    pass
                found = await asyncio.to_thread(_mcpedl_lookup, name)
                if found:
                    source = found.get("url", "")
                    if not have_img and found.get("image"):
                        data, iext = await asyncio.to_thread(_fetch_image, found["image"])
                        if data:
                            async with lock:
                                banner_url = await asyncio.to_thread(_put_banner, aid, data, iext)
                            have_img = True
            entry = {"id": aid, "name": name, "description": desc, "banner": banner_url,
                     "download": dl_url, "size": size, "type": typ, "filename": fname,
                     "sha256": sha, "added": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
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
            result["ok"] = True
            try:
                await status.edit("Added to the database: %s\nIt shows on the site in about a minute.%s" % (name, extra))
            except Exception:
                pass
        except Exception as exc:
            if aid and not committed:
                try:
                    await asyncio.to_thread(_delete_release, aid)
                except Exception:
                    pass
            if isinstance(exc, errors.FloodWaitError):
                raise
            result["ok"] = False
            try:
                await status.edit("Could not add this file to the database. Nothing was published. Try again.")
            except Exception:
                pass
        finally:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass

    async def edit_file(msg, status, eid):
        doc = msg.document
        fname = next((a.file_name for a in doc.attributes if getattr(a, "file_name", None)), "addon")
        fname = fname.replace("/", "_").replace("\\", "_")
        caption = "" if getattr(msg, "fwd_from", None) else (msg.message or "").strip()
        ext = os.path.splitext(fname)[1].lower()
        typ = TYPES.get(ext, "file")
        tmp = Path(tmp_dir) / ("dbe_%s" % msg.id)
        try:
            tmp.parent.mkdir(exist_ok=True)
            await client.download_file(doc, file=str(tmp), part_size_kb=512, file_size=doc.size or None)
            async with lock:
                cur, _ = await asyncio.to_thread(_read_data)
                entry = next((a for a in cur["addons"] if a.get("id") == eid), None)
                if entry is None:
                    raise RuntimeError("entry gone")
                dl_url, size = await asyncio.to_thread(_replace_asset, eid, str(tmp), fname)
                banner_url = None
                if not entry.get("banner") or entry.get("icon"):
                    idata, iext = await asyncio.to_thread(_extract_icon, str(tmp))
                    if idata:
                        banner_url = await asyncio.to_thread(_put_banner, eid, idata, iext)

                def mut(d):
                    for a in d["addons"]:
                        if a.get("id") == eid:
                            a["download"] = dl_url
                            a["size"] = size
                            a["filename"] = fname
                            a["type"] = typ
                            if caption:
                                a["name"] = caption.splitlines()[0][:80]
                                a["description"] = caption[:MAX_DESC]
                            if banner_url:
                                a["banner"] = banner_url
                                a["icon"] = True
                await asyncio.to_thread(_update_data, mut, "Edit file " + eid)
            touch_edit()
            await msg.reply("Download link:\n" + dl_url)
            await status.edit("File replaced for %s. The site updates in about a minute." % eid)
        except Exception:
            try:
                await status.edit("Could not replace the file. The old entry is unchanged or still works. Try again.")
            except Exception:
                pass
        finally:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass

    batch = {"ok": [], "fail": [], "last": 0.0}

    def _label(msg):
        try:
            return next((a.file_name for a in msg.document.attributes if getattr(a, "file_name", None)), "file")
        except Exception:
            return "file"

    async def _flush_batch():
        if len(batch["ok"]) + len(batch["fail"]) < 2:
            batch["ok"].clear(); batch["fail"].clear()
            return
        txt = "Batch finished: %d added" % len(batch["ok"])
        if batch["fail"]:
            txt += ", %d failed (nothing published for these, send them again):\n- %s" % (
                len(batch["fail"]), "\n- ".join(batch["fail"][:40]))
        batch["ok"].clear(); batch["fail"].clear()
        try:
            await client.send_message(owner_id, txt)
        except Exception:
            pass

    async def db_worker():
        while True:
            item = await queue.get()
            msg, status, eid = item[0], item[1], item[2]
            opts = item[3] if len(item) > 3 else {}
            tries = opts.get("tries", 0)
            res = {}
            retry = False
            try:
                if eid:
                    await edit_file(msg, status, eid)
                    res["ok"] = True
                else:
                    await add_one(msg, status, quiet=opts.get("quiet", False), photo=opts.get("photo"),
                                  ignore_caption=opts.get("ignore_caption", False), result=res)
                    if res.get("ok") and opts.get("notify"):
                        try:
                            await client.send_message(owner_id, "Channel import done: " + opts["notify"])
                        except Exception:
                            pass
            except errors.FloodWaitError as e:
                if tries < 4:
                    opts["tries"] = tries + 1
                    retry = True
                    await asyncio.sleep(min(e.seconds, 900) + 2)
                    await queue.put((msg, status, eid, opts))
                else:
                    res["ok"] = False
            except Exception:
                res["ok"] = False
            if not retry and opts.get("batch") and not res.get("dup"):
                (batch["ok"] if res.get("ok") else batch["fail"]).append(_label(msg))
            if opts.get("quiet") or opts.get("batch"):
                await asyncio.sleep(2)   # gentle pacing for batches and channel imports
            if queue.empty() and not retry and (batch["ok"] or batch["fail"]):
                await _flush_batch()

    # ---- channel auto-import ------------------------------------------
    def chan_on(chat_id):
        return bool(GH_TOKEN) and chat_id in (state.get("channels") or [])

    @client.on(events.NewMessage(func=lambda e: bool(e.is_channel) and not e.is_group))
    async def on_channel(event):
        msg = event.message
        cid = event.chat_id
        text = (msg.message or "").strip()
        m = re.match(r"^/db\s+channel\s+(on|off)\s*$", text, re.I)
        if m:
            await _set_channel(cid, m.group(1).lower() == "on")
            try:
                await event.reply("Channel import %s for this channel." % m.group(1).lower())
            except Exception:
                pass
            raise events.StopPropagation
        if not msg.document or not chan_on(cid):
            return
        photo = None
        try:
            if msg.grouped_id:
                await asyncio.sleep(2)
                near = await client.get_messages(cid, ids=list(range(max(1, msg.id - 9), msg.id + 10)))
                for o in near:
                    if o and o.grouped_id == msg.grouped_id and o.photo and not o.document:
                        data = await client.download_media(o.photo, file=bytes)
                        photo = (data, "jpg")
                        break
        except Exception:
            photo = None
        fname = next((a.file_name for a in msg.document.attributes if getattr(a, "file_name", None)), "addon")
        await queue.put((msg, _Quiet(), None, {"quiet": True, "photo": photo, "ignore_caption": True,
                                              "notify": _clean_name(os.path.splitext(fname)[0])}))
        raise events.StopPropagation

    async def _set_channel(cid, on):
        chans = list(state.get("channels") or [])
        if on and cid not in chans:
            chans.append(cid)
        if not on and cid in chans:
            chans.remove(cid)
        state["channels"] = chans
        _save_state(state)

    client.loop.create_task(db_worker())
