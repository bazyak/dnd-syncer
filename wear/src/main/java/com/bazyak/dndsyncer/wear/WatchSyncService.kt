package com.bazyak.dndsyncer.wear

import android.accessibilityservice.AccessibilityService
import android.database.ContentObserver
import android.view.accessibility.AccessibilityEvent
import com.bazyak.dndsyncer.core.DndSync
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.Shell
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
        FileLog.d(TAG, "СЕРВИС ПОДКЛЮЧЁН. ${Zen.describe(contentResolver)}")
        FileLog.d(TAG, "shell=${Shell.backend()} secure=${Zen.canWriteSecure(this)}")
        observer = Zen.observe(this) { key -> schedule("watch:$key") }

        // Wi-Fi на часах сам не поднимается, а без него не встаёт Shizuku.
        // Ставим ожидание сети один раз — дальше цепочка доиграется сама,
        // хоть после перезагрузки, хоть после возвращения домой.
        // ensureRunning ждёт отклика Shizuku секундами — только в фоне,
        // onServiceConnected вызывается на главном потоке.
        scope.launch {
            if (!Shell.isAvailable()) {
                FileLog.d(TAG, "shell недоступен при старте")
                ShizukuStarter.ensureRunning(this@WatchSyncService)
            } else {
                ShizukuStarter.awaitWifi(this@WatchSyncService)
            }
        }

        schedule("connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * Смена режима трогает несколько ключей подряд (снятие театра гасит
     * theater_mode_on и zen_mode отдельными записями). Без задержки улетает
     * промежуточный снимок, который сосед принимает за команду.
     */
    private fun schedule(reason: String) {
        FileLog.d(TAG, "СИГНАЛ [$reason] ${Zen.describe(contentResolver)}")
        val had = pending?.isActive == true
        pending?.cancel()
        if (had) FileLog.d(TAG, "предыдущая отложенная публикация отменена")

        pending = scope.launch {
            delay(SETTLE_MS)
            val state = Zen.snapshot(contentResolver)
            FileLog.d(
                TAG,
                "ПУБЛИКУЮ [$reason] ${Zen.describe(contentResolver)} → " +
                    "dnd=${state.dnd} night=${state.night}",
            )
            sync.publish(state, reason)
        }
    }

    override fun onDestroy() {
        FileLog.w(TAG, "СЕРВИС ОСТАНОВЛЕН")
        observer?.let { contentResolver.unregisterContentObserver(it) }
        ShizukuStarter.stopAwaiting(this)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WatchSync"
        const val SETTLE_MS = 700L
    }
}
