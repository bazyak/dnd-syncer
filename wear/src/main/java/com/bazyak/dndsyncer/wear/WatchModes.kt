package com.bazyak.dndsyncer.wear

import android.content.Context
import com.bazyak.dndsyncer.core.Dnd
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.ModeState
import com.bazyak.dndsyncer.core.Shell
import com.bazyak.dndsyncer.core.Zen

/**
 * Применение режимов на часах. Общее для команды с телефона и для полной
 * синхронизации после подъёма Shizuku ([PhoneCatchUp]).
 */
object WatchModes {

    private enum class ShellState { READY, STARTED, NONE }

    /**
     * Ночь пишется через WRITE_SECURE_SETTINGS, Shizuku ей не нужен, поэтому
     * идёт первой и не зависит от Wi-Fi и токена. Shell поднимается только
     * для DND и только если без него не обойтись.
     *
     * Synchronized: команда с телефона и догон после подъёма Shizuku могут
     * прийти из разных потоков одновременно. Повторный вход из того же потока
     * (подъём Shizuku внутри setDnd запускает догон) монитор пропускает.
     */
    @Synchronized
    fun apply(context: Context, target: ModeState, applyDnd: Boolean, applyNight: Boolean) {
        val current = Zen.snapshot(context.contentResolver)
        if (applyNight) {
            FileLog.d(TAG, "МЕНЯЮ ночь: ${current.night} → ${target.night}")
            setNight(context, target.night)
        }
        if (applyDnd) {
            FileLog.d(TAG, "МЕНЯЮ dnd: ${current.dnd} → ${target.dnd}")
            setDnd(context, target.dnd)
        }
        FileLog.d(TAG, "после применения: ${Zen.describe(context.contentResolver)}")
    }

    private fun setNight(context: Context, on: Boolean) {
        if (!Zen.canWriteSecure(context)) {
            FileLog.w(TAG, "нет WRITE_SECURE_SETTINGS — ночной режим не применён")
            return
        }
        val ok = Zen.putGlobal(context, Zen.KEY_BEDTIME, if (on) 1 else 0)
        FileLog.d(TAG, "запись bedtime_mode=${if (on) 1 else 0} успех=$ok")
    }

    private fun setDnd(context: Context, on: Boolean) {
        // Сначала снимаем театр, иначе он удержит zen_mode.
        if (!on && Zen.theater(context.contentResolver)) {
            if (Zen.canWriteSecure(context)) {
                val ok = Zen.putGlobal(context, Zen.KEY_THEATER, 0)
                FileLog.d(TAG, "снимаю театр, успех=$ok")
            } else {
                FileLog.w(TAG, "нет WRITE_SECURE_SETTINGS — театр не снят")
            }
        }
        // Тишина уже поднята театром или ночью (или снята вместе с театром) —
        // shell не нужен, Shizuku не будим.
        if (Dnd.isOn(context) == on) {
            FileLog.d(TAG, "zen уже в нужном состоянии, shell не нужен")
            return
        }
        when (ensureShell(context)) {
            ShellState.READY -> Dnd.set(context, on)
            // Подъём Shizuku сам запустил догон телефона по последнему снимку,
            // который не старше этой команды. Если догон оставил dnd как есть,
            // значит часы меняли его позже — и тогда эта команда тоже устарела.
            ShellState.STARTED -> FileLog.d(TAG, "Shizuku поднят, догон с телефона уже отработал")
            ShellState.NONE -> Unit
        }
    }

    /** Shell нужен только для `cmd notification set_dnd`. Нет его — поднимаем Shizuku. */
    private fun ensureShell(context: Context): ShellState {
        if (Shell.isAvailable()) return ShellState.READY
        FileLog.w(TAG, "shell недоступен, пробую поднять Shizuku")
        val result = ShizukuStarter.ensureRunning(context)
        FileLog.d(TAG, "подъём Shizuku: $result")
        return when (result) {
            ShizukuStarter.Result.ALREADY_RUNNING -> {
                Notify.clear(context)
                ShellState.READY
            }
            ShizukuStarter.Result.STARTED -> {
                Notify.clear(context)
                ShellState.STARTED
            }
            ShizukuStarter.Result.NO_WIFI -> {
                Notify.noWifi(context)
                ShellState.NONE
            }
            ShizukuStarter.Result.NO_TOKEN -> {
                FileLog.w(TAG, "токен не задан — DND применить нечем")
                ShellState.NONE
            }
            ShizukuStarter.Result.FAILED -> {
                FileLog.w(TAG, "Shizuku поднять не удалось — DND не применён")
                ShellState.NONE
            }
        }
    }

    private const val TAG = "WatchModes"
}
