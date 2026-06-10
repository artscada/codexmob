---
name: Android release signing
description: How Codex Android is signed locally and in CI, and why the keystore must be preserved.
---
- `app/build.gradle.kts` enables the `release` signingConfig only when all four `RELEASE_*` keys exist in `local.properties` AND the keystore file exists; otherwise release builds stay unsigned. This same gate means a missing keystore silently downgrades signing rather than failing.
- CI (`.github/workflows/build-apk.yml`) reconstructs `local.properties` from four GitHub Actions secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. All four present → `assembleRelease` + upload signed APK; any missing (e.g. fork PRs) → fall back to `assembleDebug` so the compile gate still works.
- The generated keystore + plaintext passwords are backed up under gitignored `.local/signing/` (never committed). GitHub secret values cannot be read back.

**Why:** An Android app's signing identity is permanent. If the keystore OR its passwords are lost, you can never publish an update to an already-shipped app under the same identity — there is no recovery path.

**How to apply:** When touching signing or the APK workflow, keep the "all-or-nothing secrets → release, else debug" fallback. Never commit a keystore or passwords. If regenerating the keystore, the app's signing identity changes (treat as a new app for update purposes).
