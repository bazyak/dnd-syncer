package com.bazyak.dndsyncer.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Токен автозапуска Shizuku, присланный с телефона.
 *
 * Пока токена нет, приложение не пытается поднимать Shizuku вообще: ни по
 * команде, ни по расписанию, ни по появлению Wi-Fi. Без токена intent всё
 * равно будет отвергнут, а лишние попытки только жгут батарею и засоряют лог.
 */
object ShizukuToken {

    fun get(context: Context): String =
        prefs(context).getString(KEY, "").orEmpty()

    fun isPresent(context: Context): Boolean = get(context).isNotEmpty()

    fun set(context: Context, token: String) {
        prefs(context).edit { putString(KEY, token.trim()) }
    }

    /**
     * Подписка на появление и удаление токена.
     *
     * Токен приходит с телефона в другом компоненте того же процесса, и экран
     * сам по себе об этом не узнает. Возвращает функцию отписки.
     */
    fun observe(context: Context, onChange: () -> Unit): () -> Unit {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("shizuku_token", Context.MODE_PRIVATE)

    private const val KEY = "token"
}
