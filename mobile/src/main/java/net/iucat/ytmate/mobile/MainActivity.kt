package net.iucat.ytmate.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * YTmate 手機／平板版主畫面：WebView 開伺服器上的手機版頁面（/m）。
 *  - 第一次開：輸入 YTmate 密碼 → 伺服器發這台手機專屬的裝置金鑰（App 裡不放密碼；之後自動登入；可在「📺 電視配對」頁取消）。
 *  - 頁面透過 window.YTmateApp 叫原生功能：播放（背景播放／鎖屏控制）、錄音（前景服務）、設定。
 *  - 從其他 App 分享 YouTube／B 站／CNN／X 連結進來 → 頁面的「📥 稍後看」。
 *  - 安全：WebView 只開本服務的網域，其他網址交給系統瀏覽器（JS 橋接不會暴露給別的網站）。
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val base = BuildConfig.BASE_URL
    private val baseHost: String = Uri.parse(BuildConfig.BASE_URL).host ?: ""
    private var pendingShare: String? = null
    private var pendingRec: JSONObject? = null
    private var pageReady = false

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.app = applicationContext
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0D1117"))
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "$userAgentString YTmateMobile/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.addJavascriptInterface(Bridge(), "YTmateApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == baseHost) {
                    // 登入頁：先試裝置金鑰自動登入
                    if (request.url.path == "/login") { autoLogin(); return true }
                    return false
                }
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) offline("連不到 YTmate 伺服器（${error.description}）")
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { pageReady = false }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                if (Uri.parse(url).path == "/login") { autoLogin(); return }
                pageReady = true
                deliverShare()
            }
        }
        web.webChromeClient = WebChromeClient()
        handleIntent(intent)
        askNotificationPermission()
        if (savedInstanceState == null) start() else web.restoreState(savedInstanceState)
        RecorderService.resumeUploads(this)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        deliverShare()
        if (intent.getStringExtra("open") == "rec") web.evaluateJavascript("window.mOpen && window.mOpen('rec')", null)
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.mVisible && window.mVisible(true)", null)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            pendingShare = Regex("https?://\\S+").find(text)?.value
        }
    }

    private fun deliverShare() {
        val u = pendingShare ?: return
        if (!pageReady) return
        pendingShare = null
        web.evaluateJavascript("window.mShare ? window.mShare(${JSONObject.quote(u)}) : 0", null)
    }

    private fun start() {
        if (Prefs.token(this).isEmpty()) askPassword(null) else autoLogin()
    }

    private var loginTries = ArrayList<Long>()

    /** 用裝置金鑰登入（背景）→ 開 /m；金鑰被取消（401）就重新問密碼；連不到伺服器就顯示離線頁。 */
    private fun autoLogin() {
        if (Prefs.token(this).isEmpty()) { askPassword(null); return }
        val now = System.currentTimeMillis()
        loginTries.removeAll { now - it > 30000 }
        loginTries.add(now)
        if (loginTries.size > 3) { offline("自動登入一直失敗，請稍後再試"); loginTries.clear(); return }
        Thread {
            val code = Api.reloginCode()
            runOnUiThread {
                when {
                    code == 200 -> web.loadUrl("$base/m")
                    code == 401 -> { Prefs.setToken(this, ""); askPassword("這台裝置的登入已被取消，請重新輸入密碼") }
                    code < 0 -> offline("連不到 YTmate 伺服器，請確認網路")
                    else -> offline("伺服器暫時有問題（HTTP $code）")
                }
            }
        }.start()
    }

    private fun askPassword(msg: String?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val pw = EditText(this).apply { hint = "YTmate 密碼"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val name = EditText(this).apply { hint = "這台裝置的名稱"; setText(Prefs.get(this@MainActivity, "device_name", defaultName())) }
        box.addView(pw); box.addView(name)
        AlertDialog.Builder(this)
            .setTitle("登入 YTmate")
            .setMessage((msg?.let { "$it\n\n" } ?: "") + "第一次使用：輸入 YTmate 密碼。之後這台裝置會自動登入（可在網頁的「📺 電視配對」頁取消）。")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("登入") { _, _ -> register(pw.text.toString(), name.text.toString().ifBlank { defaultName() }) }
            .show()
    }

    private fun register(password: String, name: String) {
        Thread {
            val r = Api.json("POST", "/api/device/register", JSONObject().put("password", password).put("name", name).put("kind", "phone"))
            val tok = if (r.code == 200) JSONObject(r.body).optString("token") else ""
            runOnUiThread {
                if (tok.isNotEmpty()) {
                    Prefs.setToken(this, tok); Prefs.set(this, "device_name", name)
                    autoLogin()
                } else {
                    val err = if (r.code < 0) "連不到伺服器" else try { JSONObject(r.body).optString("error", "登入失敗") } catch (_: Exception) { "登入失敗" }
                    askPassword("❌ $err")
                }
            }
        }.start()
    }

    private fun defaultName(): String {
        val m = (Build.MODEL ?: "手機").trim()
        return if (m.lowercase().startsWith(Build.MANUFACTURER.lowercase())) m else "${Build.MANUFACTURER} $m"
    }

    private fun offline(msg: String) {
        val html = "<html><body style='background:#0d1117;color:#c9d1d9;font-family:sans-serif;padding:32px'>" +
            "<h2>⚠️ $msg</h2><p>網路恢復後按下面的按鈕重試。沒傳完的錄音會在連上網路後自動補傳。</p>" +
            "<button style='font-size:18px;padding:12px 20px' onclick='YTmateApp.retry()'>🔄 重試</button></body></html>"
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    override fun onBackPressed() {
        web.evaluateJavascript("window.mBack ? window.mBack() : ''") { r ->
            if (r != null && r.contains("handled")) return@evaluateJavascript
            if (web.canGoBack()) web.goBack() else moveTaskToBack(true)     // 不關 App：背景播放／錄音照常
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            val o = pendingRec ?: return
            pendingRec = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startRec(o)
            else toast("需要麥克風權限才能錄音（設定 → 應用 → YTmate → 權限）")
        }
    }

    private fun startRec(o: JSONObject) {
        RecorderService.start(this, o.optString("title"), o.optString("kind", "meeting"), o.optString("lang", "zh"))
        web.evaluateJavascript("window.mRecChanged && window.mRecChanged()", null)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** 頁面（/m）可以呼叫的原生功能：window.YTmateApp.xxx() */
    inner class Bridge {
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun features(): String = "play,listen,speed,sleep,pip,record,share"
        @JavascriptInterface fun decoders(): String = Codecs.hardware().joinToString(",")
        @JavascriptInterface fun deviceName(): String = Prefs.get(this@MainActivity, "device_name", defaultName())

        /** 在主執行緒執行（播放器只能在主執行緒操作），等結果回來。 */
        private fun <T> onUi(def: T, ms: Long, fn: () -> T): T {
            var out = def
            val latch = CountDownLatch(1)
            runOnUiThread { try { out = fn() } finally { latch.countDown() } }
            latch.await(ms, TimeUnit.MILLISECONDS)
            return out
        }

        @JavascriptInterface
        fun play(json: String): Boolean = onUi(false, 3000) {
            val ok = PlayerHub.load(this@MainActivity, json)
            if (ok && JSONObject(json).optBoolean("open", true)) startActivity(Intent(this@MainActivity, PlayerActivity::class.java))
            ok
        }

        @JavascriptInterface
        fun nowPlaying(): String = onUi("{}", 1000) { PlayerHub.nowPlaying().toString() }

        @JavascriptInterface
        fun control(action: String, value: String): String {
            if (action == "open") { runOnUiThread { startActivity(Intent(this@MainActivity, PlayerActivity::class.java)) }; return "ok" }
            return onUi("", 2000) { PlayerHub.control(this@MainActivity, action, value) }
        }

        @JavascriptInterface fun recCapacity(): String = RecorderService.capacity(this@MainActivity).toString()
        @JavascriptInterface fun recStatus(): String = RecorderService.status(this@MainActivity).toString()

        @JavascriptInterface
        fun recStart(json: String) {
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingRec = o
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                } else startRec(o)
            }
        }

        @JavascriptInterface fun recStop() { RecorderService.stop(this@MainActivity) }

        @JavascriptInterface fun getPref(key: String): String = Prefs.get(this@MainActivity, "p_$key")
        @JavascriptInterface
        fun setPref(key: String, value: String) {
            Prefs.set(this@MainActivity, "p_$key", value)
            if (key == "subs") { Prefs.set(this@MainActivity, "subs", value); runOnUiThread { PlayerHub.applyTrackPrefs(PlayerHub.player(this@MainActivity)) } }
        }

        /** 小米／紅米：允許背景執行（省電策略「無限制」），背景播放與錄音才不會被系統關掉。 */
        @JavascriptInterface
        fun batterySettings(): String {
            runOnUiThread {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                try {
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        @SuppressLint("BatteryLife")
                        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        startActivity(i)
                    } else {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            return if (pm.isIgnoringBatteryOptimizations(packageName)) "ok" else "ask"
        }

        @JavascriptInterface
        fun batteryOk(): Boolean = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

        @JavascriptInterface fun retry() { runOnUiThread { start() } }

        @JavascriptInterface
        fun logout() {
            runOnUiThread {
                Prefs.setToken(this@MainActivity, "")
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                askPassword(null)
            }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            if (!url.startsWith("https://")) return
            runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
        }
    }
}

/** 這支手機能硬體解碼的影片格式（伺服器據此挑原檔或 480p 相容版）。 */
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
                else name.startsWith("omx.google.") || name.startsWith("c2.android.")
                if (software) continue
                for (t in ci.supportedTypes) MIME[t.lowercase()]?.let { found.add(it) }
            }
        } catch (_: Exception) {
        }
        return found.toList().also { cached = it }
    }
}
