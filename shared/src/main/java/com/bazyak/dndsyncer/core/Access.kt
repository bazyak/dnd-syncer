package com.bazyak.dndsyncer.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * Единственный доступ, который выдаётся пальцем: специальные возможности.
 * События не используются — сервис нужен как живой процесс для ContentObserver.
 */
object Access {

    fun isAccessibilityEnabled(context: Context, service: Class<*>): Boolean {
        val expected = "${context.packageName}/${service.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * Возвращает службу в список включённых, если система её выкинула.
     *
     * Принудительная остановка приложения на OxygenOS снимает его службу
     * специальных возможностей, и её приходится включать руками заново.
     * Список включённых служб — обычный ключ Settings.Secure, поэтому при
     * наличии права записи (WRITE_SECURE_SETTINGS на часах, рут на телефоне)
     * восстанавливаем себя сами. Чужие службы в списке не трогаем — только
     * дописываем свою.
     *
     * @param write запись ключа Settings.Secure, своя для каждого устройства
     */
    fun restoreAccessibility(
        context: Context,
        service: Class<*>,
        write: (key: String, value: String) -> Boolean,
    ): Boolean {
        if (isAccessibilityEnabled(context, service)) return true

        val mine = "${context.packageName}/${service.name}"
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val updated = if (current.isBlank()) mine else "$current:$mine"

        val ok = write(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated) &&
            write(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        FileLog.d("Access", "восстановление службы специальных возможностей: успех=$ok")
        return ok
    }

    fun accessibilityIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )

    fun open(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        }
        return false
    }
}
