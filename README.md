# مِفتاح — Miftah

Personal Android app that pools the **free tiers of several LLM providers** behind one encrypted on-device key vault, one fallback router, one local OpenAI-compatible endpoint, a read-only-tools agent, and importable **SKILL.md** skills.

Inspired by [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi) (MIT License) — reimplemented natively in Kotlin for Android; no original code is bundled.

## Features (v1)

- **Gateway:** in-code catalog for Google Gemini (native API), Groq, Cerebras, Mistral, OpenRouter and GitHub Models (OpenAI-compatible). Multiple keys per provider, stored **AES-256/GCM-encrypted via Android Keystore** — never in plaintext.
- **Router:** user-ordered fallback chain of (provider, model); per-key RPM/RPD tracking against documented free-tier defaults; automatic cooldown + failover on 429/5xx/timeouts; full request log.
- **Chat playground:** streaming replies, per-reply provider/model attribution.
- **Local server:** foreground-service OpenAI-compatible server (`POST /v1/chat/completions`, `GET /v1/models`), protected by an app-generated unified key (required in both modes), off by default. Binds `127.0.0.1` by default; an opt-in network-reach toggle in Advanced settings binds `0.0.0.0` so other apps/devices on the same LAN or the user's Tailscale network can connect. Settings shows a "Connect another app" card with copyable base URLs (this phone / Wi-Fi / Tailscale).
- **Agent mode:** tool registry with JSON-schema tools. v1 tools are **read-only only**: web search (keyless, best-effort), fetch URL, GitHub user repos, device time, calculator — plus skill tools (`load_skill`, `read_skill_file`). Every tool call is audit-logged and shown inline. Write tools cannot be registered without an approval gate (enforced in code).
- **Skills:** import SKILL.md folders/ZIPs or GitHub repos (Claude Code / Hermes format), mandatory review before first enable, progressive disclosure (name+description always; full body on pick or `load_skill`; references via `read_skill_file`), `allowed-tools` enforced. Bundled scripts are listed but **not executable** in this version.
- Arabic-first UI + English, dark mode, advanced settings behind a reveal. No ads, no analytics.

## Identity

- applicationId: `com.ahmed.miftah`
- versionName 1.0.0 / versionCode 1
- Dedicated signing keystore (alias `miftah`), committed only as an encrypted blob (`keystore.enc.b64`); CI decrypts it with repo secrets `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`.

## Build

GitHub Actions (`.github/workflows/build-apk.yml`) builds the release APK and publishes it to Releases on push to `main`.
