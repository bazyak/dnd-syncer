package com.bazyak.dndsyncer.phone

import android.accessibilityservice.AccessibilityService
import android.database.ContentObserver
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.bazyak.dndsyncer.core.DndSync
import com.bazyak.dndsyncer.core.ModeState
import com.bazyak.dndsyncer.core.Sync
import com.bazyak.dndsyncer.core.Zen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Телефонная сторона. DND читается из Settings.Global["zen_mode"] — он меняется
 * при любом источнике тишины, включая срабатывание расписания, поэтому
 * NotificationListenerService (и чтение уведомлений) не нужен.
 */
class PhoneSyncService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sync by lazy { DndSync(this, Sync.PATH_PHONE) }
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    override fun onServiceConnected() {
        Log.d(TAG, "Сервис подключён")
        observer = Zen.observe(this) { schedule("phone") }
        schedule("connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun schedule(reason: String) {
        pending?.cancel()
        pending = scope.launch {
            delay(SETTLE_MS)
            val state = snapshot(this@PhoneSyncService)
            Log.d(TAG, "zen=${Zen.zen(contentResolver)} → $state")
            sync.publish(state, reason)
        }
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PhoneSync"
        private const val SETTLE_MS = 700L

        /**
         * Состояние берём из дампа: zen_mode один на оба режима и различить
         * ночь от DND по нему нельзя. Если привилегированного доступа нет,
         * падаем на zen_mode — тогда ночь неотличима, но DND хотя бы работает.
         */
        fun snapshot(context: android.content.Context): ModeState {
            ZenDump.read()?.let {
                return ModeState(dnd = it.dnd, night = it.night)
            }
            return ModeState(dnd = Zen.zen(context.contentResolver) != 0, night = false)
        }
    }
}
