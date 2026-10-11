package com.bazyak.dndsyncer.core

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * Публикация собственного состояния. Каждая сторона пишет только в свой путь.
 *
 * Помним состояние, которое сосед о нас уже знает, и отмечаем, что
 * изменилось. Если не изменилось ничего — не публикуем вовсе: лишний снимок
 * неизбежно будет принят соседом за команду и развернёт его состояние назад.
 *
 * Сосед знает не только то, что мы опубликовали, но и то, что мы применили
 * по его же команде ([acknowledge]). Без этого применённое уходило обратно
 * эхом, и если сосед за эту секунду успевал переключиться, запоздавшее эхо
 * возвращало его назад: телефон, только что выключивший DND, включал его снова.
 */
class DndSync(private val context: Context, private val myPath: String) {

    private val prefs by lazy {
        context.getSharedPreferences("dnd_sync", Context.MODE_PRIVATE)
    }

    /**
     * Блокирующий вызов, только из фонового потока. Состояние читается
     * под [SyncLock], чтобы не застать посередине применение команды соседа.
     */
    fun publish(reason: String, read: () -> ModeState) {
        SyncLock.withLock<Unit> {
            val state = read()
            val known = lastKnown()
            val dndChanged = known != null && known.dnd != state.dnd
            val nightChanged = known != null && known.night != state.night

            if (known == state) {
                FileLog.d(TAG, "состояние не изменилось ($reason) — не публикую")
                return@withLock
            }

            val request = PutDataMapRequest.create(myPath).apply {
                dataMap.putBoolean(Sync.KEY_DND, state.dnd)
                dataMap.putBoolean(Sync.KEY_NIGHT, state.night)
                dataMap.putBoolean(Sync.KEY_DND_CHANGED, dndChanged)
                dataMap.putBoolean(Sync.KEY_NIGHT_CHANGED, nightChanged)
                dataMap.putString(Sync.KEY_REASON, reason)
                dataMap.putLong(Sync.KEY_TS, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            runCatching {
                Tasks.await(
                    Wearable.getDataClient(context).putDataItem(request),
                    TIMEOUT_S,
                    TimeUnit.SECONDS,
                )
            }.onSuccess {
                remember(state)
                FileLog.d(
                    TAG,
                    "ОТПРАВЛЕНО в $myPath: dnd=${state.dnd}${flag(dndChanged)} " +
                        "night=${state.night}${flag(nightChanged)} причина=$reason",
                )
            }.onFailure { FileLog.w(TAG, "публикация не удалась: $it") }
        }
    }

    /**
     * Команда соседа применена — значит, он это значение уже знает.
     * Записываем его как известное, чтобы следующая публикация не вернула
     * его эхом. Только поля, которые действительно применились: если
     * команда не прошла, сосед должен узнать наше настоящее состояние.
     *
     * Вызывать под [SyncLock], вместе с самим применением.
     */
    fun acknowledge(applied: ModeState, dnd: Boolean, night: Boolean) {
        if (!dnd && !night) return
        // Мы ещё ни разу не публиковались — сосед о нас ничего не знает.
        val known = lastKnown() ?: return
        val updated = ModeState(
            dnd = if (dnd) applied.dnd else known.dnd,
            night = if (night) applied.night else known.night,
        )
        if (updated == known) return
        remember(updated)
        FileLog.d(
            TAG,
            "применено по команде соседа: dnd=${updated.dnd} night=${updated.night} " +
                "— обратно не отправляю",
        )
    }

    private fun flag(changed: Boolean) = if (changed) " (ИЗМЕНЕНО)" else ""

    private fun lastKnown(): ModeState? {
        if (!prefs.contains(KEY_DND)) return null
        return ModeState(prefs.getBoolean(KEY_DND, false), prefs.getBoolean(KEY_NIGHT, false))
    }

    private fun remember(state: ModeState) {
        prefs.edit {
            putBoolean(KEY_DND, state.dnd)
            putBoolean(KEY_NIGHT, state.night)
        }
    }

    private companion object {
        const val TAG = "DndSync"
        const val KEY_DND = "last_dnd"
        const val KEY_NIGHT = "last_night"
        const val TIMEOUT_S = 10L
    }
}
