package com.bazyak.dndsyncer.core

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Три ключа Settings.Global, из которых собирается состояние часов.
 * Все три читаются без каких-либо разрешений; записываются только
 * с WRITE_SECURE_SETTINGS (выдаётся через adb, см. README).
 *
 * theater_mode_on и bedtime_mode помечены @hide, публичных констант нет —
 * поэтому строки заданы литералами. API самого Settings.Global публичный.
 */
object Zen {
    const val KEY_ZEN = "zen_mode"
    const val KEY_THEATER = "theater_mode_on"
    const val KEY_BEDTIME = "bedtime_mode"

    /**
     * Меняется при любой правке zen-конфига, включая активацию и снятие
     * автоправил. Нужен как триггер: когда ночной режим включается поверх
     * уже поднятого DND, сам zen_mode не меняется и обсервер бы промолчал.
     */
    const val KEY_ETAG = "zen_mode_config_etag"

    fun uris(): List<Uri> = listOf(
        Settings.Global.getUriFor(KEY_ZEN),
        Settings.Global.getUriFor(KEY_THEATER),
        Settings.Global.getUriFor(KEY_BEDTIME),
        Settings.Global.getUriFor(KEY_ETAG),
    )

    fun zen(cr: ContentResolver): Int = Settings.Global.getInt(cr, KEY_ZEN, 0)
    fun theater(cr: ContentResolver): Boolean = Settings.Global.getInt(cr, KEY_THEATER, 0) == 1
    fun bedtime(cr: ContentResolver): Boolean = Settings.Global.getInt(cr, KEY_BEDTIME, 0) == 1

    /**
     * Сборка снимка из трёх флагов OxygenOS Watch.
     *
     * zen_mode — это OR всех источников тишины, сам по себе он не говорит,
     * что именно включено. Источник вычисляется вычитанием:
     *
     *   0,0,0 → ничего
     *   0,1,0 → ручной DND
     *   0,1,1 → театр (± ручной DND, неразличимо и не важно)
     *   1,1,0 → только ночь (ручного DND быть не может: на часах они
     *           взаимоисключающие, а театра нет)
     *   1,1,1 → ночь + театр
     */
    fun snapshot(cr: ContentResolver): ModeState {
        val zen = zen(cr)
        val theater = theater(cr)
        val bedtime = bedtime(cr)
        return ModeState(
            dnd = theater || (zen != 0 && !bedtime),
            night = bedtime,
        )
    }

    fun observe(context: Context, onChange: () -> Unit): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        uris().forEach {
            context.contentResolver.registerContentObserver(it, false, observer)
        }
        return observer
    }

    fun canWriteSecure(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Запись доступна только с WRITE_SECURE_SETTINGS; нужна для театра и ночи на часах. */
    fun putGlobal(context: Context, key: String, value: Int): Boolean = runCatching {
        Settings.Global.putInt(context.contentResolver, key, value)
    }.getOrDefault(false)
}
