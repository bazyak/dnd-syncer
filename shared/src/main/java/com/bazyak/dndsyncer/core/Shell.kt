package com.bazyak.dndsyncer.core

/**
 * Единая точка выполнения привилегированных команд.
 * Телефон рутован — идёт через su. На часах рута нет, но есть Shizuku.
 */
object Shell {

    enum class Backend { ROOT, SHIZUKU, NONE }

    fun backend(): Backend = when {
        RootShell.isAvailable() -> Backend.ROOT
        ShizukuShell.isReady() -> Backend.SHIZUKU
        else -> Backend.NONE
    }

    fun exec(command: String): String? = when (backend()) {
        Backend.ROOT -> RootShell.exec(command)
        Backend.SHIZUKU -> ShizukuShell.exec(command)
        Backend.NONE -> null
    }

    fun isAvailable(): Boolean = backend() != Backend.NONE
}
