# MeetYou -> codex-android map

This note is a practical translation of the `CodeMzt/MeetYou` architecture into the current `codex-android-RU` fork.

## What MeetYou gets right

MeetYou keeps the system in clean layers:

- `Core` owns conversation state, memory, scheduler, and delivery.
- `ToolRouter` decides where a task should go.
- `ExecutionTarget` performs the work.
- Endpoints and providers are surfaces, not the source of truth.

That separation is the main thing worth copying.

## What we should copy

### 1. Core-owned state

Keep conversation state, runtime state, and task state out of UI widgets and out of the tool packages.

Target files:

- `app/src/main/java/com/codex/android/service/CodexRuntimeService.kt`
- `app/src/main/java/com/codex/android/bridge/CodexLocalExecBridge.kt`
- `app/src/main/java/com/codex/android/provider/CodexMCPBridge.kt`

Goal:

- one owner for conversation/runtime state
- no duplicated state machines in UI and tools

### 2. ToolRouter as the dispatcher

Turn `ToolRouter` into the single entry point for task routing.

Target file:

- `app/src/main/java/com/codex/android/bridge/ToolRouter.kt`

Goal:

- Linux tasks go to `LinuxBackend`
- Android root tasks go to `RootBridge`
- file tasks go to file operations
- network tasks go to network operations
- system tasks go to system operations

### 3. ExecutionTarget thinking

Do not model execution as "one shell".
Model it as explicit targets.

Suggested targets:

- `LINUX`
- `ROOT`
- `FILE`
- `NETWORK`
- `SYSTEM`

Target files:

- `app/src/main/java/com/codex/android/bridge/ToolRouter.kt`
- `app/src/main/java/com/codex/android/runtime/linux/LinuxBackend.kt`
- `app/src/main/java/com/codex/android/runtime/root/RootBridge.kt`

### 4. Narrow host/root whitelist

Keep host/root execution narrow and auditable.

Target files:

- `app/src/main/java/com/codex/android/runtime/root/SuRootBridge.kt`
- `app/src/main/java/com/codex/android/util/AndroidShellExecutor.kt`

Goal:

- root is a controlled bridge, not a generic escape hatch

### 5. Delivery and streaming as separate concern

If we later add background tasks or multi-surface replies, put delivery in a separate layer.

Target candidates:

- `app/src/main/java/com/codex/android/provider/CodexMCPBridge.kt`
- `app/src/main/java/com/codex/android/integrations/http/CodexExternalHttpServer.kt`

## What we should not copy blindly

Do not import the whole MeetYou stack as-is.

Avoid copying directly:

- their full provider ecosystem
- their desktop-specific delivery surfaces
- their backend persistence stack
- their unrelated integrations

Reason:

- our current problem is not multi-surface expansion
- our current problem is making the phone-side execution model coherent

## Practical translation for codex-android

### Current path

`Codex -> CodexLocalExecBridge -> ToolRouter -> LinuxBackend / RootBridge / system/file/network`

### Desired path

`Codex -> ToolRouter -> ExecutionTarget -> backend`

Where:

- `CodexLocalExecBridge` becomes an input/interpretation layer
- `ToolRouter` becomes the dispatcher
- `ExecutionTarget` becomes the explicit execution contract

## File-by-file priority

### Highest priority

- `app/src/main/java/com/codex/android/bridge/ToolRouter.kt`
- `app/src/main/java/com/codex/android/bridge/CodexLocalExecBridge.kt`
- `app/src/main/java/com/codex/android/runtime/linux/ChrootBackend.kt`
- `app/src/main/java/com/codex/android/runtime/root/SuRootBridge.kt`

### Next priority

- `app/src/main/assets/packages/super_admin.js`
- `app/src/main/java/com/codex/android/provider/CodexMCPBridge.kt`
- `app/src/main/java/com/codex/android/util/AndroidShellExecutor.kt`

### Later

- domain tools for mail, notifications, browser, calendar

## Success criteria

This translation is good enough when:

- `ToolRouter` is the clear dispatcher
- no major execution path bypasses it
- Linux and root targets are separated
- new domain tools can be added without touching the core routing model

