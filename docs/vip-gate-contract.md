# VIP Gate Contract (DRAFT v2 — not implemented; no bot/hosting/content changes made)

Status: design proposal. Owner: this doc is app-side + contract design; bot
implementation belongs to the bot worker via main. Nothing here has been
deployed, exposed, or migrated.

Approval gates (user, timed by main):
- A: bot code change + opening the bot's one port (bot/hosting mutation)
- B: uploading catalog files to a new private vault channel (content copy)
- C: deleting public release assets / public catalog (destructive, separate
  from A/B, does NOT make already-downloaded copies protected)

## Three separable layers (per main, 2026-11-19)

1. **Code validation service** — gate checks a code, issues tokens. Alone it
   protects nothing; it is the control plane only.
2. **Asset protection (future discovery)** — new/uploads-migrated content is
   served ONLY through the gate. This is where "hides DB content" becomes
   true, and only for content that exists exclusively in the vault.
3. **Legacy-link migration** — deleting the existing public release assets
   and public addons.json. Destructive; breaks every previously shared link
   and the public website's download buttons; and does NOT protect copies
   anyone already downloaded or re-shared. Never claim otherwise.

The user's own existing downloads and app history stay usable and untouched
in every phase. Gating future DB discovery never authorizes removing local
files.

## Architecture (Route A refined: Telegram vault + bot gate)

```
[67 Launcher app] --HTTPS--> [ADDON MANIA bot HTTP gate] --MTProto--> [Telegram vault channel]
                                    ^
                            owner: /vip code <new>
```

- **Vault**: one private Telegram channel, bot is admin (existing import
  pattern). Each protected file lives in the vault; the gated catalog stores
  (channel_id, message_id, file_unique_id) per addon instead of a public URL.
- **Gate**: small HTTP server inside the existing bot process (aiohttp), on
  the deployment's one free-plan port, served at the host's free HTTPS
  subdomain (`*.apps.bot-hosting.cloud` — verified on the host's website-
  hosting page; exact free-plan port→subdomain binding to be confirmed at
  deploy time). Files stream from Telegram via Telethon `iter_download`,
  chunked; nothing is written to the 512MB local disk.

## Endpoints (bot gate)

### POST /vip/check
Body: `{"code": "<user-entered code>"}`
- Constant-time compare of SHA-256(code) with the stored hash.
- Success 200: `{"token": "<base64(exp)>.<HMAC_SHA256(exp, gate_secret)>", "exp": <unix>}`, TTL 30 days.
- Failure 401: `{"error": "bad_code"}`. Rate limit: 5 tries/min/IP, fixed 1s delay on failure.
- The code is never logged, returned, or stored in plaintext.

### GET /vip/catalog
Header: `X-VIP-Token`; 401 if invalid/expired.
Returns the addons.json document with per-file signed URLs:
`"download": "/vip/file/<id>?s=<hmac(id,exp)>&e=<exp>"`, signatures expire in 24h.

### GET /vip/file/<id>?s=..&e=..
- The per-file signature IS the authorization (no token header), so Android
  download managers can fetch the URL directly.
- Streams bytes from Telegram; supports `Range` for resume.
- Unsigned/invalid requests get 404 (no catalog or metadata leak).

### GET /vip/health
`{"ok": true, "vault": <count>}` — no auth; lets the app distinguish
"gate down" from "bad code".

## Owner commands (ADDON MANIA bot, owner-only via existing owner_gate)

| command | effect |
| --- | --- |
| `/vip code <newcode>` | stores SHA-256 hash; instantly active for new checks (already-issued tokens stay valid until their exp) |
| `/vip off` | kill switch: catalog open again |
| `/vip status` | gate on/off, code set-at time, vault file count |

## App side (owned by launcher executor; local work, no new permissions)

- FEED and DOWNLOADS panels check for a stored token; absent/expired → VIP
  lock screen with masked code entry (no logging) → POST /vip/check.
- Token persisted in the existing Keystore-backed store (same mechanism as
  the YouTube key), never in plaintext prefs.
- Api.java routes catalog + downloads to the gate when VIP mode is active;
  401 → lock screen. Gate unreachable → honest error card, NO silent
  fallback to public URLs.
- Existing downloaded files and download history are never touched.

## Verification status (2026-11-19)

Verified:
- Host free plan: 1 port/deployment, free HTTPS subdomain, 256MB RAM,
  512MB disk, 25% CPU, no card, 4-day renewal (bot-hosting.net/pricing and
  /website-hosting, fetched today).
- Telegram API ToS (core.telegram.org/api/terms, fetched today): no clause
  prohibits a bot-operated client serving files from a private channel. The
  client-app obligations (api_id, ecosystem disclosure) apply to the bot,
  which already runs under its own api_id. General abuse/fair-use
  enforcement remains a residual risk at heavy bandwidth.
- Bot API getFile caps downloads at 20MB (why the bot already uses Telethon
  MTProto, 2GB/file ceiling; largest catalog file is 156MB).

Unverified residuals:
- Free-plan port→HTTPS-subdomain binding detail (confirm at deploy).
- Real-world concurrency: 256MB/25% CPU suggests 2–4 simultaneous streams;
  no documented request-rate limit found. Rate limiting is in the design;
  load behavior is unproven until deployed.
- Observed Telegram throughput on this host was 500–700KB/s at best
  (2026-10 tests) → a 156MB pack ≈ 4–5 min minimum, far slower than the
  current GitHub CDN. A gate outage (99% uptime target, renewal lapses)
  blocks ALL gated DB access.

## Honest scope statement

This gate stops casual/public discovery of DB content going forward. It does
not and cannot protect copies already downloaded or re-shared, and it does
not prevent VIP code sharing between users. No client-side-only alternative
provides real protection and none is proposed.
