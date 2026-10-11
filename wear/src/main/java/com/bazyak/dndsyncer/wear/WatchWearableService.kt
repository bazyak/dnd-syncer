package com.bazyak.dndsyncer.wear

import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.Sync
import com.bazyak.dndsyncer.core.SyncReceiver
import com.bazyak.dndsyncer.core.Zen
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Применяет на часах снимок с телефона.
 *
 * Применённое обратно не отправляется: телефон это значение уже знает
 * (см. DndSync.acknowledge), поэтому эха нет.
 */
class WatchWearableService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Буфер живёт только внутри колбэка, а пройти по нему надо дважды:
        // отдельно за токеном и отдельно за состоянием.
        val events = dataEvents.map { it.freeze() }
        receiveToken(events)

        val update = SyncReceiver.parse(events, Sync.PATH_PHONE) ?: run {
            FileLog.d(TAG, "событие не про нас, пропускаю")
            return
        }
        val incoming = update.state
        val current = Zen.snapshot(contentResolver)
        FileLog.d(
            TAG,
            "ПОЛУЧЕНО с телефона: dnd=${incoming.dnd}${mark(update.dndChanged)} " +
                "night=${incoming.night}${mark(update.nightChanged)} " +
                "причина=${update.reason} | у себя: dnd=${current.dnd} night=${current.night} | " +
                Zen.describe(contentResolver),
        )

        // Применяем только то, что телефон у себя ИЗМЕНИЛ. Остальные поля в
        // снимке — просто его текущее состояние, а не указание что-то делать.
        val applyDnd = update.dndChanged && incoming.dnd != current.dnd
        val applyNight = update.nightChanged && incoming.night != current.night
        if (!applyDnd && !applyNight) {
            FileLog.d(TAG, "менять нечего")
            return
        }

        WatchModes.apply(this, incoming, applyDnd = applyDnd, applyNight = applyNight)
    }

    /**
     * Токен приходит с телефона отдельным DataItem. Сохраняем и сразу пробуем
     * поднять Shizuku: ждать следующего повода незачем. Нет Wi-Fi — встанет
     * ожидание сети. Токен удалён — та же функция снимет ожидание.
     */
    private fun receiveToken(events: List<DataEvent>) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            if (event.dataItem.uri.path != Sync.PATH_TOKEN) return@forEach

            val token = DataMapItem.fromDataItem(event.dataItem)
                .dataMap.getString(Sync.KEY_TOKEN).orEmpty()
            ShizukuToken.set(this, token)
            FileLog.d(TAG, if (token.isEmpty()) "токен удалён с телефона" else "токен получен")

            val result = ShizukuStarter.ensureRunning(this)
            FileLog.d(TAG, "подъём Shizuku после смены токена: $result")
            if (result == ShizukuStarter.Result.STARTED) Notify.clear(this)
        }
    }

    private fun mark(changed: Boolean) = if (changed) " (ИЗМЕНЕНО)" else ""

    private companion object {
        const val TAG = "WatchWearable"
    }
}
