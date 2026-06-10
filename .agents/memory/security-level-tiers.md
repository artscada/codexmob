---
name: Security level tiers (Android AI tools)
description: How the user-facing 安全/标准/完全 security switch must stay coherent, and where AI-reachable high-privilege tools are gated.
---

# Security-level switch design

The Android app exposes a user-facing security-level switch (SharedPreferences `codex_prefs` / key `security_level`) governing the AI's high-privilege builtin tools. Centralized in `SecurityPolicy` (`com.codex.android.security`).

## Rule: keep tiers monotonic — never claim a file sandbox at a tier that also allows shell
**Why:** if arbitrary shell is allowed, the AI bypasses any file-path sandbox via `cat`/`cp`/`echo >`. Asserting "files confined to workspace" while shell is on is a false promise to the user (architect flagged this).
**How to apply:** the ladder is safe → standard → full with capability strictly increasing. File sandbox is enforced ONLY at the most-restrictive tier where shell is also off:
- safe: shell OFF, files confined to sandbox root
- standard (default): shell OFF, full file access
- full: shell ON + full file access

If you ever add a tier or capability, preserve this: a sandbox claim is only valid when every escape hatch (shell, exec) is also closed at that tier.

## Where AI-reachable high-privilege tools are gated (gate BOTH)
There are two independent execution paths for the model's builtin tools — gating one is not enough:
- `AnyclawManager.executeAndroidShell` / `executeAndroidFile`
- `provider/CodexMCPBridge.executeBuiltinTool` (`android_shell`, `android_read_file`)

`AndroidShellExecutor` / `DevelopmentEnvironment` also run shell but are internal infra (Codex runtime setup, diagnostics) and NOT exposed as model tools — intentionally ungated. The Codex CLI's own command execution does not flow through the gated builtin-tool paths, so disabling the `android-shell` *tool* does not cripple the CLI agent itself.

## Path-confinement check
`SecurityPolicy.checkFileAccess` canonicalizes both sandbox root and target, then allows only `target == root || target.startsWith(root + separator)`. Canonicalization (not raw string compare) is what defeats `../` and symlink normalization.
