package com.bazyak.dndsyncer.phone

import android.util.Log
import com.bazyak.dndsyncer.core.Dnd
import com.bazyak.dndsyncer.core.Sync
import com.bazyak.dndsyncer.core.SyncReceiver
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/**
 * Применяет на телефоне снимок с часов. DND и ночь ставятся независимо:
 * на телефоне эти режимы сосуществуют, в отличие от часов.
 */
class PhoneWearableService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val (incoming, reason) = SyncReceiver.parse(dataEvents, Sync.PATH_WATCH) ?: return
        val current = PhoneSyncService.snapshot(this)
        if (incoming == current) {
            Log.d(TAG, "← $incoming ($reason) — совпадает, ничего не делаю")
            return
        }

        Log.d(TAG, "← dnd=${incoming.dnd} night=${incoming.night} ($reason), было $current")

        if (incoming.night != current.night) PhoneNight.setOn(this, incoming.night)
        if (incoming.dnd != current.dnd) {
            Dnd.set(this, incoming.dnd)
        }
    }

    private companion object {
        const val TAG = "PhoneWearable"
    }
}
