"""Pure filename-rename logic for the FREE MC MARKET PLACE renamer bot.

Rules confirmed by the owner (via main):
- Extension (everything from the LAST dot) is never touched; bytes never touched.
- A standalone "by"/"BY" token cuts the name from that token onward.
- An "@" cuts the name from the "@" onward (including the @).
- Earliest marker wins when both appear (owner steering: rule applies anywhere
  in the name; cutting at the earliest marker removes all later credits).
- Embedded substrings (e.g. "Baby", "ByeBye") are never cut - word boundary only.
- Brand text appended before the extension: "by FREE MC MARKET PLACE".
- Owner-added custom words are removed from the remaining base: every
  occurrence of the exact text, case-insensitive, including embedded.
- If a cut would leave an empty base, the file is rejected (no misleading name).
"""
import re

BRAND = "by FREE MC MARKET PLACE"

_BY_TOKEN = re.compile(r"(?i)(?<![A-Za-z0-9])by(?![A-Za-z0-9])")
_WS = re.compile(r"\s{2,}")


class RenameRejected(Exception):
    """Raised when a file cannot be safely renamed (empty base after cut)."""


def split_base_ext(filename: str):
    """Split into (base, ext-with-dot). No dot -> whole name is the base."""
    base, dot, _ext = filename.rpartition(".")
    if not dot:
        return filename, ""
    return base, "." + _ext


def earliest_marker_cut(base: str):
    """Return index of the earliest standalone 'by' token or '@', else None."""
    cuts = []
    at = base.find("@")
    if at != -1:
        cuts.append(at)
    m = _BY_TOKEN.search(base)
    if m:
        cuts.append(m.start())
    return min(cuts) if cuts else None


def apply_custom_words(base: str, custom_words):
    """Remove owner-added words: every occurrence of the exact text, case-insensitive,
    including embedded (owner: 'bas wo word hata jaaye chahe kitna bhi, jaise bhi likha ho').
    Extension was already split off, so it is never touched."""
    for word in custom_words:
        if not word.strip():
            continue
        pat = re.compile(re.escape(word.strip()), re.IGNORECASE)
        base = pat.sub("", base)
    return _WS.sub(" ", base).strip(" -_,")


def rename(filename: str, custom_words=()):
    base, ext = split_base_ext(filename.strip())
    cut = earliest_marker_cut(base)
    if cut is not None:
        base = base[:cut].rstrip(" -_,")
    base = apply_custom_words(base, custom_words)
    if not base:
        raise RenameRejected(filename)
    return f"{base} {BRAND}{ext}"
