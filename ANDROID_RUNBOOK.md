# Codex Android - Android App Runbook

## Project Overview
- **Repo**: `weige369/codex-android`
- **Root package**: `com.ai.assistance.operit` (Operit base)
- **App ID**: `com.codex.android`
- **Min SDK**: 26 | **Target SDK**: 34 | **Compile SDK**: 36

## Build System

### Versions (DO NOT CHANGE without verifying compatibility)
| Component | Version | Notes |
|-----------|---------|-------|
| Gradle | 8.13 | |
| AGP | 8.13.2 | |
| Kotlin | 2.1.10 | kapt in Kotlin 2.0+ requires explicit support |
| Room | 2.8.4 | Uses kapt, NOT KSP |
| ObjectBox | 5.4.2 | Uses kapt |
| Compose BOM | 2024.12.01 | |

### Pre-Build Checks
Always run before pushing: `python3 plugins/pre-build-check/scripts/check_all.py`

Checks include:
- `check_manifest.py` - Duplicate activities/services, launcher
- `check_resources.py` - String/style references, CDATA, excludes assets/templates
- `check_xml.py` - XML validity
- `check_kotlin.py` - Codex bridge files have required imports/interfaces
- `check_build.py` - Kotlin version, ObjectBox consistency, kapt deps, stdlib constraint

### Common Build Failures (Fixed)
1. **ObjectBox version mismatch** - build.gradle.kts had 5.3.0, catalog had 5.4.2
2. **Duplicate kapt plugin** - both `alias()` and `id("kotlin-kapt")` applied
3. **Missing Room query data classes** - `CharacterCardChatStats`, `CharacterGroupChatStats`
4. **kotlin-stdlib transitive version conflict** - library pulls 2.3.10, compiler expects 2.1.10
5. **Gradle Kotlin DSL syntax** - Use `it` not `details` in `eachDependency` blocks

### Build Commands
- `./gradlew :app:assembleDebug` - Build only the app module (recommended)
- `./gradlew assembleDebug` - Build all projects (requires NDK for quickjs native build)
- Prefer pushing to GitHub Actions for CI build (no local Android SDK required)

## Module Structure
- `:app` - Single app module
- Native C++ via CMake (sherpa-ncnn is optional via `if(EXISTS)`)

## Codex Integration Layer
- `com.codex.android.codex.CodexManager` - Binary download/lifecycle
- `com.codex.android.bridge.CodexBridge` - WebSocket JSON-RPC bridge
- `com.codex.android.provider.CodexAgentProvider` - AIService interface impl
- `com.codex.android.service.CodexRuntimeService` - Foreground service
- `com.codex.android.ui.CodexActivity` - WebView UI
- `com.codex.android.ui.settings.CodexSettingsScreen` - Settings

## Key Dependencies
- Room, ObjectBox (kapt), Compose, Shizuku, Retrofit, OkHttp
- MCP SDK (`io.modelcontextprotocol.sdk:mcp:1.1.0`)
- BouncyCastle (`bcprov-jdk18on:1.78`)
