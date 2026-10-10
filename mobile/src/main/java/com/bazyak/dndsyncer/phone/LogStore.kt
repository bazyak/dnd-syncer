package com.bazyak.dndsyncer.phone

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.bazyak.dndsyncer.core.LogSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * Файл лога с ротацией как в Linux: dnd-syncer-phone.log, .log.1, .log.2, .log.3.
 *
 * Два места:
 *   по умолчанию — Download/DND syncer/, обычными файлами. С Android 11
 *     приложение без разрешений создаёт в Download/ свои файлы, и имя
 *     остаётся ровно таким, как задано. MediaStore здесь не годится: при
 *     MIME text/plain он дописывает к имени .txt, потом по исходному имени
 *     свой же файл не находит и на каждом запуске плодит "(1).txt", "(2).txt"…
 *   папка, выбранная пользователем, — через SAF с постоянным доступом.
 *     MIME application/octet-stream, чтобы провайдер не менял имя.
 *
 * Чужой файл с тем же именем (в том числе оставшийся от прежней установки
 * приложения) система открыть не даёт. Тогда запись не идёт, а причина
 * лежит в [problem] — её показывает экран.
 */
class LogStore(private val context: Context) {

    private val _problem = MutableStateFlow<String?>(null)

    /** Последняя ошибка записи; null, если пишется нормально. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    // SAF: поиск файла в дереве медленный, поэтому Uri и размер запоминаем.
    private var tree: Uri? = null
    private var treeFile: Uri? = null
    private var treeWritten = 0L

    @Synchronized
    fun append(line: String) {
        val bytes = line.toByteArray()
        val folder = LogSettings.folder(context)
        runCatching {
            if (folder != null) appendToTree(folder, bytes) else appendToDownloads(bytes)
        }.onSuccess {
            _problem.value = null
        }.onFailure {
            treeFile = null
            val text = it.message ?: it.javaClass.simpleName
            // В logcat — только новую ошибку, а не каждую строку.
            if (text != _problem.value) Log.w(TAG, "не удалось записать лог", it)
            _problem.value = text
        }
    }

    /** Текущий путь для показа на экране. */
    fun describe(): String {
        val folder = LogSettings.folder(context) ?: return "$DEFAULT_DIR/$NAME"
        val name = DocumentFile.fromTreeUri(context, folder)?.name ?: folder.lastPathSegment
        return "$name/$NAME"
    }

    // --- по умолчанию: Download/DND syncer ---

    private fun appendToDownloads(bytes: ByteArray) {
        val root = context.getSystemService(StorageManager::class.java)
            .primaryStorageVolume.directory
            ?: error(context.getString(R.string.log_err_no_storage))
        val dir = File(root, DEFAULT_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) error(context.getString(R.string.log_err_mkdir, dir.path))

        val file = File(dir, NAME)
        if (file.length() + bytes.size > MAX_BYTES) rotateFiles(dir)
        FileOutputStream(file, true).use { it.write(bytes) }
    }

    /**
     * Сдвигаем с конца: .2 → .3, .1 → .2, текущий → .1. Самый старый удаляется.
     * Обратный порядок обязателен, иначе затрём ещё не сдвинутый файл.
     */
    private fun rotateFiles(dir: File) {
        File(dir, "$NAME.$KEEP").delete()
        for (i in KEEP - 1 downTo 1) {
            File(dir, "$NAME.$i").renameTo(File(dir, "$NAME.${i + 1}"))
        }
        check(File(dir, NAME).renameTo(File(dir, "$NAME.1"))) {
            context.getString(R.string.log_err_rename, NAME, "$NAME.1")
        }
    }

    // --- SAF: папка, выбранная пользователем ---

    private fun appendToTree(folder: Uri, bytes: ByteArray) {
        if (folder != tree) {
            // Папку сменили — старый Uri больше не наш.
            tree = folder
            treeFile = null
        }
        var uri = treeFile ?: openInTree(folder)
        if (treeWritten + bytes.size > MAX_BYTES) {
            rotateInTree(folder)
            uri = openInTree(folder)
        }
        val stream = context.contentResolver.openOutputStream(uri, "wa")
            ?: error(context.getString(R.string.log_err_open, NAME))
        stream.use { it.write(bytes) }
        treeWritten += bytes.size
    }

    /** Находит или создаёт файл и запоминает его вместе с текущим размером. */
    private fun openInTree(folder: Uri): Uri {
        val dir = DocumentFile.fromTreeUri(context, folder) ?: error(context.getString(R.string.log_err_folder))
        val file = dir.findFile(NAME)
            ?: dir.createFile(MIME, NAME)
            ?: error(context.getString(R.string.log_err_create, NAME))
        treeFile = file.uri
        treeWritten = file.length()
        return file.uri
    }

    private fun rotateInTree(folder: Uri) {
        val dir = DocumentFile.fromTreeUri(context, folder) ?: error(context.getString(R.string.log_err_folder))
        dir.findFile("$NAME.$KEEP")?.delete()
        for (i in KEEP - 1 downTo 1) {
            dir.findFile("$NAME.$i")?.renameTo("$NAME.${i + 1}")
        }
        dir.findFile(NAME)?.renameTo("$NAME.1")
    }

    companion object {
        const val NAME = "dnd-syncer-phone.log"

        // Не const: Environment.DIRECTORY_DOWNLOADS — статическое поле Java,
        // а не константа времени компиляции.
        val DEFAULT_DIR = "${Environment.DIRECTORY_DOWNLOADS}/DND syncer"

        private const val TAG = "LogStore"
        private const val MIME = "application/octet-stream"
        private const val KEEP = 3
        private const val MAX_BYTES = 4L * 1024 * 1024
    }
}
