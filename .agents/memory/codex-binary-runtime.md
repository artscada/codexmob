---
name: Codex binary runtime constraints on Android
description: Why the downloaded Codex CLI binary can't just run on-device, and how version/arch are handled
---

# Codex CLI binary on Android — runtime constraints

The app downloads the `openai/codex` Rust CLI (linux-musl) at runtime into the app's
private files dir and runs it. Three durable constraints shape this subsystem:

- **Android 10+ (API 29+) blocks executing files from the app's writable data dir (W^X).**
  This — not just "musl vs bionic" — is the real reason a plain downloaded binary can't
  be exec'd directly. **Why:** the platform enforces write-xor-execute on app-private
  storage. **How to apply:** direct execution generally only works via Termux/proot
  (their own exec-allowed dirs) or by shipping the binary inside the APK's
  `nativeLibraryDir` (jniLibs `lib*.so`), which IS exec-allowed. `testDirectExecution()`
  in CodexManager is the honest empirical probe — the definitive answer must come from a
  real device, it can't be predicted at build time.

- **Codex publishes only `aarch64` and `x86_64` linux-musl release artifacts** (tag
  pattern `rust-v<semver>`, artifact `codex-<rust-target>.tar.gz`). 32-bit armv7 is
  unsupported. Arch is detected from `Build.SUPPORTED_ABIS`; unsupported → empty mirror
  list + friendly message. **How to apply:** if upstream changes the tag prefix or
  artifact naming, the download URLs break — pin a known-good `CODEX_VERSION` and let
  users opt into the latest via the in-app check rather than blindly chasing latest.

- **Upgrades don't need an APK rebuild.** The binary is downloaded at runtime, so the
  in-app flow queries the GitHub Releases API for the newest `rust-v*` tag and
  re-downloads. An `.installed_version` marker file records what's installed; manual
  imports leave no marker (version shows as unknown).

**Process exec gotcha:** when probing a binary with `ProcessBuilder(... ).start()`, call
`waitFor(timeout)` BEFORE `readText()` on its output — reading first blocks indefinitely
if the process hangs. Only safe to read-first when output is known-tiny AND you don't
need a timeout.
