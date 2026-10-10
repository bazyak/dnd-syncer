package com.bazyak.dndsyncer.wear

import android.content.Context
import android.net.Uri
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.ModeState
import com.bazyak.dndsyncer.core.Sync
import com.bazyak.dndsyncer.core.Zen
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * Полная синхронизация с телефоном после подъёма Shizuku.
 *
 * Пока Shizuku лежал, DND с телефона применить было нечем, а режим на
 * телефоне за это время мог смениться сколько угодно раз. Поэтому догоняем
 * не пропущенные команды, а текущее состояние телефона: его последний снимок
 * уже лежит на часах в Data Layer.
 *
 * Исключение — признак, который часы сами поменяли ПОЗЖЕ этого снимка
 * (их последняя публикация свежее и отмечает этот признак изменённым).
 * Телефон такого изменения ещё не видел; выставив его старое значение, мы
 * затёрли бы свежее действие на часах. Ответ телефона придёт обычным путём.
 */
object PhoneCatchUp {

    private data class Snapshot(
        val state: ModeState,
        val dndChanged: Boolean,
        val nightChanged: Boolean,
        val ts: Long,
    )

    /** Блокирующий вызов: только из фонового потока. */
    fun run(context: Context) {
        val (phone, own) = runCatching {
            read(context, Sync.PATH_PHONE) to read(context, Sync.PATH_WATCH)
        }.getOrElse {
            FileLog.w(TAG, "не удалось прочитать снимки из Data Layer: $it")
            return
        }
        if (phone == null) {
            FileLog.d(TAG, "снимка телефона ещё нет — догонять нечего")
            return
        }
        val current = Zen.snapshot(context.contentResolver)
        FileLog.d(
            TAG,
            "ДОГОНЯЮ телефон: dnd=${phone.state.dnd} night=${phone.state.night} ts=${phone.ts} | " +
                "своя публикация: ${own?.let { "ts=${it.ts} dnd_changed=${it.dndChanged} night_changed=${it.nightChanged}" } ?: "нет"} | " +
                "у себя: dnd=${current.dnd} night=${current.night}",
        )

        val newerOwn = own?.takeIf { it.ts > phone.ts }
        val keepDnd = newerOwn?.dndChanged == true
        val keepNight = newerOwn?.nightChanged == true
        if (keepDnd) FileLog.d(TAG, "dnd часы меняли позже снимка телефона — не трогаю")
        if (keepNight) FileLog.d(TAG, "ночь часы меняли позже снимка телефона — не трогаю")

        val applyDnd = !keepDnd && phone.state.dnd != current.dnd
        val applyNight = !keepNight && phone.state.night != current.night
        if (!applyDnd && !applyNight) {
            FileLog.d(TAG, "менять нечего")
            return
        }
        WatchModes.apply(context, phone.state, applyDnd = applyDnd, applyNight = applyNight)
    }

    /** Самый свежий снимок по пути, null — снимка нет. Блокирует поток. */
    private fun read(context: Context, path: String): Snapshot? {
        // URI без узла: снимки этого пути от всех узлов. Пишет в путь одно
        // устройство, но на всякий случай берём самый свежий по отметке.
        val uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .path(path)
            .build()
        val buffer = Tasks.await(
            Wearable.getDataClient(context).getDataItems(uri),
            TIMEOUT_S,
            TimeUnit.SECONDS,
        )
        try {
            return buffer.map { DataMapItem.fromDataItem(it).dataMap }
                .maxByOrNull { it.getLong(Sync.KEY_TS) }
                ?.let {
                    Snapshot(
                        state = ModeState(
                            dnd = it.getBoolean(Sync.KEY_DND),
                            night = it.getBoolean(Sync.KEY_NIGHT),
                        ),
                        dndChanged = it.getBoolean(Sync.KEY_DND_CHANGED),
                        nightChanged = it.getBoolean(Sync.KEY_NIGHT_CHANGED),
                        ts = it.getLong(Sync.KEY_TS),
                    )
                }
        } finally {
            buffer.release()
        }
    }

    private const val TAG = "PhoneCatchUp"
    private const val TIMEOUT_S = 10L
}
