package net.iucat.ytmate.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import java.net.URLEncoder

/**
 * YTmate TV 主畫面：全螢幕 WebView 開伺服器上的電視版頁面（/tv）。
 *
 * - 自動登入：有裝置金鑰 → POST /api/tv/login（伺服器發長期 session cookie）→ 導向 /tv；
 *   沒有金鑰 → 開 /tv/pair 顯示配對碼＋QR，手機核准後頁面呼叫 YTmateTV.saveToken() 存金鑰。
 * - 按「播放」時頁面呼叫 YTmateTV.play(json)，交給原生播放器 PlayerActivity（ExoPlayer）。
 * - 返回鍵：先問頁面 window.tvBack()（關視窗、回首頁）；已在首頁時連按兩次才離開。
 * - 安全：WebView 只允許開本服務的網域，其他網址一律擋掉（JS 橋接不會暴露給別的網站）。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var lastBack = 0L
    private val base = BuildConfig.BASE_URL
    private val baseHost: String = Uri.parse(BuildConfig.BASE_URL).host ?: ""

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.E2E) WebView.setWebContentsDebuggingEnabled(true)     // 只有自動測試版開啟（讓測試程式檢查頁面狀態）
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#09090F"))
        setContentView(web)
        hideSystemUi()

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = "$userAgentString YTmateTV/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.addJavascriptInterface(Bridge(), "YTmateTV")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return true
                return host != baseHost          // 只允許本服務的網域
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline("無法連到伺服器（${error.description}）")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, resp: WebResourceResponse) {
                if (request.isForMainFrame && resp.statusCode >= 500) showOffline("伺服器暫時沒有回應（HTTP ${resp.statusCode}）")
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
            }
        }
        web.webChromeClient = WebChromeClient()
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.requestFocus()

        if (savedInstanceState == null) start() else web.restoreState(savedInstanceState)
    }

    /** 有金鑰就自動登入，沒有就開配對畫面。 */
    private fun start() {
        val token = Prefs.token(this)
        if (token.isNullOrEmpty()) {
            web.loadUrl("$base/tv/pair")
        } else {
            val body = "token=" + URLEncoder.encode(token, "UTF-8")
            web.postUrl("$base/api/tv/login", body.toByteArray(Charsets.UTF_8))
        }
    }

    private fun showOffline(reason: String) {
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#09090f;color:#f4f4f8;font-family:sans-serif;text-align:center">
            <div><div style="font-size:4vw">📡</div>
            <div style="font-size:2.4vw;font-weight:700;margin:1.5vw 0">連不上 YTmate</div>
            <div style="font-size:1.4vw;color:#a3a3b8">$reason</div>
            <div style="font-size:1.4vw;color:#a3a3b8;margin-top:.6vw">請確認電視網路與伺服器狀態</div>
            <div id="b" tabindex="0" style="display:inline-block;margin-top:3vw;padding:1vw 3vw;border-radius:99px;background:#fff;color:#111;font-size:1.6vw;font-weight:700">↻ 按確認鍵重試</div>
            <div style="font-size:1.1vw;color:#6b6b80;margin-top:1.5vw">每 15 秒自動重試</div></div>
            <script>document.onkeydown=function(e){if(e.keyCode==13||e.keyCode==23){YTmateTV.retry()}};setTimeout(function(){YTmateTV.retry()},15000)</script>
            </body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack() {
        web.evaluateJavascript("(window.tvBack && window.tvBack()) ? '1' : '0'") { r ->
            if (r != null && r.contains("1")) return@evaluateJavascript
            val now = System.currentTimeMillis()
            if (now - lastBack < 2000) {
                finish()
            } else {
                lastBack = now
                Toast.makeText(this, "再按一次返回鍵離開 YTmate", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        hideSystemUi()
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** 給頁面呼叫的橋接（window.YTmateTV）。JS 呼叫是在背景執行緒，UI 動作要切回主執行緒。 */
    inner class Bridge {
        @JavascriptInterface
        fun saveToken(token: String) {
            Prefs.saveToken(this@MainActivity, token)
            runOnUiThread { start() }
        }

        @JavascriptInterface
        fun clearToken() {
            Prefs.clear(this@MainActivity)
        }

        @JavascriptInterface
        fun unpair() {
            Prefs.clear(this@MainActivity)
            runOnUiThread {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                web.loadUrl("$base/tv/pair")
            }
        }

        @JavascriptInterface
        fun play(json: String) {
            PlayerActivity.pending = json
            runOnUiThread { startActivity(Intent(this@MainActivity, PlayerActivity::class.java)) }
        }

        @JavascriptInterface
        fun retry() {
            runOnUiThread { start() }
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun deviceName(): String {
            val n = try {
                Settings.Global.getString(contentResolver, "device_name")
            } catch (e: Exception) {
                null
            }
            return (n ?: "${Build.MANUFACTURER} ${Build.MODEL}").take(40)
        }

        /** 這台電視能「硬體」解碼的影片格式，例如 "h264,hevc,vp9"（伺服器據此挑原檔或 480p 相容版）。 */
        @JavascriptInterface
        fun decoders(): String = Codecs.hardware().joinToString(",")

        /** 裝置資訊（設定頁顯示；回報問題用）。 */
        @JavascriptInterface
        fun deviceInfo(): String {
            val wv = try {
                if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName ?: "" else ""
            } catch (e: Exception) { "" }
            return JSONObject()
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("webview", wv)
                .put("decoders", JSONArray(Codecs.hardware()))
                .toString()
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }
    }
}

/** 偵測硬體解碼器。軟體解碼器（OMX.google.*、c2.android.* 等）不算：電視的 CPU 軟解 AV1／VP9 高畫質會卡頓。
 *  H.264 一律算支援（所有 Android 電視都有）。 */
object Codecs {
    private val MIME = linkedMapOf("video/avc" to "h264", "video/hevc" to "hevc", "video/x-vnd.on2.vp9" to "vp9", "video/av01" to "av1")
    private var cached: List<String>? = null

    fun hardware(): List<String> {
        cached?.let { return it }
        val found = linkedSetOf("h264")
        try {
            for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (ci.isEncoder) continue
                val name = ci.name.lowercase()
                val software = if (Build.VERSION.SDK_INT >= 29) ci.isSoftwareOnly
                else name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw.") || name.contains("ffmpeg")
                if (software) continue
                for (t in ci.supportedTypes) MIME[t.lowercase()]?.let { found.add(it) }
            }
        } catch (_: Exception) {
        }
        return found.toList().also { cached = it }
    }
}

/** 裝置金鑰存在 App 私有空間（其他 App 讀不到；移除 App 就會清掉）。 */
object Prefs {
    private const val FILE = "ytmate_tv"
    private const val KEY = "device_token"

    fun token(ctx: Context): String? = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)

    fun saveToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, token.trim()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
