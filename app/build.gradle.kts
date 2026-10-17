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
// 2026-10-17：手機遙控等大改版 → 版本名稱改成 1.1.x（第 10 次編譯起；versionCode 仍是編譯序號，一直遞增）
val versionLabel: String = if (buildNo >= 10) "1.1.${buildNo - 10}" else "1.0.$buildNo"
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
        versionName = versionLabel
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
        buildConfigField("boolean", "E2E", "false")
        manifestPlaceholders["cleartext"] = "false"
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
        // 自動測試版（GitHub Actions 的模擬器測試用）：連到測試伺服器（模擬器裡的 10.0.2.2＝執行測試的機器）、
        // 允許 http、開啟 WebView 偵錯。正式版不受影響。
        create("e2e") {
            initWith(getByName("debug"))
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8765\"")
            buildConfigField("boolean", "E2E", "true")
            manifestPlaceholders["cleartext"] = "true"
            signingConfig = signingConfigs.getByName("debug")
        }
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
    implementation("androidx.media3:media3-session:$media3")     // 媒體工作階段：系統／遙控器媒體鍵、Google 助理「暫停」
    implementation("androidx.media3:media3-database:$media3")    // 2026-10-17：播放暫存（SimpleCache 的索引資料庫）
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.2")
}
