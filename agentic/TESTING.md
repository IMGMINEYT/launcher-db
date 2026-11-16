# v1.1 test candidate

## Checks executed
- Clean Java compilation and Android resource linking, SDK 34, min SDK 24 / target 33.
- DEX generation, APK alignment and cryptographic signature verification.
- Package/version metadata inspection: com.imgmine.agentic, version 1.1 / 12.
- Release signer compared with v1.0 so the update can use the same app identity.
- Source audit of shared instruction routing, pause boundaries, screen-preview retention and service shutdown.

## Not executed
No Android device/emulator is attached. The following remain unverified:
- Actual UI pixels, clipping, keyboard behavior, contrast and landscape layout.
- Installation and startup on a device, including ML Kit component initialization.
- Live accessibility screenshot capture and preview rendering.
- Pause/resume during requests, confirmation dialogs and gestures.
- End-to-end calls to OpenAI-compatible, Gemini or Anthropic providers.
- Android background-service restrictions and screen-off behavior.

This is a test candidate, not a production-ready or fully tested release.

## Expected behavior for device QA
1. Existing permissions and encrypted API settings remain in place after update.
2. Home, Live, History and Settings navigate without changing the floating ball/chat.
3. A Home instruction enters the same chat/task path, then backgrounds the app.
4. While the app dashboard is foreground, new automation actions wait. An already-dispatched Android gesture may finish. Continue on phone backgrounds the app and resumes observation.
5. Pause task holds at action boundaries. Resume re-observes instead of using a pre-pause target. Emergency stop uses the existing OFF path.
6. Preview is the last captured screen, not live video. It uses accessibility screenshots on Android 11+, stays in RAM only, and clears on OFF/screen-off/service destruction.
7. History persists the latest 50 tasks; clear-memory does not clear API keys.
8. First-time app-open and payment confirmations still apply.

## Scope still incomplete against the full master prompt
This milestone is only incremental UI/control work. Separate Tasks navigation, task priorities/real queue/duplicate detection, full provider editing and manual-model/configuration controls, editable/disableable memory, voice, risk-classified confirmations, encrypted structured task history, usage reporting and comprehensive diagnostics are not all implemented. The current history store is app-private SharedPreferences JSON, not an encrypted Room database.

Do not treat the earlier milestone labels as full completion of the corresponding master-prompt sections.

# v1.1.1 speed pass
- Screenshot/OCR no longer run every step: capture only when the screen changed AND (vision first look, explicit "look" action, or a text-poor screen); OCR cached per screen hash; images recycled.
- Old screen observations are dropped from API history (only the latest dump is sent), dump trimmed to 60 elements with shorter text, OCR capped at 25 lines.
- Post-action settle wait reduced; per-step timings (screen/photo/ocr/AI ms) logged in the collapsible step narration for diagnostics.
- Still not device-tested: actual step latency depends on his provider/model speed.

# v1.2 fast behavior + local helper
- Behavior-rules block injected with every API call: act decisively, no re-reading unchanged screens, one-line thoughts, plan only for 3+ step tasks.
- LocalAssist: rule-based instant commands (back/home/recents/scroll/volume/lock/shade) with NO API call and zero tokens; conservative exact-phrase matching only, everything else goes to the AI unchanged. Works even before API setup.
- Tap gets one adjacent-coordinate retry on gesture rejection; swipe durations clamped.
- Caveat: LocalAssist is rules, not a trained mini-AI. Compile/signature verified; not device-tested.

# v1.3 Android-control knowledge + gesture vocabulary
- Always-on ANDROID CONTROL ESSENTIALS block: gesture vocabulary, swipe physics, list/loading behavior, dialogs/ads handling, spinners, tabs, toasts, WebView fallback, rotation. ~450 tokens per call.
- 24 per-app playbooks (WhatsApp, Telegram, YouTube, Instagram, Chrome, Settings, Gmail, Maps, Spotify, X, Facebook, Photos, Play Store, Phone/Contacts, Messages, Camera, Discord, Calculator, Files, Clock, Snapchat, UPI payment apps) - injected ONLY when that app is foreground (token-budgeted).
- New actions: double_tap(_id), drag (press-hold-move), pinch (two-finger zoom), clear (empty input field).
- Security: password fields now redacted (***) in every screen dump.
- Compile/signature verified; gestures not device-tested (pinch/drag semantics may need tuning on real apps).

# v1.4 hardening pass
- Network resilience: transient failures (timeout, empty reply, connection errors) retry twice with 1.5s backoff on the same key before failing the task. Permanent config errors (bad host) fail fast.
- Fixed a stale-index bug in observation-history trimming (old screen dumps now removed by identity, safe after budget trims).
- Rotation/resize handling: dimension change invalidates cached screenshots/OCR so coordinates stay honest.
- Empty screens ("no interactive elements" / "screen unavailable") now auto-request a photo instead of wasting steps.
- Invalid AI replies escalate (hard JSON rule at 2, friendly failure + model-switch advice at 4) instead of looping forever.
- "app not installed" errors now tell the AI to use the exact package from the installed list.

# v1.5 risk-classified security
- LOW (open/scroll/read/navigate/wait/volume/rotate): free, no prompts.
- MEDIUM (typing/sending-style tasks, forms, copy, subscribes): one confirmation per task at the first such action.
- HIGH (taps on Send/Post/Share/Pay/Buy/Delete/Call/Transfer/Book labeled elements, payment/call/delete/order tasks): confirmation EVERY risky action; denial is final for that action.
- Payment-app foreground (Paytm/PhonePe/GPay/BHIM/MobiKwik) now triggers the same every-step payment gate as payment instructions.
- Existing gates unchanged: first-time app-open approval (remembered per package), moneyMode every-step confirm, red OFF.

# v1.6 diagnostics
- LogStore: local action log (last 100 actions: step, action, result, duration) in app-private storage - no secrets, no screen content.
- Settings -> Diagnostics card: today's token estimate (chars/4 heuristic, labeled as estimate, not provider billing), last 10 actions with errors highlighted, one-tap log clear.
- API failures also logged. Compile/signature verified; not device-tested.

# v1.7 voice - EXPERIMENTAL, default OFF
- Voice (push-to-talk) behind a Settings toggle labeled EXPERIMENTAL, OFF by default. RECORD_AUDIO is requested only when the user enables it.
- Mic buttons (Home + overlay chat) fill the input box with recognized text - the user reviews and sends; nothing is auto-sent.
- Mic never stays on: listening stops after each phrase, on tap, and is destroyed with the service/activity.
- SpeechRecognizer (system); on many devices recognition needs internet. Hinglish via free-form language model.
- NOT device-tested: recognition availability varies by device/Android build. This is the riskiest overnight change.
- Not included: text-to-speech replies (remaining work).

# v1.8 maximum-speed pass
- API decoding: temperature 0 + max_tokens caps (OpenAI 700, Anthropic/Gemini 800) - shorter, deterministic action replies.
- Screen dump: resource ids dropped (~20 chars/element saved); vision JPEGs now 512px/q50.
- Multi-action batch replies: AI can return up to 4 obvious low-risk actions in one call (e.g. tap_id then type); every action still passes the same gates (app-open approval, payment every-step, HIGH always-confirm, MEDIUM once-per-task), batch stops on error/denial/pause, single re-read after.
- Evaluated and rejected: parallel screenshot (image must precede the call - negligible gain), early-execute from stream (truncated-JSON risk).
- Compile/signature verified; batch behavior not device-tested.
