package com.bazyak.dndsyncer.phone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.bazyak.dndsyncer.core.LogSettings

/**
 * Файл лога с ротацией по образцу Linux: dnd-syncer-phone.log, затем .1, .2, .3.
 *
 * Два источника пути:
 *   по умолчанию — Downloads/DND syncer/ через MediaStore, разрешений не нужно;
 *   выбранная пользователем папка — через SAF, с постоянным доступом.
 */
class LogStore(private val context: Context) {

    private var written = 0L
    private var cached: Uri? = null

    @Synchronized
    fun append(line: String) {
        val bytes = line.toByteArray()

        // Размер считаем сами, а не спрашиваем систему на каждой строке:
        // одна запись может быть и в сотню байт, и в килобайт.
        if (written + bytes.size > MAX_BYTES) {
            rotate()
            written = 0
            cached = null
        }

        val uri = cached ?: open().also { cached = it } ?: return
        runCatching {
            context.contentResolver.openOutputStream(uri, "wa")?.use { it.write(bytes) }
            written += bytes.size
        }.onFailure {
            Log.w(TAG, "не удалось дописать лог", it)
            cached = null
        }
    }

    /** Текущий путь для показа на экране. */
    fun describe(): String {
        val folder = LogSettings.folder(context)
        return if (folder != null) {
            "${DocumentFile.fromTreeUri(context, folder)?.name ?: folder.lastPathSegment}/$NAME"
        } else {
            "$DEFAULT_DIR/$NAME"
        }
    }

    // --- общее ---

    private fun open(): Uri? {
        val uri = LogSettings.folder(context)?.let(::openInTree) ?: openInDownloads()
        if (uri != null) written = size(uri)
        return uri
    }

    private fun size(uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L)

    /**
     * Сдвигаем: .2 → .3, .1 → .2, текущий → .1. Самый старый удаляется.
     * Порядок с конца обязателен, иначе затрём ещё не сдвинутый файл.
     */
    private fun rotate() {
        val folder = LogSettings.folder(context)
        if (folder != null) rotateInTree(folder) else rotateInDownloads()
    }

    // --- SAF: папка, выбранная пользователем ---

    private fun openInTree(tree: Uri): Uri? {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: return null
        val existing = dir.findFile(NAME)
        return (existing ?: dir.createFile("text/plain", NAME))?.uri
    }

    private fun rotateInTree(tree: Uri) {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: return
        dir.findFile("$NAME.$KEEP")?.delete()
        for (i in KEEP - 1 downTo 1) {
            dir.findFile("$NAME.$i")?.renameTo("$NAME.${i + 1}")
        }
        dir.findFile(NAME)?.renameTo("$NAME.1")
    }

    // --- MediaStore: Downloads/DND syncer ---

    private fun openInDownloads(): Uri? {
        find(NAME)?.let { return it }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, DEFAULT_DIR)
        }
        return context.contentResolver.insert(COLLECTION, values)
    }

    private fun rotateInDownloads() {
        find("$NAME.$KEEP")?.let { context.contentResolver.delete(it, null, null) }
        for (i in KEEP - 1 downTo 1) {
            rename("$NAME.$i", "$NAME.${i + 1}")
        }
        rename(NAME, "$NAME.1")
    }

    private fun rename(from: String, to: String) {
        val uri = find(from) ?: return
        runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, to) },
                null,
                null,
            )
        }.onFailure { Log.w(TAG, "не удалось переименовать $from → $to", it) }
    }

    private fun find(name: String): Uri? {
        context.contentResolver.query(
            COLLECTION,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "$DEFAULT_DIR%"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(COLLECTION, cursor.getLong(0).toString())
            }
        }
        return null
    }

    companion object {
        const val NAME = "dnd-syncer-phone.log"
        // Не const: Environment.DIRECTORY_DOWNLOADS — статическое поле Java,
        // а не константа времени компиляции.
        val DEFAULT_DIR = "${Environment.DIRECTORY_DOWNLOADS}/DND syncer"
        private const val TAG = "LogStore"
        private const val KEEP = 3
        private const val MAX_BYTES = 4L * 1024 * 1024
        private val COLLECTION: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }
}
