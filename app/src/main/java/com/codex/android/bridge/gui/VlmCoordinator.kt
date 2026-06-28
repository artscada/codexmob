package com.codex.android.bridge.gui

import android.content.Context
import android.util.Base64
import android.util.Log
import com.codex.android.codex.CodexConnectionSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class VlmCoordinator private constructor(private val context: Context) {
    companion object {
        private const val TAG = "VlmCoordinator"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var instance: VlmCoordinator? = null

        fun getInstance(context: Context): VlmCoordinator {
            return instance ?: synchronized(this) {
                instance ?: VlmCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class VlmDecision(
        val action: String,
        val selector: String?,
        val selectorType: String?,
        val text: String,
        val reason: String
    )

    suspend fun getVlmDecision(
        prompt: String,
        screenshotFile: File,
        xmlFile: File,
        executionLog: String
    ): VlmDecision? {
        val config = CodexConnectionSettings.load(context)
        if (!CodexConnectionSettings.hasApiConfiguration(config)) {
            Log.e(TAG, "API is not configured. Cannot make VLM call.")
            return null
        }

        try {
            val base64Image = encodeImageToBase64(screenshotFile) ?: return null
            val xmlText = if (xmlFile.exists()) xmlFile.readText().take(12000) else "XML layout unavailable"

            val systemPrompt = """
                You are an Android GUI automation agent.
                Your task is to achieve the user's goal: "$prompt"
                
                Current execution history:
                $executionLog
                
                Below is the truncated XML hierarchy of the current screen:
                $xmlText
                
                Look at the screenshot and XML hierarchy. Determine the next step.
                Choose the best element from the XML layout matching your choice.
                
                You MUST respond with a single, raw JSON object matching this schema:
                {
                  "action": "click" | "input" | "complete",
                  "selector": "the exact text, resource-id, or content-desc of the element to click/input",
                  "selector_type": "text" | "id" | "desc",
                  "text": "text to type (only if action is 'input')",
                  "reason": "short explanation of your decision"
                }
                Do not include markdown blocks (like ```json). Respond with only the JSON object itself.
            """.trimIndent()

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/png;base64,$base64Image")
                            })
                        })
                    })
                })
            }

            val requestBodyJson = JSONObject().apply {
                put("model", config.apiModel)
                put("messages", messages)
                put("temperature", 0.1)
                // Use JSON mode if supported, otherwise standard text
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }

            val request = Request.Builder()
                .url("${config.apiUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer ${config.apiKey}")
                .post(requestBodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            Log.i(TAG, "Sending request to VLM model: ${config.apiModel} at ${config.apiUrl}")
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP error response from VLM API: ${response.code} ${response.message}")
                    return null
                }

                val responseBody = response.body?.string() ?: return null
                Log.d(TAG, "VLM API Response: $responseBody")

                val jsonResponse = JSONObject(responseBody)
                val choices = jsonResponse.getJSONArray("choices")
                if (choices.length() > 0) {
                    val content = choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
                    
                    // Parse content JSON, discarding any markdown wrappers if present
                    val cleanJson = if (content.startsWith("```json")) {
                        content.removePrefix("```json").removeSuffix("```").trim()
                    } else if (content.startsWith("```")) {
                        content.removePrefix("```").removeSuffix("```").trim()
                    } else {
                        content
                    }

                    val decisionJson = JSONObject(cleanJson)
                    return VlmDecision(
                        action = decisionJson.optString("action", "complete"),
                        selector = if (decisionJson.has("selector") && !decisionJson.isNull("selector")) decisionJson.getString("selector") else null,
                        selectorType = if (decisionJson.has("selector_type") && !decisionJson.isNull("selector_type")) decisionJson.getString("selector_type") else null,
                        text = decisionJson.optString("text", ""),
                        reason = decisionJson.optString("reason", "")
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error coordinating with VLM API", e)
        }
        return null
    }

    private fun encodeImageToBase64(file: File): String? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode image to base64", e)
            null
        }
    }
}
