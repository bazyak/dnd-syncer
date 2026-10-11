package com.bazyak.dndsyncer.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Применение команды соседа и решение о публикации не должны пересекаться.
 *
 * Иначе публикация застаёт применение посередине: читает ещё старое
 * значение, видит в нём изменение и отправляет соседу как команду. А
 * подтверждение ([DndSync.acknowledge]) должно попасть в известное соседу
 * состояние раньше, чем публикация его прочитает.
 *
 * ReentrantLock, а не корутинный Mutex: на часах подъём Shizuku посреди
 * применения запускает догон телефона, который применяет режимы ещё раз в
 * том же потоке.
 */
object SyncLock {
    private val lock = ReentrantLock()

    fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
