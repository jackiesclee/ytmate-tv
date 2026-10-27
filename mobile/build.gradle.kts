// YTmate 手機／平板版（2026-10-27）：小米手機、紅米手機、小米平板。
// 介面＝伺服器上的手機版頁面 /m（WebView，改介面不用重裝 App）；播放＝原生 ExoPlayer（背景播放、鎖屏控制、子母畫面）；
// 錄音＝原生前景服務（鎖螢幕照錄、邊錄邊上傳到「🎤 錄音」頻道）。
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val baseUrl: String = (System.getenv("YTMATE_BASE_URL") ?: "https://music.iucat.net").trimEnd('/')
val buildNo: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val ksPath: String? = System.getenv("YTMATE_KEYSTORE")
val hasKs: Boolean = ksPath != null && File(ksPath).exists()

android {
    namespace = "net.iucat.ytmate.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.iucat.ytmate.mobile"
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
    lint { checkReleaseBuilds = false; abortOnError = false }
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
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.2")
}
