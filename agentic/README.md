# Agentic
Agentic AI phone-control app for Android. Floating ball + mini chat window; an OpenAI-compatible AI reads the screen (Accessibility) and taps/swipes/types for you.

- Onboarding: permissions (overlay + accessibility) -> base URL + API key -> model list -> START AGENTIC
- Key encrypted with Android Keystore (AES-GCM), HTTPS-only, never logged
- Red OFF halts the AI instantly and removes the overlays; 5s long-press on the ball removes it (config kept); auto-remove on screen-off
- Gates: first-time app opens need user approval; payment-related tasks confirm every step
- Build: ../apk toolchain (JDK 17 + SDK build-tools 34), `bash build.sh` -> out/Agentic.apk
