package com.codex.android.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.codex.android.service.CodexRuntimeService
import com.codex.android.ui.CodexActivity
import com.codex.android.service.RuntimeState

/**
 * Codex 快捷设置磁贴 (Android 7+)。
 *
 * 快速启动/停止 Codex 运行时，点击打开主界面。
 */
@RequiresApi(Build.VERSION_CODES.N)
class CodexTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        // Toggle runtime based on current state
        val currentState = CodexRuntimeService.state.value
        when (currentState) {
            RuntimeState.RUNNING -> {
                CodexRuntimeService.stop(this)
            }
            else -> {
                CodexRuntimeService.start(this)
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val currentState = CodexRuntimeService.state.value
        tile.label = "Codex"
        tile.subtitle = if (currentState == RuntimeState.RUNNING) "运行中" else "已停止"
        tile.state = when (currentState) {
            RuntimeState.RUNNING -> Tile.STATE_ACTIVE
            RuntimeState.ERROR -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
