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
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.util.Log
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
 * - 2026-10-15：選單鍵＝開／關設定；從播放器回來時焦點停在最後播放的那一支（window.tvAfterPlay）；
 *   登入過期（頁面收到 401）時先用裝置金鑰自動重新登入（YTmateTV.relogin()），失效了才回配對畫面。
 * - 2026-10-17（v1.1）：📱 手機遙控（Remote：領指令、回報狀態）；播放暫存設定（MediaCache）；
 *   告訴頁面「看得到／看不到」（window.tvVisible，播放器蓋在上面時頁面不啟動螢幕保護）；
 *   播完整份清單時問頁面有沒有待播清單（window.tvQueueNext）。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var lastBack = 0L
    // v1.1.2：確認鍵的短按／長按由 App 判斷再告訴頁面（網頁核心收不到遙控器「按住」的重複訊號，頁面自己判斷不可靠）。
    // 頁面呼叫 YTmateTV.wantOkKeys(true) 才啟用；換頁就關掉（配對頁、離線頁照舊）。
    @Volatile private var pageOkKeys = false
    private var okDownAt = 0L
    private var okLongSent = false
    private val OK_LONG_MS = 600L
    @Volatile private var lastRelogin = 0L
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

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageOkKeys = false
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                // 保險：頁面有 tvOkKey 就由 App 判斷確認鍵（跟 wantOkKeys 同一件事，避免時間差漏掉）
                view.evaluateJavascript("window.tvOkKey ? '1' : '0'") { r -> if (r != null && r.contains("1")) pageOkKeys = true }
            }
        }
        web.webChromeClient = WebChromeClient()
        // 2026-10-17：關掉 Android 8 以上的系統焦點外框（小米電視上會在畫面上畫出大黃框；選取效果由頁面自己畫）
        if (Build.VERSION.SDK_INT >= 26) web.defaultFocusHighlightEnabled = false
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.requestFocus()

        Remote.attachMain(this)
        if (savedInstanceState == null) start() else web.restoreState(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        Remote.attachMain(this)
        Remote.onStart(this)
    }

    override fun onStop() {
        Remote.onStop()
        super.onStop()
    }

    /** 給 Remote／播放器在頁面上執行 JS（主執行緒）。 */
    fun runJs(js: String, cb: ((String?) -> Unit)?) {
        if (isFinishing) { cb?.invoke(null); return }
        web.evaluateJavascript(js) { r -> cb?.invoke(r) }
    }

    /** 播放器播完整份清單：請頁面看看有沒有待播清單（有的話頁面會直接開始播；沒有就回呼 queueEmpty）。 */
    fun askQueueNext(): Boolean {
        if (isFinishing) return false
        web.evaluateJavascript("window.tvQueueNext ? (window.tvQueueNext(), 'ok') : 'old'") { r ->
            if (r == null || !r.contains("ok")) PlayerActivity.onQueueEmpty()
        }
        return true
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
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {       // 選單鍵（很多電視遙控器都有「≡」）→ 開／關設定
            if (event.action == KeyEvent.ACTION_UP) web.evaluateJavascript("window.tvMenu && window.tvMenu()", null)
            return true
        }
        if (pageOkKeys && isOkKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) { okDownAt = SystemClock.uptimeMillis(); okLongSent = false }
                else if (okDownAt > 0 && !okLongSent) { okLongSent = true; okToPage("long") }   // 按住不放（遙控器送重複按下）
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (okDownAt > 0 && !okLongSent) okToPage(if (SystemClock.uptimeMillis() - okDownAt >= OK_LONG_MS) "long" else "short")
                okDownAt = 0L
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isOkKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun okToPage(kind: String) {
        Log.i("YTmateTV", "page ok key=$kind")
        web.evaluateJavascript("window.tvOkKey && window.tvOkKey('$kind')", null)
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
        web.evaluateJavascript("window.tvVisible && window.tvVisible(true)", null)
        hideSystemUi()
        web.requestFocus()                                    // 從播放器回來：遙控器按鍵一定送到頁面
        PlayerActivity.lastVideoId?.let { vid ->              // 焦點移到最後播放的那一支
            PlayerActivity.lastVideoId = null
            web.evaluateJavascript("window.tvAfterPlay && window.tvAfterPlay(${JSONObject.quote(vid)})", null)
        }
    }

    override fun onPause() {
        web.evaluateJavascript("window.tvVisible && window.tvVisible(false)", null)
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
            // 播放器已經開著（手機送來新的影片、或接著播待播清單）→ 同一個播放器換清單（singleTop → onNewIntent）
            runOnUiThread { startActivity(Intent(this@MainActivity, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        }

        /** 手機遙控的播放指令：頁面開始播放（或失敗）後回報結果。 */
        @JavascriptInterface
        fun remoteAck(id: String, ok: Boolean, msg: String) = Remote.ack(id, ok, msg)

        /** 頁面查過了：沒有待播清單 → 播放器回到頻道頁。 */
        /** v1.1.2：頁面要 App 幫忙判斷確認鍵短按／長按（結果用 window.tvOkKey('short'|'long') 告訴頁面） */
        @JavascriptInterface
        fun wantOkKeys(on: Boolean) { runOnUiThread { pageOkKeys = on; Log.i("YTmateTV", "page ok keys=$on") } }

        @JavascriptInterface
        fun queueEmpty() { runOnUiThread { PlayerActivity.onQueueEmpty() } }

        /** 播放暫存的狀態：{mode, cap, used, free}（bytes）。 */
        @JavascriptInterface
        fun cacheInfo(): String = MediaCache.info(this@MainActivity).toString()

        /** 清除播放暫存，回傳釋放了多少 bytes。 */
        @JavascriptInterface
        fun clearCache(): String = MediaCache.clear(this@MainActivity).toString()

        /** 頁面收到 401（登入過期）→ 用裝置金鑰重新登入。回傳 false＝沒有金鑰或 30 秒內已試過（頁面改去配對畫面）。 */
        @JavascriptInterface
        fun relogin(): Boolean {
            val now = System.currentTimeMillis()
            if (Prefs.token(this@MainActivity).isNullOrEmpty() || now - lastRelogin < 30_000) return false
            lastRelogin = now
            runOnUiThread { start() }
            return true
        }

        /** 使用者設定（畫質 quality：auto／low；字幕 subs：en／zh／off）：存在 App 裡，一直記住直到再改。 */
        @JavascriptInterface
        fun getPref(key: String): String = Prefs.get(this@MainActivity, key)

        @JavascriptInterface
        fun setPref(key: String, value: String) = Prefs.set(this@MainActivity, key, value)

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

    // 使用者設定：只接受這幾個鍵與值（頁面不能拿這個存任意資料；取消配對也不會清掉）
    private val ALLOWED = mapOf("quality" to setOf("auto", "low"), "subs" to setOf("en", "zh", "off"),
        "subs_last" to setOf("en", "zh"),          // 播放器按「下」關字幕前的語言（再打開時用）
        "cache" to setOf("auto", "off"))           // 2026-10-17：播放暫存（auto＝依剩餘空間自動、off＝不暫存）

    fun get(ctx: Context, key: String): String {
        if (key !in ALLOWED) return ""
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("pref_$key", "") ?: ""
    }

    fun set(ctx: Context, key: String, value: String) {
        if (ALLOWED[key]?.contains(value) != true) return
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("pref_$key", value).apply()
    }
}
