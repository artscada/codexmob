package com.codex.android.runtime.linux

import android.content.Context
import com.codex.android.util.LinuxEnvironment
import java.io.File

class ChrootInstaller(
    private val context: Context
) {
    private val linuxEnv = LinuxEnvironment(context)

    fun rootfsDir(): File = linuxEnv.getRootfsDir()

    suspend fun install(): Boolean {
        return linuxEnv.installRootfs()
    }
}
