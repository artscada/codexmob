# 2026-06-10: chroot release for Codex Android RU

This release formalizes the next runtime direction for the fork: keep Codex as a Linux agent, move the Linux runtime toward `chroot`, and keep Android root operations as a separate privileged bridge.

## Release name

- Release title: `chroot`
- Suggested tag: `v2026.06.10-chroot.1`
- App version: `1.11.0+7-chroot.1`
- Version code: `49`

## Why this release exists

The previous `proot`-based Linux environment was useful as a bootstrap layer, but it is not enough for the workloads this fork is targeting.

The main goals of the `chroot` release are:

- give Codex a more complete Linux userspace;
- improve Python, script execution, and workspace behavior;
- reduce the edge cases that appear in `proot` environments;
- keep Android root operations separated from the Linux runtime.

## Target architecture

```text
Codex CLI
  -> Local Exec Bridge
      -> Tool Router
          -> LinuxBackend (proot/chroot)
          -> RootBridge (su only)
          -> File/Policy layer
```

### Linux runtime

Codex runs as a normal Linux agent inside `LinuxBackend`.

Responsibilities:

- `codex exec`
- `bash`
- `python3`
- `pip`
- `git`
- script creation and execution
- workspace access

Preferred backend:

- `ChrootBackend`

Fallback backend:

- `ProotBackend`

### Android privileged bridge

Android system actions are isolated behind `RootBridge`.

This bridge is intentionally narrow. It should execute only specific host-root tasks and return the result back to Codex.

Responsibilities:

- `pm`
- `am`
- `getprop`
- `setprop`
- `iptables`
- `ip`
- `mount`
- `sysctl`
- process inspection

Current direction:

- root only
- no Shizuku path in the final model

## What changes in practice

- Codex no longer needs to run as root just to do Linux work.
- Python scripts can be created and executed in a real Linux userspace.
- Android-root actions stay explicit, auditable, and limited.
- `iptables` and similar tools are handled as dedicated root tools, not as generic shell shortcuts.

## Planned code layout

```text
app/src/main/java/com/codex/android/runtime/linux/
  LinuxBackend.kt
  ProotBackend.kt
  ChrootBackend.kt
  ChrootMountManager.kt
  ChrootInstaller.kt

app/src/main/java/com/codex/android/runtime/root/
  RootBridge.kt
  SuRootBridge.kt

app/src/main/java/com/codex/android/bridge/
  ToolRouter.kt

app/src/main/java/com/codex/android/policy/
  ToolPolicy.kt
```

## Existing code that will be reused

- `LinuxEnvironment` already contains the current proot/rootfs logic.
- `CodexRuntimeService` already decides how Codex is launched.
- `AndroidShellExecutor` already has a root execution branch.
- `CodexLocalExecBridge` already contains direct-command routing that can be moved into `ToolRouter`.

## Release scope

This release is about the runtime split and documentation first.

- `proot` remains as fallback.
- `chroot` becomes the primary target.
- root operations are kept in a separate bridge.
- the first tool set should focus on Linux runtime, Python, and a small number of Android-root commands.

## Initial tool set

### Linux tools

- `linux_run`
- `linux_run_script`
- `linux_write_file`
- `linux_read_file`
- `linux_list_dir`
- `linux_make_dir`
- `python_create_venv`
- `python_pip_install`
- `linux_sync_workspace`

### Root tools

- `root_run`
- `iptables_dump`
- `iptables_apply`
- `iptables_rollback`
- `system_getprop`
- `system_setprop`
- `system_pm`
- `system_am`
- `system_mount_info`
- `system_sysctl_get`
- `system_sysctl_set`
- `system_process_list`

## Release note summary

This is the release where the fork stops treating `proot` as the end state and starts treating `chroot` as the real Linux runtime target for Codex.

