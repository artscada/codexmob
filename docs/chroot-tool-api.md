# Codex + chroot + root bridge: tool API map

This note describes the concrete routing model for the `chroot` release.

## Core idea

Codex stays a Linux agent.

- Linux work goes through `LinuxBackend`.
- Android system work goes through `RootBridge`.
- `ToolRouter` is the single place where the app decides which side should handle a request.

## Class map

```text
Codex CLI
  -> Local Exec Bridge
      -> ToolRouter
          -> LinuxBackend (ProotBackend / ChrootBackend)
          -> RootBridge (SuRootBridge)
          -> ToolPolicy
```

## Linux backend classes

- `LinuxBackend`
- `ProotBackend`
- `ChrootBackend`
- `LinuxRuntimeFactory`
- `ChrootInstaller`
- `ChrootMountManager`

### Linux commands

These should stay inside the Linux runtime:

- `codex exec`
- `bash`
- `python3`
- `git`
- `pip`
- `mkdir`
- `cp`
- `mv`
- workspace scripts

### Linux tool API

- `linux_run`
- `linux_run_script`
- `linux_write_file`
- `linux_read_file`
- `linux_list_dir`
- `linux_make_dir`

## Root bridge classes

- `RootBridge`
- `SuRootBridge`

### Root commands

These should go through the Android root bridge:

- `pm`
- `am`
- `getprop`
- `setprop`
- `iptables-save`
- `iptables-restore`
- `mount`
- `ip`
- `sysctl`

### Root tool API

- `root_run`
- `iptables_dump`
- `iptables_apply`
- `system_getprop`
- `system_setprop`
- `system_pm`
- `system_am`

## File policy

`ToolPolicy` should keep path and command checks small and explicit.

- Linux runtime gets workspace and script tasks.
- Root bridge gets Android-host tasks only.
- Anything ambiguous should fail closed.

## Current implementation notes

- `CodexRuntimeService` now chooses the Linux backend before launching Codex.
- `CodexConnectionSettings` stores the selected Linux mode.
- `super_admin.js` now exposes `linux_run` and `root_run` names for the package-level API.
- `proot` remains a fallback while `chroot` becomes the target backend.

