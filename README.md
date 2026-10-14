# YTmate TV 📺

YTmate 的**小米電視／Android TV 用戶端**（APK）。打開就自動登入 `https://music.iucat.net`，用遙控器選頻道、播放影片或音樂。

<img src="brand/banner_1280x720.png" width="480" alt="YTmate TV">

## 下載與安裝

1. 到這個 repo 的 **Releases**，下載最新的 `YTmateTV-v1.0.N.apk`。每次推送到 `main` 都會自動編譯一版。
2. 把 APK 拷到 USB 隨身碟，插上電視，用「檔案管理」打開並安裝。
   - 第一次安裝時，電視會要求允許「安裝未知來源應用」：設定 → 帳號與安全 → 允許安裝未知來源應用，各機型的位置可能略有不同。
3. 之後出新版，直接安裝新的 APK 就會覆蓋舊版，不用先移除，配對也會保留。

## 第一次使用：配對（自動登入）

1. 在電視上打開 YTmate，畫面會顯示 **6 位數配對碼** 和 **QR code**。
2. 用**已登入 YTmate 的手機**掃描 QR code，或打開 `https://music.iucat.net/pair` 輸入配對碼。
3. 按「允許這台電視」，電視就會自動登入。之後每次打開都直接進首頁。

> APK 裡**沒有密碼**。每台電視都有自己的裝置金鑰，伺服器只存金鑰的雜湊值。
> 不用的電視可以在手機的 `/pair` 頁面按「取消配對」，那台電視會立刻登出。

## 遙控器操作

| 畫面 | 按鍵 | 動作 |
|---|---|---|
| 首頁／頻道頁 | 上下左右 | 移動選取 |
| | 確認 | 開啟頻道／播放 |
| | 返回 | 回上一層；在首頁連按兩次離開 |
| 播放中 | 確認 | 暫停／播放 |
| | 左／右 | 倒轉／快轉 10 秒 |
| | 上／下 | 顯示控制列（上一支、下一支、字幕） |
| | 頻道上／下、媒體鍵 | 上一支／下一支 |
| | 返回 | 收起控制列；再按一次回到頻道頁 |

## 架構

- **介面**：伺服器上的電視版頁面 `https://music.iucat.net/tv`，由全螢幕 WebView 顯示。改畫面只要更新伺服器，**不用重裝 App**。
- **播放**：原生 Media3 ExoPlayer，硬體解碼、遙控器原生支援，媒體請求會帶上登入 cookie。
- **登入**：裝置配對拿到長期裝置金鑰，存在 App 私有空間；每次開啟時 `POST /api/tv/login` 換成 session。
- **安全**：WebView 只允許開本服務的網域，其他網址一律擋掉。

```
app/src/main/java/net/iucat/ytmate/tv/
├── MainActivity.kt    # WebView 外殼、JS 橋接（window.YTmateTV）、返回鍵、離線重試畫面
└── PlayerActivity.kt  # 原生播放器（清單播放、音樂顯示封面、字幕、遙控器按鍵）
brand/                 # 圖示與橫幅原始檔（SVG）
.github/workflows/build.yml  # 自動編譯＋發佈 Release
```

## 自己編譯（選用）

- 需要 JDK 17、Android SDK（API 34）和 Gradle 8.9，執行 `gradle :app:assembleRelease`。
- 伺服器網址可以用環境變數 `YTMATE_BASE_URL` 覆寫，預設是 `https://music.iucat.net`。
- 正式簽章用 `YTMATE_KEYSTORE`、`YTMATE_KEYSTORE_PASSWORD`、`YTMATE_KEY_ALIAS`、`YTMATE_KEY_PASSWORD` 這幾個環境變數提供（GitHub Actions 讀 repo Secrets）。

最低支援 Android 7.0（API 24）。
