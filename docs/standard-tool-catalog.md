# Standard tool catalog for Codex Android

This is the minimal baseline toolset for automation on the device.

## Goal

The router should not guess from a generic shell first.
It should match a task to a standard tool and then send it to the right execution target.

## Base tools

### Linux

- `linux_run`
- `linux_run_script`
- `linux_write_file`
- `linux_read_file`

### Root

- `root_run`
- `root_read`
- `root_write`

### System

- `system_getprop`
- `system_pm`
- `system_am`

### Network

- `iptables_dump`
- `iptables_apply`

### Notifications

- `notify`
- `toast`

## Route logic

The dispatcher should look at:

- action type
- required privileges
- data location
- risk level
- whether the task is file, system, network, or notification oriented

## Current implementation hook

`ToolRouter` now exposes:

- `standardTools()`
- `suggestTool(prompt)`
- `dispatch(...)`

This is the first step toward a real execution catalog instead of a raw shell-first approach.

