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
