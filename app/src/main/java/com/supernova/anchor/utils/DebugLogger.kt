package com.supernova.anchor.utils

import android.content.Context
import android.os.Environment
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
        val baseDir = resolveLogBaseDir(context)
        val debugDir = File(baseDir, "debug")
        if (!debugDir.exists()) debugDir.mkdirs()
        logFile = File(debugDir, "${fileNameFormat.format(Date())}.txt")
        write("SYSTEM", "Logger started. Path: ${logFile?.absolutePath}")
    }

    /**
     * Picks where the debug/ folder actually lives, in priority order:
     *   1. A genuine removable SD card, if the device has one and it's mounted.
     *   2. Primary shared/external storage (what a file manager shows under
     *      Android/data/<pkg>/files) — this is what getExternalFilesDir(null)
     *      alone gives you, and on phones with no physical SD card this IS
     *      the "external" storage, just not removable media.
     *   3. Internal app-private storage (filesDir) — only if neither above is
     *      available (e.g. storage not mounted yet at boot). Invisible
     *      without root or the app itself; this is the case that looked like
     *      files were landing in "the local programs directory".
     *
     * No WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permission is
     * needed for any of these — app-specific directories under external
     * storage are exempt from scoped storage restrictions on every API level
     * this app targets (minSdk 24 through targetSdk 35).
     */
    private fun resolveLogBaseDir(context: Context): File {
        val volumes = context.getExternalFilesDirs(null) // index 0 = primary; rest = other volumes, if any

        val removableSdCard = volumes.drop(1).firstOrNull { dir ->
            dir != null &&
                Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED &&
                Environment.isExternalStorageRemovable(dir)
        }
        if (removableSdCard != null) return removableSdCard

        val primary = volumes.getOrNull(0)
        if (primary != null && Environment.getExternalStorageState(primary) == Environment.MEDIA_MOUNTED) {
            return primary
        }

        return context.filesDir
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