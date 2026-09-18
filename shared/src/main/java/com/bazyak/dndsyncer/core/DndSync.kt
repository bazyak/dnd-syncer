package com.bazyak.dndsyncer.core

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Публикация собственного состояния. Каждая сторона пишет только в свой путь.
 *
 * Помним последнее опубликованное состояние и отмечаем, что изменилось.
 * Если не изменилось ничего — не публикуем вовсе: лишний снимок неизбежно
 * будет принят соседом за команду и развернёт его состояние назад.
 */
class DndSync(private val context: Context, private val myPath: String) {

    private val prefs by lazy {
        context.getSharedPreferences("dnd_sync", Context.MODE_PRIVATE)
    }

    suspend fun publish(state: ModeState, reason: String) {
        val known = lastPublished()
        val dndChanged = known != null && known.dnd != state.dnd
        val nightChanged = known != null && known.night != state.night

        if (known == state) {
            FileLog.d(TAG, "состояние не изменилось ($reason) — не публикую")
            return
        }

        val request = PutDataMapRequest.create(myPath).apply {
            dataMap.putBoolean(Sync.KEY_DND, state.dnd)
            dataMap.putBoolean(Sync.KEY_NIGHT, state.night)
            dataMap.putBoolean(Sync.KEY_DND_CHANGED, dndChanged)
            dataMap.putBoolean(Sync.KEY_NIGHT_CHANGED, nightChanged)
            dataMap.putString(Sync.KEY_REASON, reason)
            dataMap.putLong(Sync.KEY_TS, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
            .onSuccess {
                remember(state)
                FileLog.d(
                    TAG,
                    "ОТПРАВЛЕНО в $myPath: dnd=${state.dnd}${flag(dndChanged)} " +
                        "night=${state.night}${flag(nightChanged)} причина=$reason",
                )
            }
            .onFailure { FileLog.w(TAG, "публикация не удалась: $it") }
    }

    private fun flag(changed: Boolean) = if (changed) " (ИЗМЕНЕНО)" else ""

    private fun lastPublished(): ModeState? {
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
    }
}
