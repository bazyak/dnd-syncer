package com.bazyak.dndsyncer.core

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** Настройки логирования: включён ли и куда писать. */
object LogSettings {

    private fun prefs(context: Context) =
        context.getSharedPreferences("log_settings", Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        FileLog.enabled = enabled
    }

    /** Папка, выбранная пользователем через системный диалог, либо null. */
    fun folder(context: Context): Uri? =
        prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    fun setFolder(context: Context, uri: Uri?) {
        prefs(context).edit {
            if (uri == null) remove(KEY_FOLDER) else putString(KEY_FOLDER, uri.toString())
        }
    }

    private const val KEY_ENABLED = "enabled"
    private const val KEY_FOLDER = "folder"
}
