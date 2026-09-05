import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Автоинкремент версии.
 *
 * Считается один раз здесь, а не в каждом модуле — иначе телефон и часы
 * увеличивали бы счётчик по разу за сборку и разъезжались в номерах,
 * а Data Layer требует одинаковых версий у пары APK.
 *
 * Увеличиваем только когда реально собираем: на sync, clean и запуске тестов
 * номер стоит на месте.
 */
val versionFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}

val major = versionProps.getProperty("major", "1").toInt()
val minor = versionProps.getProperty("minor", "0").toInt()
var patch = versionProps.getProperty("patch", "0").toInt()
var code = versionProps.getProperty("code", "0").toInt()

val building = gradle.startParameter.taskNames.any { name ->
    listOf("assemble", "install", "bundle").any { name.contains(it, ignoreCase = true) }
}

if (building) {
    patch += 1
    code += 1
    versionFile.writeText(
        """
        # Версия приложения. patch и code увеличиваются автоматически при каждой
        # сборке (assemble/install/bundle). Правится руками только major и minor.
        major=$major
        minor=$minor
        patch=$patch
        code=$code
        """.trimIndent() + "\n",
    )
}

extra["appVersionName"] = "$major.$minor.$patch"
extra["appVersionCode"] = code
