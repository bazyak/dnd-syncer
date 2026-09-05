package com.bazyak.dndsyncer.core

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem

/** Разбор входящего снимка от соседа. */
object SyncReceiver {

    fun parse(events: DataEventBuffer, peerPath: String): Pair<ModeState, String>? {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != peerPath) continue
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val state = ModeState(
                dnd = map.getBoolean(Sync.KEY_DND),
                night = map.getBoolean(Sync.KEY_NIGHT),
            )
            return state to map.getString(Sync.KEY_REASON).orEmpty()
        }
        return null
    }
}
