package com.bazyak.dndsyncer.core

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem

/** Разбор входящего снимка от соседа. */
object SyncReceiver {

    fun parse(events: DataEventBuffer, peerPath: String): ModeUpdate? {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != peerPath) continue
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            return ModeUpdate(
                state = ModeState(
                    dnd = map.getBoolean(Sync.KEY_DND),
                    night = map.getBoolean(Sync.KEY_NIGHT),
                ),
                dndChanged = map.getBoolean(Sync.KEY_DND_CHANGED),
                nightChanged = map.getBoolean(Sync.KEY_NIGHT_CHANGED),
                reason = map.getString(Sync.KEY_REASON).orEmpty(),
            )
        }
        return null
    }
}
