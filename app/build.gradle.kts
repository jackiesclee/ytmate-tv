// YTmate TV — 小米電視／Android TV 用戶端（2026-10-14）
// 介面＝伺服器上的電視版頁面（WebView）；播放＝原生 ExoPlayer（Media3）。
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 伺服器網址：可在 GitHub Actions 用環境變數 YTMATE_BASE_URL 覆寫
val baseUrl: String = (System.getenv("YTMATE_BASE_URL") ?: "https://music.iucat.net").trimEnd('/')
// 版本號：GitHub Actions 每次編譯自動 +1（新版可以直接覆蓋安裝）
val buildNo: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
// 簽章：有提供金鑰庫才用正式簽章（之後更新都必須用同一把）；沒有就用除錯簽章
val ksPath: String? = System.getenv("YTMATE_KEYSTORE")
val hasKs: Boolean = ksPath != null && File(ksPath).exists()

android {
    namespace = "net.iucat.ytmate.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.iucat.ytmate.tv"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNo
        versionName = "1.0.$buildNo"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    signingConfigs {
        create("release") {
            if (hasKs) {
                storeFile = File(ksPath!!)
                storePassword = System.getenv("YTMATE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("YTMATE_KEY_ALIAS")
                keyPassword = System.getenv("YTMATE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKs) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.2")
}
