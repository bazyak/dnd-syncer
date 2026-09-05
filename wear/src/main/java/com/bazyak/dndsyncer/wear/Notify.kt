package com.bazyak.dndsyncer.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/** Единственное уведомление приложения: когда синхронизация не смогла отработать. */
object Notify {

    private const val CHANNEL = "sync_problems"
    private const val ID_NO_WIFI = 1

    fun noWifi(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Проблемы синхронизации", NotificationManager.IMPORTANCE_HIGH),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Режим не синхронизирован")
            .setContentText("Нет Wi-Fi. Подойди ближе к сети и переключи режим ещё раз.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Нет Wi-Fi, поэтому не удалось поднять Shizuku. " +
                        "Подойди ближе к сети и переключи режим ещё раз.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(ID_NO_WIFI, notification)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_NO_WIFI)
    }
}
