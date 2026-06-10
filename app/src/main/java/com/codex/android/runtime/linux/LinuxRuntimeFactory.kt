package com.codex.android.runtime.linux

import android.content.Context
import com.codex.android.codex.CodexConnectionSettings
import com.codex.android.runtime.root.RootBridge
import com.codex.android.runtime.root.SuRootBridge

object LinuxRuntimeFactory {
    fun create(context: Context, rootBridge: RootBridge = SuRootBridge()): LinuxBackend {
        return when (LinuxRuntimeMode.fromId(CodexConnectionSettings.loadLinuxRuntimeMode(context))) {
            LinuxRuntimeMode.CHROOT -> ChrootBackend(context, rootBridge)
            LinuxRuntimeMode.PROOT -> ProotBackend(context)
        }
    }
}
