package com.bazyak.dndsyncer.core

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Публикация собственного снимка состояния. Каждая сторона пишет только в свой путь. */
class DndSync(private val context: Context, private val myPath: String) {

    suspend fun publish(state: ModeState, reason: String) {
        val request = PutDataMapRequest.create(myPath).apply {
            dataMap.putBoolean(Sync.KEY_DND, state.dnd)
            dataMap.putBoolean(Sync.KEY_NIGHT, state.night)
            dataMap.putString(Sync.KEY_REASON, reason)
            dataMap.putLong(Sync.KEY_TS, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
            .onSuccess { Log.d(TAG, "→ dnd=${state.dnd} night=${state.night} ($reason)") }
            .onFailure { Log.w(TAG, "Публикация не удалась", it) }
    }

    private companion object {
        const val TAG = "DndSync"
    }
}
