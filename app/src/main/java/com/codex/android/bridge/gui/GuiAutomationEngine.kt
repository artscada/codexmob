package com.codex.android.bridge.gui

import android.content.Context
import android.util.Log
import com.codex.android.runtime.root.SuRootBridge
import com.codex.android.util.AndroidShellExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GuiAutomationEngine private constructor(private val context: Context) {
    companion object {
        private const val TAG = "GuiAutomationEngine"
        
        @Volatile
        private var instance: GuiAutomationEngine? = null

        fun getInstance(context: Context): GuiAutomationEngine {
            return instance ?: synchronized(this) {
                instance ?: GuiAutomationEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val rootBridge = SuRootBridge()
    private val tempDumpPath = "/data/local/tmp/gui_dump.xml"
    private val tempScreenshotPath = "/data/local/tmp/gui_screenshot.png"
    private val hostDumpFile = File("/data/local/tmp/gui_dump.xml") // rootfs shares tmp

    suspend fun executeGuiAction(
        action: String,
        path: String,
        content: String?,
        timeoutMs: Long
    ): AndroidShellExecutor.ShellResult {
        Log.i(TAG, "Executing GUI action: $action, path=$path, content=$content")
        return when (action.lowercase()) {
            "click" -> {
                // If coordinates are directly passed
                val coords = parseCoordinates(path)
                if (coords != null) {
                    rootBridge.click(coords.first, coords.second)
                } else {
                    // It is a selector. Dump hierarchy first
                    dumpAndFind(path, "text")
                }
            }
            "click_by_id" -> dumpAndFind(path, "id")
            "click_by_desc" -> dumpAndFind(path, "desc")
            "swipe" -> {
                val coords = parseSwipeCoordinates(path) ?: return AndroidShellExecutor.ShellResult(-1, "", "invalid swipe coords")
                rootBridge.swipe(coords[0], coords[1], coords[2], coords[3], coords[4])
            }
            "input" -> {
                val text = content ?: return AndroidShellExecutor.ShellResult(-1, "", "text required")
                rootBridge.inputText(text)
            }
            "screenshot" -> rootBridge.takeScreenshot(path.ifBlank { tempScreenshotPath })
            "dump" -> rootBridge.dumpWindowHierarchy(path.ifBlank { tempDumpPath })
            "do_task" -> {
                val prompt = content ?: return AndroidShellExecutor.ShellResult(-1, "", "prompt required")
                executeTaskWorkflow(prompt)
            }
            else -> AndroidShellExecutor.ShellResult(-1, "", "unknown action: $action")
        }
    }

    private suspend fun dumpAndFind(selector: String, type: String): AndroidShellExecutor.ShellResult {
        rootBridge.dumpWindowHierarchy(tempDumpPath)
        // Ensure local file access (we might need to copy it or read it)
        val file = File(tempDumpPath)
        if (!file.exists()) {
            // Fallback: try host path mapping
            Log.w(TAG, "Dump file not found on host, trying fallback paths")
        }
        val coords = XmlLayoutParser.findElementCoordinates(file, selector, type)
        return if (coords != null) {
            rootBridge.click(coords.first, coords.second)
        } else {
            AndroidShellExecutor.ShellResult(-1, "", "Element not found: $selector ($type)")
        }
    }

    private suspend fun executeTaskWorkflow(prompt: String): AndroidShellExecutor.ShellResult {
        Log.i(TAG, "Starting Route-Then-Act workflow for prompt: $prompt")
        
        // 1. Try to find a saved route in the DB
        val routeStr = RouteDatabaseHelper.getInstance(context).findRouteForPrompt(prompt)
        val recordedSteps = ArrayList<JSONObject>()
        
        if (routeStr != null) {
            Log.i(TAG, "Saved route found. Replaying...")
            try {
                val routeJson = JSONArray(routeStr)
                var replaySuccess = true
                for (i in 0 until routeJson.length()) {
                    val step = routeJson.getJSONObject(i)
                    val stepAction = step.optString("action")
                    val selector = step.optString("selector")
                    val selectorType = step.optString("selector_type")
                    val stepText = step.optString("text")
                    
                    Log.i(TAG, "Replaying step ${i + 1}: $stepAction $selector")
                    
                    val result = when (stepAction) {
                        "click" -> dumpAndFind(selector, selectorType)
                        "input" -> rootBridge.inputText(stepText)
                        else -> AndroidShellExecutor.ShellResult(-1, "", "Unsupported replay action")
                    }
                    
                    if (result.exitCode != 0) {
                        Log.w(TAG, "Replay failed at step ${i + 1}. Falling back to VLM Act...")
                        replaySuccess = false
                        break
                    }
                    
                    // Small delay to let screen load
                    Thread.sleep(1500)
                }
                
                if (replaySuccess) {
                    return AndroidShellExecutor.ShellResult(0, "Route replayed successfully", "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Replay failed due to exception. Falling back to VLM...", e)
            }
        }

        // 2. Act: VLM dynamic loop
        Log.i(TAG, "Executing VLM dynamic loop (Act)...")
        var currentStep = 0
        val maxSteps = 10
        var taskCompleted = false
        val executionLog = StringBuilder()

        while (currentStep < maxSteps && !taskCompleted) {
            currentStep++
            Log.i(TAG, "VLM loop step $currentStep")
            
            // Take screenshot and XML dump
            rootBridge.takeScreenshot(tempScreenshotPath)
            rootBridge.dumpWindowHierarchy(tempDumpPath)
            
            // Call local VLM coordinator (which talks to gpt-5.4-mini on device)
            val decision = VlmCoordinator.getInstance(context).getVlmDecision(
                prompt = prompt,
                screenshotFile = File(tempScreenshotPath),
                xmlFile = File(tempDumpPath),
                executionLog = executionLog.toString()
            )
            
            if (decision == null) {
                return AndroidShellExecutor.ShellResult(-1, "", "Failed to get decision from VLM")
            }

            Log.i(TAG, "VLM Decision: action=${decision.action}, selector=${decision.selector}, text=${decision.text}")
            executionLog.append("Step $currentStep: ${decision.action} ${decision.selector ?: ""}\n")

            if (decision.action == "complete") {
                taskCompleted = true
                break
            }

            val stepResult = when (decision.action) {
                "click" -> {
                    val sel = decision.selector ?: ""
                    val selType = decision.selectorType ?: "text"
                    val coords = parseCoordinates(sel)
                    val clickRes = if (coords != null) {
                        rootBridge.click(coords.first, coords.second)
                    } else {
                        dumpAndFind(sel, selType)
                    }
                    if (clickRes.exitCode == 0) {
                        recordedSteps.add(JSONObject().apply {
                            put("action", "click")
                            put("selector", sel)
                            put("selector_type", selType)
                        })
                    }
                    clickRes
                }
                "input" -> {
                    val inputRes = rootBridge.inputText(decision.text)
                    if (inputRes.exitCode == 0) {
                        recordedSteps.add(JSONObject().apply {
                            put("action", "input")
                            put("text", decision.text)
                        })
                    }
                    inputRes
                }
                else -> AndroidShellExecutor.ShellResult(-1, "", "unsupported VLM action")
            }

            if (stepResult.exitCode != 0) {
                return AndroidShellExecutor.ShellResult(-1, "", "Step execution failed: ${stepResult.stderr}")
            }

            Thread.sleep(1500)
        }

        if (taskCompleted) {
            // Save recorded steps as a new route
            if (recordedSteps.isNotEmpty()) {
                val stepsArray = JSONArray()
                recordedSteps.forEach { stepsArray.put(it) }
                RouteDatabaseHelper.getInstance(context).saveRoute(prompt, stepsArray.toString())
                Log.i(TAG, "New route saved successfully for prompt: $prompt")
            }
            return AndroidShellExecutor.ShellResult(0, "Task completed successfully", "")
        }

        return AndroidShellExecutor.ShellResult(-1, "", "Task exceeded maximum steps limit")
    }

    private fun parseCoordinates(coordsStr: String): Pair<Int, Int>? {
        try {
            val parts = coordsStr.split(",")
            if (parts.size == 2) {
                return Pair(parts[0].trim().toInt(), parts[1].trim().toInt())
            }
        } catch (_: Exception) {}
        return null
    }

    private fun parseSwipeCoordinates(coordsStr: String): IntArray? {
        try {
            val parts = coordsStr.split(",")
            if (parts.size >= 4) {
                val coords = IntArray(5)
                coords[0] = parts[0].trim().toInt()
                coords[1] = parts[1].trim().toInt()
                coords[2] = parts[2].trim().toInt()
                coords[3] = parts[3].trim().toInt()
                coords[4] = if (parts.size == 5) parts[4].trim().toInt() else 300
                return coords
            }
        } catch (_: Exception) {}
        return null
    }
}
