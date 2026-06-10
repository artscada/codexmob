package com.ai.assistance.operit.core.application

import android.app.Application

class CodexApplication : Application() {
    companion object {
        lateinit var instance: CodexApplication
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
