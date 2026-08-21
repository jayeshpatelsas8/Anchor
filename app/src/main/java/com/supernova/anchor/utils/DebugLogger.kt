package com.supernova.anchor.utils

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

    fun init(context: Context) {
        appContext = context.applicationContext
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
     *      available (e.g. storage not mounted yet at boot).
     *
     * This is the fallback path used when no custom folder (see below) is
     * configured, or when writing to a configured custom folder fails.
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

    fun log(tag: String, message: String) {
        write(tag, message)
    }

    private fun write(tag: String, message: String) {
        val ts = timeFormat.format(Date())
        val line = "[$ts] [$tag] $message"
        Log.d(TAG, line)

        val ctx = appContext
        val customUri = if (ctx != null) getCustomDebugFolderUri(ctx) else null
        if (ctx != null && customUri != null) {
            try {
                writeToCustomFolder(ctx, customUri, line + "\n")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Custom debug folder write failed, falling back to default location", e)
                // falls through to the default file-based write below
            }
        }

        try {
            logFile?.let { f -> FileWriter(f, true).use { it.appendLine(line) } }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    // ---------------------------------------------------------------------
    // Custom debug folder (user-picked via Settings -> "Choose debug folder")
    // ---------------------------------------------------------------------
    // Uses the platform's Storage Access Framework directly through
    // DocumentsContract (no extra androidx.documentfile dependency needed).
    // A user-granted folder can be anywhere: internal shared storage, a real
    // SD card, or (on API 29+) even another app's exposed storage — the tree
    // URI carries its own permanent grant, independent of DebugLogger's own
    // auto-detection logic above.

    /** Returns the saved custom folder's tree Uri, or null if none is set or the grant is no longer valid. */
    fun getCustomDebugFolderUri(context: Context): Uri? {
        val stored = AppSettings(context).getString(AppSettings.DEBUG_FOLDER_TREE_URI)
        if (stored.isBlank()) return null
        val uri = Uri.parse(stored)
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (stillGranted) uri else null
    }

    /** Called after the user picks a folder via ActivityResultContracts.OpenDocumentTree(). */
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

    /** Reverts to the auto-detected default location (SD card / shared storage / internal). */
    fun clearCustomDebugFolder(context: Context) {
        val uri = getCustomDebugFolderUri(context)
        AppSettings(context).setString(AppSettings.DEBUG_FOLDER_TREE_URI, "")
        if (uri != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { /* already released or never held — fine either way */ }
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

    /** Most recently modified log document in the custom folder, if one is set and has entries. */
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

    // ---------------------------------------------------------------------
    // Accessors for Settings (path display, open folder, share log)
    // ---------------------------------------------------------------------

    /** The default (auto-detected) debug directory — used only when no custom folder is set. */
    fun getDebugDir(context: Context): File {
        val dir = File(resolveLogBaseDir(context), "debug")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Most recently modified log file in the default debug dir, if any exist yet. */
    fun getLatestLogFile(context: Context): File? =
        getDebugDir(context).listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }

    /** Human-readable "where are logs going right now" string for Settings — one line, copyable. */
    fun describeCurrentLocation(context: Context): String {
        val customUri = getCustomDebugFolderUri(context)
        if (customUri != null) {
            return "Custom folder: ${Uri.decode(customUri.toString())}"
        }
        return getDebugDir(context).absolutePath
    }
}