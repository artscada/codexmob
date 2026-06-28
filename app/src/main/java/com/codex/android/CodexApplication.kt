package com.codex.android

import android.app.Application
import android.util.Log
import com.codex.android.diagnostics.CrashHandler
import com.codex.android.integrations.http.CodexExternalHttpServer

/**
 * Codex Android Application 入口。
 *
 * 负责：
 * - 应用初始化
 * - 崩溃捕获注册
 * - 全局配置加载
 */
class CodexApplication : Application() {

    companion object {
        private const val TAG = "CodexApplication"
        lateinit var instance: CodexApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 注册崩溃捕获
        CrashHandler(this).register()

        CodexExternalHttpServer.getInstance(this).ensureStarted()
        com.codex.android.bridge.gui.GuiIpcServer.getInstance(this).start()

        Log.i(TAG, "CodexApplication 初始化完成")
    }
}
