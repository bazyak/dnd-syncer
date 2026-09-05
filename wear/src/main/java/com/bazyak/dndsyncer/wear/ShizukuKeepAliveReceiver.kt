package com.bazyak.dndsyncer.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar
import kotlin.concurrent.thread

/**
 * Ночная попытка поднять Shizuku: часы почти наверняка на зарядке рядом с
 * домашним Wi-Fi, так что шанс успеха максимальный, а пользователь ничего
 * не замечает. Плюс первая попытка сразу после загрузки.
 */
class ShizukuKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Сработал: ${intent.action}")
        schedule(context)

        val pending = goAsync()
        thread {
            try {
                ShizukuStarter.ensureRunning(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ShizukuKeepAlive"
        private const val ACTION_DAILY = "com.bazyak.dndsyncer.DAILY_SHIZUKU"
        private const val HOUR = 4

        /** Пере-взводится при каждом срабатывании и после загрузки. */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java)
            val intent = Intent(context, ShizukuKeepAliveReceiver::class.java)
                .setAction(ACTION_DAILY)
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            // Неточный будильник намеренно: минута туда-сюда роли не играет,
            // а точный требовал бы SCHEDULE_EXACT_ALARM.
            am.setWindow(
                AlarmManager.RTC_WAKEUP,
                next.timeInMillis,
                60 * 60 * 1000L,
                pending,
            )
            Log.d(TAG, "Следующая попытка: ${next.time}")
        }
    }
}
