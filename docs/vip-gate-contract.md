# VIP Gate Contract (DRAFT v3 — aligned with bot rev2 implementation; nothing deployed)

Status: design + local code on both sides (app: dark-flagged client; bot:
unwired module). v3 records the as-implemented protocol after the bot
worker's rev2 hardening; v2's plain-SHA verifier, rotation-surviving tokens
and gen-free signed URLs are superseded.

Approval gates (user, timed by main; NONE granted yet):
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
- Verifier: scrypt(code, random salt) stored server-side (slow KDF; a leaked
  state file does not trivialize brute force). Owner codes pass a strength
  check; `/vip code gen` mints a high-entropy code shown once to the owner.
- Success 200: `{"token": "<b64url(exp.gen)>.<b64url(HMAC-SHA256(payload))>", "exp": <unix>}`, TTL 30 days.
- Failure 401 `bad_code` (fixed 1s delay); 503 `gate_off` when the gate is off.
- Rate limit: 5 tries/min/IP + 30/min global (spoofed-IP rotation still hits
  the global wall). X-Forwarded-For is IGNORED by default; honored only from
  a configured trusted-proxy set whose behavior is verified at deploy time.
- The code is never logged, returned, or stored in plaintext.

### GET /vip/catalog
Header: `X-VIP-Token`; 401 `unauthorized` if invalid/expired/rotated;
503 `gate_off` when off (fail closed — the gate never re-exposes anything
on its own; returning to a public catalog would need the user's explicit
instruction, which does not exist).
Returns the addons.json document with ROOT-RELATIVE per-file signed URLs:
`"download": "/vip/file/<id>?s=<hmac(id,exp,gen)>&e=<exp>"` — the app
resolves them against the gate base URL. Signatures are generation-bound
and expire in 24h. OPEN USER DECISION: these are bearer URLs — anyone given
the link can use it within its lifetime (24h, or until rotation).

### GET /vip/file/<id>?s=..&e=.. (rev3: token REQUIRED)
- A valid `X-VIP-Token` is required in addition to the signature (bot rev3,
  implemented): gate off -> 404, missing/bad token -> 401, bad signature -> 404.
  A shared bearer URL alone is useless. The app's downloader always sends the
  header on gate-origin requests.
- Signed URLs live 10 minutes (bot rev3, FILE_URL_TTL_SECONDS=600). There is no
  separate refresh endpoint: the app re-fetches /vip/catalog (token-auth) and
  takes the fresh signature. App-side: a 404 on a gate URL triggers exactly one
  catalog refresh and retry (Api.refreshGateUrl + Downloader.openFresh), never
  an off-origin URL, verified end-to-end with a real Range resume across expiry.
- Streams bytes from Telegram; single `Range` supported (200/206/416);
  max 3 concurrent streams (free-plan sizing).
- Origin rule (implemented, dark): in VIP mode the app accepts download URLs
  only on the gate's exact origin (scheme+host+port); cleartext is accepted
  only against a loopback test gate (127.0.0.1/localhost), never in production,
  and redirects leaving the gate origin are refused.

### GET /vip/health
`{"ok": true, "on": <bool>, "vault": <count>}` — no auth.

### Token/session semantics (rev2)
- Rotating the code (`/vip code <new>` or `/vip code gen`) bumps the
  generation: every previously issued token AND signed URL dies at once.
- `/vip revokeall` resets the gate secret AND clears the code verifier:
  everything dies; owner must set a fresh code.
- App-side mapping: 401 → lock screen ("code expired or declined"); 503 →
  "VIP server is temporarily off, tap to retry"; transport failure → honest
  error. The app NEVER falls back to the public mirrors in VIP mode.

## Owner commands (ADDON MANIA bot, owner-only via existing owner_gate)

| command | effect |
| --- | --- |
| `/vip code <newcode>` | strength-checked; stores scrypt verifier; instantly active; bumps generation (all earlier tokens and signed URLs die) |
| `/vip code gen` | generates a high-entropy code, shown once in the owner's own chat; same generation bump |
| `/vip off` | fail closed: checks/catalog 503, files 404, nothing served until a new code |
| `/vip status` | gate on/off, code set-at time, generation, vault file count |
| `/vip revokeall` | resets the gate secret + clears verifier: all tokens and signed URLs die; new code required |

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
- aiohttp behavior on the 256MB free plan (memory headroom under streams).
- Observed Telegram throughput on this host was 500–700KB/s at best
  (2026-10 tests) → a 156MB pack ≈ 4–5 min minimum, far slower than the
  current GitHub CDN. A gate outage (99% uptime target, renewal lapses)
  blocks ALL gated DB access.

Verification DONE since v2:
- Cross-language end-to-end vs the bot worker's REAL rev3 Python gate
  (aiohttp, loopback, fake vault, real scrypt/HMAC paths), real Java client,
  zero protocol mocking, two tests: (1) health, wrong-code 401, real token
  parses+stores, catalog with relative-URL resolution, 10min TTL asserted on
  catalog URLs, file-without-token 401, full + Range download byte-accurate
  with the token header, forged-signature 404, no-auth catalog 401; (2) the
  app's REAL Downloader resuming an expired-URL partial download: Range-seeded
  partial -> stale URL confirmed 404 -> automatic catalog refresh -> 206 resume
  -> byte-accurate completion. Plus a Java mock-gate suite covering
  rotation/revokeall/gate-off kill semantics. 78 offline tests green.
- laby.net skins: fixture-verified only. The test runner is 429-banned
  (multi-hour Retry-After, observed 2026-11-19); the suite makes zero live
  laby.net requests (JVM-wide fixture). Live availability unknown; nothing
  claimed about normal users. Production handling: per-origin 429 cooldown
  honoring Retry-After with stale-cache fallback.

## Honest scope statement

This gate stops casual/public discovery of DB content going forward. It does
not and cannot protect copies already downloaded or re-shared, and it does
not prevent VIP code sharing between users. No client-side-only alternative
provides real protection and none is proposed.
