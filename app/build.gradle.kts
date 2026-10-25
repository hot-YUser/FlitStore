import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}

android {
    namespace = "dev.yyblite"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.yyblite"
        minSdk = 24
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.3"
    }

    buildFeatures {
        compose = true
    }

    // 依 values-* 資料夾自動產生 localeConfig，Android 13+ 的「個別應用程式語言」據此列出可選語言。
    androidResources {
        generateLocaleConfig = true
    }

    // 新增介面字串時，三種語言必須同時補齊，漏翻在建置時就擋下。
    lint {
        error += "MissingTranslation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            val storePath = localProps.getProperty("release.storeFile")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = localProps.getProperty("release.storePassword")
                keyAlias = localProps.getProperty("release.keyAlias")
                keyPassword = localProps.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!localProps.getProperty("release.storeFile").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    // 完整的 Material 3 Expressive 元件只在 1.5 系列；beta 代表 API 已凍結。
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.work:work-runtime:2.12.0")
    // Compose 已帶入 coroutines，這裡只為編譯期可見。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Shizuku：免外部安裝器的特權安裝通道
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
}
