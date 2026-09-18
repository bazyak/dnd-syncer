import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Версионирование двух модулей.
 *
 * Правки часто касаются только одной части, поэтому счётчики у телефона и
 * часов свои. Но Data Layer связывает пару APK лишь при совпадении версий,
 * поэтому логика устроена так, чтобы расхождение само схлопывалось:
 *
 *   версии равны         -> собираемая часть увеличивается;
 *   собираем отстающую   -> подтягивается до старшей, без увеличения;
 *   собираем опережающую -> увеличивается дальше;
 *   собираем обе         -> обе получают max + 1.
 *
 * Считается один раз здесь и раздаётся модулям через extra.
 */
data class Version(var patch: Int, var code: Int)

val versionFile = rootProject.file("version.properties")
val props = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}

val major = props.getProperty("major", "1").toInt()
val minor = props.getProperty("minor", "0").toInt()
val mobile = Version(
    props.getProperty("mobilePatch", "0").toInt(),
    props.getProperty("mobileCode", "0").toInt(),
)
val wear = Version(
    props.getProperty("wearPatch", "0").toInt(),
    props.getProperty("wearCode", "0").toInt(),
)

val taskNames = gradle.startParameter.taskNames
fun isBuildTask(name: String) =
    listOf("assemble", "install", "bundle").any { name.contains(it, ignoreCase = true) }

val buildTasks = taskNames.filter(::isBuildTask)
// Задача без имени модуля (например просто `assembleDebug`) собирает всё.
val allModules = buildTasks.any {
    !it.contains("wear", ignoreCase = true) && !it.contains("mobile", ignoreCase = true)
}
val buildMobile = allModules || buildTasks.any { it.contains("mobile", ignoreCase = true) }
val buildWear = allModules || buildTasks.any { it.contains("wear", ignoreCase = true) }

fun bump(v: Version) {
    v.patch += 1
    v.code += 1
}

fun alignTo(lagging: Version, leading: Version) {
    lagging.patch = leading.patch
    // code только растёт: иначе установка поверх прежней сборки будет отбита.
    lagging.code = maxOf(lagging.code, leading.code)
}

when {
    buildMobile && buildWear -> {
        val next = maxOf(mobile.patch, wear.patch) + 1
        val nextCode = maxOf(mobile.code, wear.code) + 1
        mobile.patch = next; mobile.code = nextCode
        wear.patch = next; wear.code = nextCode
    }
    buildMobile -> when {
        mobile.patch < wear.patch -> alignTo(mobile, wear)
        else -> bump(mobile)
    }
    buildWear -> when {
        wear.patch < mobile.patch -> alignTo(wear, mobile)
        else -> bump(wear)
    }
}

if (buildMobile || buildWear) {
    versionFile.writeText(
        """
        # Версии приложения. Правятся руками только major и minor.
        #
        # patch и code у телефона и часов ведутся отдельно, потому что менять можно
        # только одну из частей. Правила при сборке:
        #   версии равны            -> собираемая часть увеличивается;
        #   собираем отстающую      -> она подтягивается до старшей, без увеличения;
        #   собираем опережающую    -> она увеличивается дальше;
        #   собираем обе сразу      -> обе получают max + 1.
        major=$major
        minor=$minor
        mobilePatch=${mobile.patch}
        mobileCode=${mobile.code}
        wearPatch=${wear.patch}
        wearCode=${wear.code}
        """.trimIndent() + "\n",
    )
}

extra["mobileVersionName"] = "$major.$minor.${mobile.patch}"
extra["mobileVersionCode"] = mobile.code
extra["wearVersionName"] = "$major.$minor.${wear.patch}"
extra["wearVersionCode"] = wear.code
