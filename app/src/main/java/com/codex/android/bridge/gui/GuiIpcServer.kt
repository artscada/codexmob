package com.codex.android.bridge.gui

import android.content.Context
import android.util.Log
import com.codex.android.runtime.root.SuRootBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class GuiIpcServer private constructor(private val context: Context) {
    companion object {
        private const val TAG = "GuiIpcServer"
        private const val PORT = 8095
        
        @Volatile
        private var instance: GuiIpcServer? = null

        fun getInstance(context: Context): GuiIpcServer {
            return instance ?: synchronized(this) {
                instance ?: GuiIpcServer(context.applicationContext).also { instance = it }
            }
        }
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val rootBridge = SuRootBridge()
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.i(TAG, "GUI IPC Server started on 127.0.0.1:$PORT")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    scope.launch { handleClient(socket) }
                }
            } catch (e: SocketException) {
                if (isRunning) {
                    Log.e(TAG, "SocketException in server loop", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in GUI IPC Server loop", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        Log.i(TAG, "GUI IPC Server stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            val output = socket.getOutputStream()

            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) {
                sendResponse(output, 400, "Bad Request")
                socket.close()
                return
            }
            val method = parts[0]
            val path = parts[1]

            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isBlank()) break
                val lower = line!!.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter("content-length:").trim().toIntOrNull() ?: 0
                }
            }

            if (method != "POST" || !path.startsWith("/api/gui")) {
                sendResponse(output, 404, "Not Found")
                socket.close()
                return
            }

            val bodyBuffer = CharArray(contentLength)
            var readCount = 0
            while (readCount < contentLength) {
                val read = reader.read(bodyBuffer, readCount, contentLength - readCount)
                if (read == -1) break
                readCount += read
            }
            val body = String(bodyBuffer)

            val json = JSONObject(body)
            val action = json.optString("action", "")
            val x = json.optInt("x", -1)
            val y = json.optInt("y", -1)
            val text = json.optString("text", "")
            val targetPath = json.optString("path", "")

            Log.i(TAG, "Received IPC request: action=$action, x=$x, y=$y, text=$text")

            val result = when (action) {
                "click" -> rootBridge.click(x, y)
                "swipe" -> {
                    val x2 = json.optInt("x2", -1)
                    val y2 = json.optInt("y2", -1)
                    val duration = json.optInt("duration", 300)
                    rootBridge.swipe(x, y, x2, y2, duration)
                }
                "input" -> rootBridge.inputText(text)
                "screenshot" -> rootBridge.takeScreenshot(targetPath.ifBlank { "/data/local/tmp/gui_screenshot.png" })
                "dump" -> rootBridge.dumpWindowHierarchy(targetPath.ifBlank { "/data/local/tmp/gui_dump.xml" })
                else -> null
            }

            if (result != null) {
                val responseJson = JSONObject().apply {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                }
                sendResponse(output, 200, responseJson.toString())
            } else {
                sendResponse(output, 400, "Unknown or invalid action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client request", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, response: String) {
        try {
            val statusMessage = when (statusCode) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                else -> "Internal Server Error"
            }
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            val header = """
                HTTP/1.1 $statusCode $statusMessage
                Content-Type: application/json; charset=utf-8
                Content-Length: ${responseBytes.size}
                Connection: close
                
                
            """.trimIndent()
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(responseBytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send response", e)
        }
    }
}
