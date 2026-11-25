# مِفتاح — Miftah

Personal Android app that pools the **free tiers of several LLM providers** behind one encrypted on-device key vault, one fallback router, one local OpenAI-compatible endpoint, an agent with read **and approval-gated write tools**, and importable **SKILL.md** skills — including runnable Python skill scripts (embedded CPython via Chaquopy).

Inspired by [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi) (MIT License) — reimplemented natively in Kotlin for Android; no original code is bundled.

## Features (v1)

- **Gateway:** in-code catalog for Google Gemini (native API), Groq, Cerebras, Mistral, OpenRouter and GitHub Models (OpenAI-compatible). Multiple keys per provider, stored **AES-256/GCM-encrypted via Android Keystore** — never in plaintext.
- **Router:** user-ordered fallback chain of (provider, model); per-key RPM/RPD tracking against documented free-tier defaults; automatic cooldown + failover on 429/5xx/timeouts; full request log.
- **Chat playground:** streaming replies, per-reply provider/model attribution.
- **Local server:** foreground-service OpenAI-compatible server (`POST /v1/chat/completions`, `GET /v1/models`), protected by an app-generated unified key (required in both modes), off by default. Binds `127.0.0.1` by default; an opt-in network-reach toggle in Advanced settings binds `0.0.0.0` so other apps/devices on the same LAN or the user's Tailscale network can connect. Settings shows a "Connect another app" card with copyable base URLs (this phone / Wi-Fi / Tailscale).
- **Agent mode:** tool registry with JSON-schema tools. Read-only tools: web search (keyless, best-effort), fetch URL, GitHub user repos, device time, calculator — plus skill tools (`load_skill`, `read_skill_file`). Every tool call is audit-logged and shown inline. Write tools cannot be registered without an approval gate (enforced in code), and all write calls in a round are resolved as ONE batched approval bundle (one decision, per-item exclusion, digest-pinned scope).
- **Stage 2 — write tools (approval-gated):** `github_create_or_update_file` and `github_create_issue` using the stored GitHub token, plus a generic `http_request` tool for any REST API. Named **service credentials** (name + base URL + key header/prefix + API key) are saved in the Keys tab, stored Keystore-encrypted, and injected by the app — tools reference services by name only, keys never enter model context, and approval cards always show the full resolved URL + method. *Gmail is deliberately not included yet: it needs Google OAuth infrastructure the app doesn't have; the generic service system is the supported path until an OAuth decision is made.*
- **Skills:** import SKILL.md folders/ZIPs or GitHub repos (Claude Code / Hermes format), mandatory review before first enable, progressive disclosure (name+description always; full body on pick or `load_skill`; references via `read_skill_file`), `allowed-tools` enforced.
- **Stage 3 — skill scripts (Chaquopy, CPython 3.11):** a skill's `scripts/*.py` files are runnable via the approval-gated `run_skill_script` tool. Rules: 30s hard timeout, stdout/stderr captured and truncated (8 KB), working directory is a per-skill sandbox in app-private storage (original skill files are only ever copied *from*, never modified), the environment contains no provider keys or service credentials, no Android bridge objects are exposed (stdio only), and scripts **may use the network** — stated on the approval card and in the pre-enable review screen, which lists script files separately with a warning. `allowed-tools` extends to scripts: a skill may only run its own `scripts/` files, and path traversal out of that folder is rejected in code. Python is arm64-v8a only and adds roughly 12–16 MB to the APK; pip requirements ship empty (stdlib only).
- Arabic-first UI + English, dark mode, advanced settings behind a reveal. No ads, no analytics.

## Identity

- applicationId: `com.ahmed.miftah`
- versionName 1.0.0 / versionCode 1
- Dedicated signing keystore (alias `miftah`), committed only as an encrypted blob (`keystore.enc.b64`); CI decrypts it with repo secrets `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`.

## Build

GitHub Actions (`.github/workflows/build-apk.yml`) builds the release APK and publishes it to Releases on push to `main`.
