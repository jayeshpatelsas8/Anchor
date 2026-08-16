package com.supernova.anchor.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val TAG = "AnchorDebug"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var logFile: File? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val debugDir = File(baseDir, "debug")
        if (!debugDir.exists()) debugDir.mkdirs()
        logFile = File(debugDir, "${fileNameFormat.format(Date())}.txt")
        write("SYSTEM", "Logger started. Path: ${logFile?.absolutePath}")
    }

    fun log(tag: String, message: String) {
        write(tag, message)
    }

    private fun write(tag: String, message: String) {
        val ts = timeFormat.format(Date())
        val line = "[$ts] [$tag] $message"
        Log.d(TAG, line)
        try {
            logFile?.let { f -> FileWriter(f, true).use { it.appendLine(line) } }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }
}
