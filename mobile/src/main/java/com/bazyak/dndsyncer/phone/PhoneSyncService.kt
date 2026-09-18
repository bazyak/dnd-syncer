package com.bazyak.dndsyncer.phone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.database.ContentObserver
import android.view.accessibility.AccessibilityEvent
import com.bazyak.dndsyncer.core.DndSync
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.ModeState
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
 * Телефонная сторона. DND читается из dumpsys, а триггером служат ключи
 * Settings.Global — zen_mode меняется при любом источнике тишины, а etag
 * при любой правке zen-конфига, включая активацию автоправил.
 */
class PhoneSyncService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sync by lazy { DndSync(this, Sync.PATH_PHONE) }
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    override fun onServiceConnected() {
        FileLog.d(TAG, "СЕРВИС ПОДКЛЮЧЁН. ${Zen.describe(contentResolver)}")
        FileLog.d(TAG, "shell=${Shell.backend()}")
        observer = Zen.observe(this) { key -> schedule("phone:$key") }
        schedule("connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun schedule(reason: String) {
        FileLog.d(TAG, "СИГНАЛ [$reason] ${Zen.describe(contentResolver)}")
        val had = pending?.isActive == true
        pending?.cancel()
        if (had) FileLog.d(TAG, "предыдущая отложенная публикация отменена")

        pending = scope.launch {
            delay(SETTLE_MS)
            // verbose: в момент публикации нужен полный расклад правил,
            // иначе потом не понять, кто поменял состояние.
            val dump = ZenDump.read(verbose = true)
            val state = if (dump != null) {
                ModeState(dnd = dump.dnd, night = dump.night)
            } else {
                ModeState(dnd = Zen.zen(contentResolver) != 0, night = false)
            }
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PhoneSync"
        private const val SETTLE_MS = 700L

        fun snapshot(context: Context): ModeState {
            ZenDump.read()?.let { return ModeState(dnd = it.dnd, night = it.night) }
            return ModeState(dnd = Zen.zen(context.contentResolver) != 0, night = false)
        }
    }
}
