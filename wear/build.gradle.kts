plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = rootProject.extra["appVersionName"] as String
val appVersionCode = rootProject.extra["appVersionCode"] as Int

android {
    namespace = "com.bazyak.dndsyncer.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bazyak.dndsyncer"
        minSdk = 34
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // Токен автозапуска Shizuku. Берётся в самом Shizuku:
        // «Управляйте Shizuku с помощью приложений автоматизации» →
        // «Просмотр намерений» → Extras → auth.
        // Задаётся в gradle.properties или local.properties строкой
        //   shizukuAuth=<токен>
        buildConfigField(
            "String",
            "SHIZUKU_AUTH",
            "\"${project.findProperty("shizukuAuth") ?: ""}\"",
        )
    }
    buildTypes { release { isMinifyEnabled = false } }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

/**
 * Имя APK. Стандартное mobile-debug.apk заменяется на человекочитаемое
 * с номером версии. VariantOutputImpl — внутренний класс AGP, публичного
 * API для переименования выходного файла до сих пор нет; при переходе на
 * AGP 9 этот блок придётся переписать.
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("DND syncer (wear) - $appVersionName.apk")
        }
    }
}
