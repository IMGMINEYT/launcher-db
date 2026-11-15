#!/usr/bin/env python3
"""Bulk import a folder of .mcaddon/.mcpack/.mcworld/.mctemplate files into the launcher database.

usage: bulk_import.py FOLDER [--apply] [--include-flagged] [--tag TAG]
Default is a DRY RUN: it only prints what would be imported and what is flagged.
Free community content only: files that look like paid Marketplace content are
flagged and skipped unless --include-flagged is given after a human review.
Needs env GH_TOKEN (repo contents + releases write).
"""
import base64, io, json, os, re, sys, time, zipfile, urllib.request, urllib.error

REPO = "IMGMINEYT/launcher-db"
BRANCH = "main"
TYPES = {".mcaddon": "addon", ".mcworld": "world", ".mcpack": "pack", ".mctemplate": "template"}
TOK = os.environ.get("GH_TOKEN", "")
MARKET = re.compile(r"market\s*place|marketplace|minecraft\s*partner|premium|\bpaid\b|pay\s*to", re.I)


def clean_name(stem):
    n = stem.replace("_", " ")
    if " " not in n.strip():
        n = n.replace(".", " ")
    n = re.sub(r"\s+", " ", n).strip()
    for _ in range(3):
        n = re.sub(r"[\s\-\u2013\u2014|:~(\[]*\bby\s+[^()\[\]]*[)\]]?\s*$", "", n, flags=re.I)
        n = re.sub(r"[\s\-\u2013\u2014|:~(\[]*\bfree\s*mc\s*market\s*place\b[)\]]?\s*$", "", n, flags=re.I)
        n = n.strip(" -\u2013\u2014|:~([_.")
    return n[:80] or stem[:80]


def slug(name):
    s = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return (s or "addon")[:60].strip("-") or "addon"


def inspect(path):
    """Returns (icon_bytes, icon_ext, flags)."""
    flags = []
    icon = (None, None)
    try:
        def scan(zf, depth):
            nonlocal icon
            best = None
            for n in zf.namelist():
                low = n.lower()
                base = low.rsplit("/", 1)[-1]
                if base in ("pack_icon.png", "pack_icon.jpg", "pack_icon.jpeg") and zf.getinfo(n).file_size < 5_000_000:
                    if best is None or n.count("/") < best.count("/"):
                        best = n
                if base == "contents.json":
                    raw = zf.read(n)[:200]
                    try:
                        json.loads(zf.read(n).decode("utf-8-sig"))
                    except Exception:
                        flags.append("encrypted contents.json (typical of paid Marketplace packs)")
                if base == "signatures.json":
                    flags.append("signatures.json present")
                if base == "manifest.json" and zf.getinfo(n).file_size < 200_000:
                    try:
                        t = zf.read(n).decode("utf-8-sig", "ignore")
                        if MARKET.search(t):
                            flags.append("manifest mentions marketplace/partner")
                    except Exception:
                        pass
            if best and not icon[0]:
                icon = (zf.read(best), "png" if best.lower().endswith(".png") else "jpg")
            if depth < 2:
                for n in zf.namelist():
                    if n.lower().endswith((".mcpack", ".zip", ".mcaddon")) and zf.getinfo(n).file_size < 200_000_000:
                        try:
                            scan(zipfile.ZipFile(io.BytesIO(zf.read(n))), depth + 1)
                        except Exception:
                            pass
        scan(zipfile.ZipFile(path), 0)
    except zipfile.BadZipFile:
        flags.append("not a valid zip")
    return icon[0], icon[1], sorted(set(flags))


def gh(method, url, body=None, raw=None, headers=None):
    h = {"Authorization": "Bearer " + TOK, "Accept": "application/vnd.github+json", "User-Agent": "bulk-import"}
    h.update(headers or {})
    data = raw
    if body is not None:
        data = json.dumps(body).encode()
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=h), timeout=600) as r:
                t = r.read()
                return r.status, (json.loads(t) if t else {})
        except urllib.error.HTTPError as e:
            t = e.read()
            if e.code in (403, 429) and attempt < 4:
                time.sleep(30 * (attempt + 1)); continue
            if e.code >= 500 and attempt < 4:
                time.sleep(3); continue
            try:
                return e.code, json.loads(t)
            except Exception:
                return e.code, {}
    return 0, {}


def api(p):
    return "https://api.github.com/repos/%s/%s" % (REPO, p)


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__); return
    folder = a[0]; apply = "--apply" in a; inc = "--include-flagged" in a
    tag = a[a.index("--tag") + 1] if "--tag" in a else "bulk-" + time.strftime("%Y%m%d")
    files = sorted(f for f in os.listdir(folder) if os.path.splitext(f)[1].lower() in TYPES)
    plan, flagged, seen = [], [], set()
    st = None
    existing = set()
    if apply:
        if not TOK:
            sys.exit("GH_TOKEN missing")
        st, cur = gh("GET", api("contents/data/addons.json?ref=" + BRANCH))
        doc = json.loads(base64.b64decode(cur["content"])); sha = cur["sha"]
        existing = set(x.get("id") for x in doc["addons"])
        seen = set(existing)
    for f in files:
        p = os.path.join(folder, f)
        ext = os.path.splitext(f)[1].lower()
        name = clean_name(os.path.splitext(f)[0])
        icon, iext, flags = inspect(p)
        if MARKET.search(f):
            flags.append("file name mentions marketplace/paid")
        base = slug(name); aid, n = base, 2
        while aid in seen:
            aid = "%s-%d" % (base, n); n += 1
        item = dict(file=f, path=p, name=name, id=aid, type=TYPES[ext], icon=icon, iext=iext, flags=flags, size=os.path.getsize(p))
        if flags and not inc:
            flagged.append(item); continue
        seen.add(aid); plan.append(item)
    print("%d files, %d to import, %d flagged" % (len(files), len(plan), len(flagged)))
    for it in flagged:
        print("FLAGGED  %-50s %s" % (it["file"][:50], "; ".join(it["flags"])))
    if not apply:
        for it in plan[:30]:
            print("WOULD ADD %-8s %-40s id=%s%s" % (it["type"], it["name"][:40], it["id"], " (icon)" if it["icon"] else ""))
        print("Dry run. Re-run with --apply to import."); return
    # one release per 900 assets
    done, failed = [], []
    rel = None; count = 0; rn = 0
    def new_release():
        nonlocal rel, rn
        rn += 1
        t = tag if rn == 1 else "%s-%d" % (tag, rn)
        s, r = gh("GET", api("releases/tags/" + t))
        if s != 200:
            s, r = gh("POST", api("releases"), {"tag_name": t, "target_commitish": BRANCH, "name": "Addon files " + t, "body": "Bulk import"})
        rel = r
    new_release()
    entries = []
    for i, it in enumerate(plan, 1):
        try:
            if count >= 900:
                count = 0; new_release()
            asset = re.sub(r"[^A-Za-z0-9._-]+", ".", it["id"] + os.path.splitext(it["file"])[1].lower())
            up = rel["upload_url"].split("{")[0] + "?name=" + asset
            s, r = gh("POST", up, raw=open(it["path"], "rb").read(), headers={"Content-Type": "application/octet-stream"})
            if s not in (200, 201):
                raise RuntimeError("upload %s" % s)
            banner, icon_flag = None, False
            if it["icon"]:
                bp = "banners/%s.%s" % (it["id"], it["iext"])
                s2, c2 = gh("PUT", api("contents/" + bp), {"message": "Banner " + it["id"], "branch": BRANCH,
                                                          "content": base64.b64encode(it["icon"]).decode()})
                if s2 in (200, 201):
                    banner = "https://raw.githubusercontent.com/%s/%s/%s" % (REPO, BRANCH, bp); icon_flag = True
                time.sleep(0.4)
            e = {"id": it["id"], "name": it["name"], "description": "", "banner": banner, "download": r["browser_download_url"],
                 "size": it["size"], "type": it["type"], "filename": it["file"], "added": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
            if icon_flag:
                e["icon"] = True
            entries.append(e); done.append(it["file"]); count += 1
            if len(entries) >= 25:
                sha = flush(entries); entries = []
            print("[%d/%d] ok %s" % (i, len(plan), it["file"][:60]), flush=True)
        except Exception as ex:
            failed.append((it["file"], str(ex)[:80])); print("[%d/%d] FAIL %s %s" % (i, len(plan), it["file"][:50], ex), flush=True)
    if entries:
        flush(entries)
    try:
        urllib.request.urlopen("https://purge.jsdelivr.net/gh/%s@%s/data/addons.json" % (REPO, BRANCH), timeout=20).read()
    except Exception:
        pass
    print("DONE: %d added, %d failed, %d flagged and skipped" % (len(done), len(failed), len(flagged)))
    for f, e in failed:
        print("FAILED", f, e)


def flush(entries):
    for _ in range(5):
        s, cur = gh("GET", api("contents/data/addons.json?ref=" + BRANCH))
        doc = json.loads(base64.b64decode(cur["content"]))
        ids = set(x["id"] for x in doc["addons"])
        doc["addons"].extend(e for e in entries if e["id"] not in ids)
        doc["updated"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        s, r = gh("PUT", api("contents/data/addons.json"), {"message": "Bulk import %d" % len(entries), "branch": BRANCH, "sha": cur["sha"],
                  "content": base64.b64encode(json.dumps(doc, indent=1, ensure_ascii=False).encode()).decode()})
        if s in (200, 201):
            return r["content"]["sha"]
        time.sleep(2)
    raise RuntimeError("could not write addons.json")


if __name__ == "__main__":
    main()
