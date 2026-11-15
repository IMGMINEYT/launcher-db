"""Best-effort public Add-Ons lookup. Never downloads content or logs names.

Uses the first-party search service observed on Minecraft's Add-Ons page.
No public stability guarantee exists: errors/ambiguity always mean no link.
"""
import asyncio
import hashlib
import json
import re
import time
import unicodedata
from collections import OrderedDict
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, HTTPRedirectHandler, build_opener

from rename_logic import split_base_ext, earliest_marker_cut

SEARCH_ENDPOINT = 'https://net-secondary.web.minecraft-services.net/api/v1.0/en-us/search'
_CACHE = OrderedDict()
_CACHE_TTL = 600
_CACHE_MAX = 128
_MAX_RESPONSE = 512 * 1024


def filename_query(filename):
    base, _ = split_base_ext(filename.strip())
    cut = earliest_marker_cut(base)
    if cut is not None:
        base = base[:cut]
    # Do not use custom strip words: lookup comes from the original filename.
    base = unicodedata.normalize('NFKC', base).replace('_', ' ')
    base = ''.join(c for c in base if not unicodedata.category(c).startswith('C'))
    base = re.sub(r'\s+', ' ', base).strip(' -_,')
    return base if 2 <= len(base) <= 128 else ''


def title_key(title):
    if not isinstance(title, str):
        return ''
    title = unicodedata.normalize('NFKC', title).casefold().replace('&', ' and ').replace('+', ' plus ')
    title = re.sub(r'[^\w\s]', ' ', title, flags=re.UNICODE).replace('_', ' ')
    words = title.split()
    if words[-2:] == ['add', 'on']:
        words = words[:-2]
    elif words[-1:] == ['addon']:
        words = words[:-1]
    return ' '.join(words)


def official_url(url):
    if not isinstance(url, str) or len(url) > 1024 or any(c.isspace() for c in url):
        return False
    try:
        p = urlsplit(url)
        return (p.scheme == 'https' and p.netloc == 'www.minecraft.net'
                and not p.query and not p.fragment
                and bool(re.fullmatch(r'/en-us/marketplace/pdp/[^/]+/[^/]+/[0-9a-fA-F-]{36}', p.path)))
    except ValueError:
        return False


def file_caption(match):
    """Telegram document captions permit 1024 characters; plain text only."""
    if not isinstance(match, dict) or not official_url(match.get('url')):
        return None
    caption = 'Official Minecraft Marketplace: ' + match['url']
    # Conservative UTF-16 budget also handles non-BMP URL characters safely.
    return caption if len(caption.encode('utf-16-le')) // 2 <= 1024 else None


def select_match(query, payload):
    """Owner selected the top search result, not a later or unique result."""
    try:
        rows = payload['result']['results']
        if not rows:
            return None
        row = rows[0]
        if (row.get('packType') == 'AddOn' and row.get('type') == 'Marketplace'
                and title_key(query) and title_key(row.get('title', '')) == title_key(query)
                and official_url(row.get('url'))):
            return {'url': row['url'], 'title': row['title']}
        return None
    except (KeyError, TypeError, AttributeError, IndexError):
        return None


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _read_json(url):
    # Both calls use fixed first-party service origins, never arbitrary input URLs.
    req = Request(url, headers={'User-Agent': 'AddonMania-LinkLookup/1.0', 'Accept': 'application/json'})
    with build_opener(_NoRedirect()).open(req, timeout=2) as response:
        data = response.read(_MAX_RESPONSE + 1)
        if len(data) > _MAX_RESPONSE:
            return None
    return json.loads(data)


def _fetch(query):
    params = {'query': query, 'pageSize': 24,
              'sortType': 'Relevance', 'category': 'Marketplace',
              'radical': 'mc-search-web-ranking-rad-integration-v1',
              'geography': 'US', 'filter[packType]': 'Add-On'}
    match = select_match(query, _read_json(SEARCH_ENDPOINT + '?' + urlencode(params)))
    if not match:
        return None
    item_id = urlsplit(match['url']).path.rsplit('/', 1)[1]
    detail_url = SEARCH_ENDPOINT.rsplit('/', 1)[0] + '/marketplace/item/' + item_id + '?geography=US'
    detail = _read_json(detail_url)
    verified = select_match(query, {'result': {'results': [detail.get('result', {})]}})
    if verified and urlsplit(verified['url']).path.rsplit('/', 1)[1] == item_id:
        return verified
    return None


async def lookup_filename(filename):
    query = filename_query(filename)
    if not query:
        return None
    # RAM only, hashed keys, no disk/query log, bounded retention and size.
    key = hashlib.sha256(query.encode()).digest()
    now = time.monotonic()
    for stale in [k for k, (expiry, _) in _CACHE.items() if expiry <= now]:
        del _CACHE[stale]
    if key in _CACHE:
        return _CACHE[key][1]
    try:
        result = await asyncio.wait_for(asyncio.to_thread(_fetch, query), timeout=5)
    except Exception:
        result = None
    _CACHE[key] = (now + _CACHE_TTL, result)
    while len(_CACHE) > _CACHE_MAX:
        _CACHE.popitem(last=False)
    return result
