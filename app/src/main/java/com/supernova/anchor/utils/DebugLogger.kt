package com.supernova.anchor.utils

// =============================================================================
// FILE: DebugLogger.kt
// =============================================================================
//
// WHAT THIS FILE DOES:
// Custom file-based logger that writes every log line to a text file on
// external storage (SD card or shared storage). This allows diagnosing SMS
// delivery issues without needing adb logcat — the user can open the log
// file directly in a file manager.
//
// CRITICAL FIX: Previously, logFile was set once in init() and never updated.
// If the app ran past midnight, all subsequent logs were written to the
// previous day's filename. For trace services running for days, this meant
// logs were silently lost into a stale file. The fix re-checks the date on
// every write() call and rotates to a new file when the day changes.
//
// TWO OUTPUT PATHS:
//   1. DEFAULT: Auto-detected location (SD card > shared storage > internal)
//      Path example: /sdcard/Android/data/com.supernova.anchor/files/debug/2025-08-25.txt
//   2. CUSTOM: User-picked folder via Storage Access Framework (SAF)
//      The user selects any folder in Settings; logs write there via ContentResolver.
//
// RELATIONSHIP TO OTHER FILES:
// - AppSettings.kt        : Stores DEBUG_FOLDER_TREE_URI for custom folder
// - SmsReceiver.kt        : Calls DebugLogger.init() and DebugLogger.log()
// - DataSmsReceiver.kt    : Same — init() is idempotent (safe to call repeatedly)
// - LocationForegroundService.kt : Logs GPS acquisition progress
// - TraceForegroundService.kt    : Logs trace ticks and scheduling
// - SmsCommandProcessor.kt       : Logs command parsing and execution
// - RingtonePlayer.kt            : Uses Android Log.d(), not this file
//
// PERMISSIONS: No runtime permission needed for app-private external storage
// (Android/data/...). Custom folders require SAF grant (persistent URI permission).
// =============================================================================

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
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
    private var appContext: Context? = null
    // CRITICAL FIX: Track the date of the current logFile so we can detect
    // midnight rollover and create a new file for the new day.
    private var currentLogDate: String = ""

    /** Initializes the logger. Safe to call repeatedly — subsequent calls
     *  are no-ops thanks to the `initialized` flag. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        initialized = true
        val baseDir = resolveLogBaseDir(context)
        val debugDir = File(baseDir, "debug")
        if (!debugDir.exists()) debugDir.mkdirs()
        val today = fileNameFormat.format(Date())
        logFile = File(debugDir, "$today.txt")
        currentLogDate = today
        write("SYSTEM", "Logger started. Path: ${logFile?.absolutePath}")
    }

    /** Picks where the debug/ folder lives, in priority order:
     *   1. Removable SD card (if mounted)
     *   2. Primary shared/external storage
     *   3. Internal app-private storage (fallback)
     */
    private fun resolveLogBaseDir(context: Context): File {
        val volumes = context.getExternalFilesDirs(null)

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

    /** Public entry point for logging. Delegates to write(). */
    fun log(tag: String, message: String) {
        write(tag, message)
    }

    /** Core write method. CRITICAL FIX: Checks for midnight rollover before
     *  every write, so logs never go to a stale file. */
    private fun write(tag: String, message: String) {
        val ts = timeFormat.format(Date())
        val line = "[$ts] [$tag] $message"
        // Always mirror to Android system logcat (visible via adb)
        Log.d(TAG, line)

        // ----------------------------------------------------------------
        // CRITICAL FIX: Midnight rollover check.
        // If the date changed since the last write, update logFile to
        // point at today's file. This ensures trace services that run
        // for days always write to the correct daily log file.
        // ----------------------------------------------------------------
        val today = fileNameFormat.format(Date())
        if (today != currentLogDate) {
            currentLogDate = today
            val baseDir = appContext?.let { resolveLogBaseDir(it) } ?: return
            val debugDir = File(baseDir, "debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            logFile = File(debugDir, "$today.txt")
            // Log the rotation event itself
            Log.d(TAG, "[$ts] [SYSTEM] Log rotated to new file: ${logFile?.absolutePath}")
        }

        // Try custom folder first (if user configured one in Settings)
        val ctx = appContext
        val customUri = if (ctx != null) getCustomDebugFolderUri(ctx) else null
        if (ctx != null && customUri != null) {
            try {
                writeToCustomFolder(ctx, customUri, line + "\n")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Custom debug folder write failed, falling back to default location", e)
            }
        }

        // Write to the default file-based location
        try {
            logFile?.let { f -> FileWriter(f, true).use { it.appendLine(line) } }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    // -----------------------------------------------------------------
    // Custom debug folder (user-picked via Settings -> "Choose debug folder")
    // -----------------------------------------------------------------

    /** Returns the saved custom folder's tree Uri, or null if none is set
     *  or the grant is no longer valid. */
    fun getCustomDebugFolderUri(context: Context): Uri? {
        val stored = AppSettings(context).getString(AppSettings.DEBUG_FOLDER_TREE_URI)
        if (stored.isBlank()) return null
        val uri = Uri.parse(stored)
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (stillGranted) uri else null
    }

    /** Called after the user picks a folder via OpenDocumentTree. */
    fun setCustomDebugFolder(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist permission for custom debug folder", e)
        }
        AppSettings(context).setString(AppSettings.DEBUG_FOLDER_TREE_URI, treeUri.toString())
        write("SYSTEM", "Custom debug folder set: $treeUri")
    }

    /** Reverts to the auto-detected default location. */
    fun clearCustomDebugFolder(context: Context) {
        val uri = getCustomDebugFolderUri(context)
        AppSettings(context).setString(AppSettings.DEBUG_FOLDER_TREE_URI, "")
        if (uri != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
        }
        write("SYSTEM", "Custom debug folder cleared, reverted to default location")
    }

    private fun writeToCustomFolder(context: Context, treeUri: Uri, line: String) {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val fileName = "${fileNameFormat.format(Date())}.txt"

        val existing = findChildDocument(context, treeUri, treeDocId, fileName)
        val targetUri = existing ?: DocumentsContract.createDocument(context.contentResolver, treeDocUri, "text/plain", fileName)
            ?: throw IllegalStateException("Could not create log file in custom folder")

        context.contentResolver.openOutputStream(targetUri, "wa")?.use { out ->
            out.write(line.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Could not open custom debug file for writing")
    }

    private fun findChildDocument(context: Context, treeUri: Uri, treeDocId: String, displayName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (nameIdx >= 0 && cursor.getString(nameIdx) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIdx))
                }
            }
        }
        return null
    }

    /** Most recently modified log document in the custom folder. */
    fun getLatestCustomLogUri(context: Context): Uri? {
        val treeUri = getCustomDebugFolderUri(context) ?: return null
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        var latestUri: Uri? = null
        var latestModified = -1L
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val modified = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                if (modified > latestModified) {
                    latestModified = modified
                    latestUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIdx))
                }
            }
        }
        return latestUri
    }

    // -----------------------------------------------------------------
    // Accessors for Settings UI (path display, open folder, share log)
    // -----------------------------------------------------------------

    /** The default (auto-detected) debug directory. */
    fun getDebugDir(context: Context): File {
        val dir = File(resolveLogBaseDir(context), "debug")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Most recently modified log file in the default debug dir. */
    fun getLatestLogFile(context: Context): File? =
        getDebugDir(context).listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }

    /** Human-readable "where are logs going now" string for Settings UI. */
    fun describeCurrentLocation(context: Context): String {
        val customUri = getCustomDebugFolderUri(context)
        if (customUri != null) {
            return "Custom folder: ${Uri.decode(customUri.toString())}"
        }
        return getDebugDir(context).absolutePath
    }
}
