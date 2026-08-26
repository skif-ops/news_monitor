plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ============================================================
// ВЕРСИЯ ПРИЛОЖЕНИЯ — меняйте эту строку при выпуске:
//   третья цифра — незначительные доработки (1.3.0 -> 1.3.1)
//   вторая цифра — большой релиз            (1.3.x -> 1.4.0)
//   первая цифра — коренное обновление      (1.x.x -> 2.0.0)
val appVersionName = "1.3.1"
// ============================================================

// внутренний счётчик сборок (невидим пользователю): растёт сам,
// поэтому каждая сборка ставится поверх предыдущей как обновление
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "ru.newsmonitor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.newsmonitor.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciRunNumber
        versionName = appVersionName
        ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a")) }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // сознательно без сжатия кода: поведение релиза = поведению отладки
            isMinifyEnabled = false
            isShrinkResources = false
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.tdl.coroutines)
}

