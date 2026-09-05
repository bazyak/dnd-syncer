package com.bazyak.dndsyncer.core

/**
 * Контракт обмена. Передаём СНИМОК состояния целиком, а не дельту:
 * принимающая сторона сама сравнивает с текущим и решает, что менять.
 */
object Sync {
    const val PATH_PHONE = "/dnd/phone"
    const val PATH_WATCH = "/dnd/watch"

    const val KEY_DND = "dnd"
    const val KEY_NIGHT = "night"
    const val KEY_REASON = "reason"
    const val KEY_TS = "ts"
}

/** Нормализованное состояние устройства: только два независимых признака. */
data class ModeState(
    val dnd: Boolean,
    val night: Boolean,
)
