package com.codex.android.bridge.gui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

data class AutomationTask(
    val id: Int = 0,
    val prompt: String,
    val mode: String, // "scheduled" or "random"
    val timeValue: String, // "08:00" or "12:00-16:00"
    val enabled: Boolean,
    val nextExecution: Long
)

class RouteDatabaseHelper private constructor(context: Context) : 
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "RouteDatabaseHelper"
        private const val DATABASE_NAME = "gui_routes.db"
        private const val DATABASE_VERSION = 2 // Upgraded version for automation tasks table

        private const val TABLE_ROUTES = "routes"
        private const val COL_ID = "id"
        private const val COL_PROMPT = "prompt"
        private const val COL_STEPS = "steps"
        private const val COL_CREATED_AT = "created_at"

        private const val TABLE_TASKS = "automation_tasks"
        private const val COL_TASK_ID = "id"
        private const val COL_TASK_PROMPT = "prompt"
        private const val COL_TASK_MODE = "mode"
        private const val COL_TASK_TIME_VALUE = "time_value"
        private const val COL_TASK_ENABLED = "enabled"
        private const val COL_TASK_NEXT_EXECUTION = "next_execution"

        @Volatile
        private var instance: RouteDatabaseHelper? = null

        fun getInstance(context: Context): RouteDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: RouteDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createRouteTableSql = """
            CREATE TABLE $TABLE_ROUTES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PROMPT TEXT UNIQUE,
                $COL_STEPS TEXT,
                $COL_CREATED_AT INTEGER
            )
        """.trimIndent()
        db.execSQL(createRouteTableSql)

        val createTasksTableSql = """
            CREATE TABLE $TABLE_TASKS (
                $COL_TASK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TASK_PROMPT TEXT,
                $COL_TASK_MODE TEXT,
                $COL_TASK_TIME_VALUE TEXT,
                $COL_TASK_ENABLED INTEGER,
                $COL_TASK_NEXT_EXECUTION INTEGER
            )
        """.trimIndent()
        db.execSQL(createTasksTableSql)
        Log.i(TAG, "Database tables created successfully.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val createTasksTableSql = """
                CREATE TABLE $TABLE_TASKS (
                    $COL_TASK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_TASK_PROMPT TEXT,
                    $COL_TASK_MODE TEXT,
                    $COL_TASK_TIME_VALUE TEXT,
                    $COL_TASK_ENABLED INTEGER,
                    $COL_TASK_NEXT_EXECUTION INTEGER
                )
            """.trimIndent()
            db.execSQL(createTasksTableSql)
            Log.i(TAG, "Upgraded database to version 2, added $TABLE_TASKS table.")
        }
    }

    // --- Route Storage Methods ---
    fun saveRoute(prompt: String, stepsJson: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_PROMPT, prompt.trim())
                put(COL_STEPS, stepsJson)
                put(COL_CREATED_AT, System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_ROUTES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            Log.i(TAG, "Route saved successfully for prompt: $prompt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save route to database", e)
        }
    }

    fun findRouteForPrompt(prompt: String): String? {
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_ROUTES,
                arrayOf(COL_STEPS),
                "$COL_PROMPT = ?",
                arrayOf(prompt.trim()),
                null, null, null
            )
            cursor.use {
                if (it.moveToFirst()) {
                    return it.getString(it.getColumnIndexOrThrow(COL_STEPS))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find route in database", e)
        }
        return null
    }

    fun deleteRoute(prompt: String) {
        try {
            val db = writableDatabase
            db.delete(TABLE_ROUTES, "$COL_PROMPT = ?", arrayOf(prompt.trim()))
            Log.i(TAG, "Route deleted for prompt: $prompt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete route from database", e)
        }
    }

    // --- Automation Tasks (Scheduler) Methods ---
    fun addAutomationTask(task: AutomationTask): Long {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_TASK_PROMPT, task.prompt.trim())
                put(COL_TASK_MODE, task.mode)
                put(COL_TASK_TIME_VALUE, task.timeValue)
                put(COL_TASK_ENABLED, if (task.enabled) 1 else 0)
                put(COL_TASK_NEXT_EXECUTION, task.nextExecution)
            }
            val id = db.insert(TABLE_TASKS, null, values)
            Log.i(TAG, "Automation task added with ID: $id")
            return id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add automation task", e)
        }
        return -1
    }

    fun updateAutomationTask(task: AutomationTask) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_TASK_PROMPT, task.prompt.trim())
                put(COL_TASK_MODE, task.mode)
                put(COL_TASK_TIME_VALUE, task.timeValue)
                put(COL_TASK_ENABLED, if (task.enabled) 1 else 0)
                put(COL_TASK_NEXT_EXECUTION, task.nextExecution)
            }
            db.update(TABLE_TASKS, values, "$COL_TASK_ID = ?", arrayOf(task.id.toString()))
            Log.d(TAG, "Automation task updated: ${task.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update automation task", e)
        }
    }

    fun getAutomationTask(id: Int): AutomationTask? {
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_TASKS,
                null,
                "$COL_TASK_ID = ?",
                arrayOf(id.toString()),
                null, null, null
            )
            cursor.use {
                if (it.moveToFirst()) {
                    return AutomationTask(
                        id = it.getInt(it.getColumnIndexOrThrow(COL_TASK_ID)),
                        prompt = it.getString(it.getColumnIndexOrThrow(COL_TASK_PROMPT)),
                        mode = it.getString(it.getColumnIndexOrThrow(COL_TASK_MODE)),
                        timeValue = it.getString(it.getColumnIndexOrThrow(COL_TASK_TIME_VALUE)),
                        enabled = it.getInt(it.getColumnIndexOrThrow(COL_TASK_ENABLED)) == 1,
                        nextExecution = it.getLong(it.getColumnIndexOrThrow(COL_TASK_NEXT_EXECUTION))
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get automation task by ID: $id", e)
        }
        return null
    }

    fun getAllAutomationTasks(): List<AutomationTask> {
        val tasks = ArrayList<AutomationTask>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_TASKS, null, null, null, null, null, "$COL_TASK_ID DESC")
            cursor.use {
                while (it.moveToNext()) {
                    tasks.add(
                        AutomationTask(
                            id = it.getInt(it.getColumnIndexOrThrow(COL_TASK_ID)),
                            prompt = it.getString(it.getColumnIndexOrThrow(COL_TASK_PROMPT)),
                            mode = it.getString(it.getColumnIndexOrThrow(COL_TASK_MODE)),
                            timeValue = it.getString(it.getColumnIndexOrThrow(COL_TASK_TIME_VALUE)),
                            enabled = it.getInt(it.getColumnIndexOrThrow(COL_TASK_ENABLED)) == 1,
                            nextExecution = it.getLong(it.getColumnIndexOrThrow(COL_TASK_NEXT_EXECUTION))
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all automation tasks", e)
        }
        return tasks
    }

    fun deleteAutomationTask(id: Int) {
        try {
            val db = writableDatabase
            db.delete(TABLE_TASKS, "$COL_TASK_ID = ?", arrayOf(id.toString()))
            Log.i(TAG, "Automation task deleted: $id")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete automation task", e)
        }
    }

    fun toggleAutomationTask(id: Int, enabled: Boolean) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_TASK_ENABLED, if (enabled) 1 else 0)
            }
            db.update(TABLE_TASKS, values, "$COL_TASK_ID = ?", arrayOf(id.toString()))
            Log.i(TAG, "Automation task $id enabled status toggled to: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle automation task status", e)
        }
    }
}
