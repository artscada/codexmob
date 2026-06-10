package com.codex.android.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Codex WebSocket 桥接器。
 * 
 * 提供 WebView JavaScript 与 Codex exec-server 之间的双向通信。
 * 支持：
 * - 发送 prompt 并接收流式响应
 * - 获取会话历史
 * - 管理 MCP 服务器
 * - 管理 Skills/Plugins
 */
class CodexBridge(
    private val wsUrl: String = "ws://127.0.0.1:9877"
) {
    companion object {
        private const val TAG = "CodexBridge"
        private const val BRIDGE_NAME = "CodexBridge"
    }

    // WebSocket 连接状态
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: OkHttpWebSocket? = null

    // 回调接口 - 由 WebView 层设置
    var onMessage: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConnectionChange: ((ConnectionState) -> Unit)? = null

    // 等待响应的请求
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /**
     * 连接到 Codex exec-server WebSocket
     */
    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED) return
        
        _connectionState.value = ConnectionState.CONNECTING
        onConnectionChange?.invoke(ConnectionState.CONNECTING)
        
        scope.launch {
            try {
                webSocket = OkHttpWebSocket(wsUrl)
                webSocket?.connect(
                    onOpen = {
                        _connectionState.value = ConnectionState.CONNECTED
                        onConnectionChange?.invoke(ConnectionState.CONNECTED)
                        Log.i(TAG, "WebSocket 已连接到 Codex exec-server")
                    },
                    onMessage = { msg ->
                        handleMessage(msg)
                    },
                    onError = { error ->
                        _connectionState.value = ConnectionState.ERROR
                        onError?.invoke(error)
                        onConnectionChange?.invoke(ConnectionState.ERROR)
                        Log.e(TAG, "WebSocket 错误: $error")
                    },
                    onClose = {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        onConnectionChange?.invoke(ConnectionState.DISCONNECTED)
                        Log.i(TAG, "WebSocket 已关闭")
                    }
                )
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.ERROR
                onError?.invoke("连接失败: ${e.message}")
                onConnectionChange?.invoke(ConnectionState.ERROR)
                Log.e(TAG, "WebSocket 连接失败", e)
            }
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        webSocket?.close()
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        onConnectionChange?.invoke(ConnectionState.DISCONNECTED)
    }

    /**
     * 发送 prompt 到 Codex 并等待响应
     */
    suspend fun executePrompt(prompt: String, stream: Boolean = true): String {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[requestId] = deferred

        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", "execute")
            put("params", JSONObject().apply {
                put("prompt", prompt)
                put("stream", stream)
            })
        }

        webSocket?.send(request.toString())

        return try {
            val result = kotlinx.coroutines.withTimeout(300_000) { deferred.await() }
            result
        } catch (e: Exception) {
            "错误: 请求超时或失败 - ${e.message}"
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    /**
     * 非阻塞式发送 prompt（流式响应通过 onMessage 回调）
     */
    fun sendPrompt(prompt: String) {
        val requestId = UUID.randomUUID().toString()

        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", "execute")
            put("params", JSONObject().apply {
                put("prompt", prompt)
                put("stream", true)
            })
        }

        webSocket?.send(request.toString())
    }

    /**
     * 获取 Codex 状态
     */
    suspend fun getStatus(): String {
        return try {
            val requestId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<String>()
            pendingRequests[requestId] = deferred

            val request = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", requestId)
                put("method", "ping")
            }

            webSocket?.send(request.toString())

            val result = kotlinx.coroutines.withTimeout(5000) { deferred.await() }
            result
        } catch (e: Exception) {
            """{"error": "${e.message}"}"""
        }
    }

    /**
     * 处理从 WebSocket 接收的消息
     */
    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)

            // 检查是否是流式响应块
            if (json.has("method") && json.getString("method") == "response") {
                val params = json.getJSONObject("params")
                val content = params.optString("content", "")
                onMessage?.invoke(content)
                return
            }

            // 检查是否是请求的响应
            if (json.has("id")) {
                val id = json.getString("id")
                val deferred = pendingRequests[id]
                if (deferred != null) {
                    if (json.has("result")) {
                        deferred.complete(json.get("result").toString())
                    } else if (json.has("error")) {
                        deferred.complete("错误: ${json.getJSONObject("error").getString("message")}")
                    }
                }
            }

            // 普通消息转发
            onMessage?.invoke(message)
        } catch (e: Exception) {
            Log.w(TAG, "消息解析失败: ${e.message}")
            onMessage?.invoke(message)
        }
    }

    /**
     * 清理资源
     */
    fun destroy() {
        disconnect()
        scope.cancel()
        pendingRequests.clear()
    }

    // ========== JavaScript 接口 ==========

    /**
     * 提供给 WebView JavaScript 调用的接口
     */
    @JavascriptInterface
    fun postMessage(jsonMessage: String) {
        Log.d(TAG, "JS -> Bridge: $jsonMessage")
        try {
            val msg = JSONObject(jsonMessage)
            when (msg.optString("type")) {
                "prompt" -> {
                    val prompt = msg.getString("data")
                    sendPrompt(prompt)
                }
                "ping" -> {
                    scope.launch {
                        val status = getStatus()
                        onMessage?.invoke("""{"type":"status","data":$status}""")
                    }
                }
                "disconnect" -> disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "JS 消息解析失败", e)
            onError?.invoke("消息格式错误: ${e.message}")
        }
    }

    @JavascriptInterface
    fun getConnectionState(): String = _connectionState.value.name

    @JavascriptInterface
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}

/**
 * 简单的 OkHttp WebSocket 封装
 */
class OkHttpWebSocket(private val url: String) {
    private var webSocket: okhttp3.WebSocket? = null
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    fun connect(
        onOpen: () -> Unit,
        onMessage: (String) -> Unit,
        onError: (String) -> Unit,
        onClose: () -> Unit
    ) {
        val request = okhttp3.Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(ws: okhttp3.WebSocket, response: okhttp3.Response) {
                onOpen()
            }

            override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                onMessage(text)
            }

            override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                onError(t.message ?: "未知错误")
            }

            override fun onClosed(ws: okhttp3.WebSocket, code: Int, reason: String) {
                onClose()
            }
        })
    }

    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    fun close() {
        webSocket?.close(1000, "客户端关闭")
        webSocket = null
    }
}

/**
 * 超时辅助函数
 */
