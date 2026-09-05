plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bazyak.dndsyncer.core"
    compileSdk = 36
    defaultConfig { minSdk = 34 }
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
    api(libs.androidx.core.ktx)
    api(libs.play.services.wearable)
    api(libs.coroutines.play.services)
    // Нужен и телефону: Shell ссылается на ShizukuShell. На телефоне
    // используется рут, поэтому провайдер в его манифест не добавлен.
    api(libs.shizuku.api)
    api(libs.shizuku.provider)
}
