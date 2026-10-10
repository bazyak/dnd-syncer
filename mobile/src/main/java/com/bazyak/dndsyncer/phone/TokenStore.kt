package com.bazyak.dndsyncer.phone

import android.content.Context
import androidx.core.content.edit
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.Sync
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Токен автозапуска Shizuku.
 *
 * Раньше он зашивался в сборку через gradle.properties, и при каждой
 * переустановке Shizuku приходилось пересобирать APK. Теперь вводится на
 * телефоне и уезжает на часы через Data Layer: DataItem сохраняется у
 * системы, поэтому часы получат его и после перезагрузки, и при первом
 * соединении.
 *
 * Пустая строка — это "удалён": отдельного удаления DataItem не делаем,
 * иначе часы не отличат "токена нет" от "связь не поднялась".
 */
object TokenStore {

    fun get(context: Context): String =
        prefs(context).getString(KEY, "").orEmpty()

    suspend fun set(context: Context, token: String) {
        val value = token.trim()
        prefs(context).edit { putString(KEY, value) }
        publish(context, value)
    }

    suspend fun clear(context: Context) = set(context, "")

    private suspend fun publish(context: Context, token: String) {
        val request = PutDataMapRequest.create(Sync.PATH_TOKEN).apply {
            dataMap.putString(Sync.KEY_TOKEN, token)
            dataMap.putLong(Sync.KEY_TS, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
            .onSuccess {
                FileLog.d(TAG, if (token.isEmpty()) "токен удалён" else "токен отправлен на часы")
            }
            .onFailure { FileLog.w(TAG, "не удалось отправить токен: $it") }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("shizuku_token", Context.MODE_PRIVATE)

    private const val TAG = "TokenStore"
    private const val KEY = "token"
}
