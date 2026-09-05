package com.bazyak.dndsyncer.wear

import android.accessibilityservice.AccessibilityService
import android.database.ContentObserver
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.bazyak.dndsyncer.core.DndSync
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
 * Часовая сторона. События специальных возможностей не обрабатываются —
 * сервис нужен исключительно как живой процесс для ContentObserver'ов.
 */
class WatchSyncService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sync by lazy { DndSync(this, Sync.PATH_WATCH) }
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    override fun onServiceConnected() {
        Log.d(TAG, "Сервис подключён")
        observer = Zen.observe(this) { schedule("watch") }
        schedule("connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * Смена режима трогает несколько ключей подряд (например, снятие театра
     * гасит theater_mode_on и zen_mode отдельными записями). Без задержки
     * улетает промежуточный снимок, который сосед принимает за команду.
     * Поэтому публикуем только когда настройки устоялись.
     */
    private fun schedule(reason: String) {
        pending?.cancel()
        pending = scope.launch {
            delay(SETTLE_MS)
            val state = Zen.snapshot(contentResolver)
            Log.d(
                TAG,
                "zen=${Zen.zen(contentResolver)} theater=${Zen.theater(contentResolver)} " +
                    "bedtime=${Zen.bedtime(contentResolver)} → $state",
            )
            sync.publish(state, reason)
        }
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WatchSync"
        const val SETTLE_MS = 700L
    }
}
