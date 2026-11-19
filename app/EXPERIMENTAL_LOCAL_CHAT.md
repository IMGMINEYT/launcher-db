# Experimental phone-local YouTube chat adapter

Version 2.11-experimental.1 adds an explicit source selector. Existing installations keep the API source by default, their encrypted key, downloads, history, display settings and package identity.

The independently written `YtLocal` adapter uses Android-compatible `HttpURLConnection` and `org.json`. It does not include code, assets or branding from Take Your Stream Chat. Architecture was researched using that project's public repository. Its README says MIT while its checked LICENSE says CC0, so there was no wholesale source copy.

## Current evidence

- 66 deterministic JVM/Robolectric tests passed after the candidate build, including 10 adapter tests and 9 integration tests.
- A fixture source reached the real loopback TCP/WebSocket bridge with original Unicode preserved. This is not a real YouTube or Minecraft test.
- Native main-screen pixels inspected for both selected sources; the compact 400x360 bridge was inspected with a stopped login state. COPY, TEST and internal screen navigation remain present.
- Package/minSdk/signing identity checked on the candidate APK. No device installation or Minecraft runtime was available.
- No new live YouTube fetch was attempted. A previous anonymous-server access boundary remains unresolved. It does not prove the phone-local path works or cannot work.

## Behavior and limits

- Explicit experimental choice; no silent fallback from API mode.
- Parses canonical YouTube URL/ID or basic @handle input, the page's chat continuation and current client version. No desktop Java HTTP API, Python runtime, paid hosting or shared backend.
- No user login cookies, invented consent cookies, Google password, hidden keys or identity rotation.
- Stops on login, consent, forbidden, malformed response, rate limit, network failure or chat end. Stopped state persists across service recreation. SAVE is an explicit restart; it does not clear a server rate-limit wait.
- HTTPS host allowlist, no automatic redirects, bounded response size, 5000-ID dedup window, maximum 200 messages per batch, existing bounded bridge queue. Initial backlog is skipped. Upstream delay is respected with a 5-second floor.
- A redirected @handle, regional-consent page or unsupported current response fails closed. No claim of universal URL/@handle compatibility.
- Custom emoji are readable tokens, not downloaded image emotes. Existing Bedrock display limits still apply.
- Google/YouTube service terms and permission restrictions apply independently of code licensing. Successful parsing is not an access entitlement or unlimited-service guarantee.

## Unchanged incomplete outcomes

VIP remains inactive and the database is not protected. Bot code management is not deployed. Bubble text, size and no-overlap behavior remain unproven in Minecraft. Chat Downloader is not installed into the APK. The new connector has no verified real-live success and is not a full completion of the user's requested update.

No publication is authorized by these notes.
