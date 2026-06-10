package com.ai.assistance.operit.util

object AppLogger {
    fun d(tag: String, msg: String) = android.util.Log.d(tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) = android.util.Log.e(tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = android.util.Log.w(tag, msg, tr)
    fun i(tag: String, msg: String) = android.util.Log.i(tag, msg)
    fun v(tag: String, msg: String) = android.util.Log.v(tag, msg)
}
