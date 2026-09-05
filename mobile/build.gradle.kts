plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = rootProject.extra["appVersionName"] as String
val appVersionCode = rootProject.extra["appVersionCode"] as Int

android {
    namespace = "com.bazyak.dndsyncer.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bazyak.dndsyncer"
        minSdk = 34
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }
    buildTypes {
        release {
            // Хелпер запускается по имени класса через app_process,
            // статических ссылок на него нет — обфускация его убьёт.
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true }
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
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
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
                ?.set("DND syncer - $appVersionName.apk")
        }
    }
}
