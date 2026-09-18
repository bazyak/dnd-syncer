package com.bazyak.dndsyncer.core

/**
 * Контракт обмена. Передаём снимок состояния ПЛЮС отметку о том, какие поля
 * у нас изменились.
 *
 * Отметки обязательны. Без них устаревшее состояние работает как команда:
 * часы всю ночь держат bedtime_mode=1, утром у них дёргается etag без всякого
 * изменения флагов, они публикуют ту же самую единицу — и телефон, только что
 * вышедший из ночи по расписанию, послушно возвращается в неё. Дальше резонанс.
 */
object Sync {
    const val PATH_PHONE = "/dnd/phone"
    const val PATH_WATCH = "/dnd/watch"

    const val KEY_DND = "dnd"
    const val KEY_NIGHT = "night"
    const val KEY_DND_CHANGED = "dnd_changed"
    const val KEY_NIGHT_CHANGED = "night_changed"
    const val KEY_REASON = "reason"
    const val KEY_TS = "ts"
}

/** Нормализованное состояние устройства: только два независимых признака. */
data class ModeState(
    val dnd: Boolean,
    val night: Boolean,
)

/** Что именно сосед изменил у себя — только это и применяем. */
data class ModeUpdate(
    val state: ModeState,
    val dndChanged: Boolean,
    val nightChanged: Boolean,
    val reason: String,
)
