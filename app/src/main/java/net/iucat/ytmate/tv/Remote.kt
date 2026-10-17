package net.iucat.ytmate.tv

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * 📱→📺 手機遙控（2026-10-17，v1.1）：App 開著時向伺服器「領指令」、定時回報播放狀態。
 *
 * 伺服器在台灣、電視在中國家裡（外面連不進來），所以一律由電視主動連出去：
 *   - 長輪詢 GET /api/remote/poll?wait=20：有指令立刻回來，沒有就等 20 秒再問一次。
 *   - 每 10 秒（播放狀態改變時立刻）POST /api/remote/state，順便帶上指令的執行結果（acks）。
 * 同一個指令 id 只執行一次（網路重送也不會重複執行）。用 WebView 的登入 cookie（電視的裝置 session）。
 *
 * 指令：
 *   play    → 交給電視頁面 window.tvRemote()（頁面跟伺服器拿這台電視的播放資料後開播放器，執行結果由頁面回報）
 *   control → 播放中交給播放器；沒在播放時只處理音量、字幕、畫質
 *   toast   → 右上角提示（播放器或頁面）
 */
object Remote {
    private const val TAG = "YTmateTV"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var gen = 0                 // 每次重新啟動 +1：舊的連線執行緒看到不一樣就結束（避免兩組同時在領指令）
    @Volatile private var started = 0
    private val lock = Object()
    private var app: Context? = null
    private val acks = ConcurrentLinkedQueue<JSONObject>()
    private val seen = LinkedHashSet<String>()
    @Volatile private var wake = false
    private var mainRef: WeakReference<MainActivity>? = null
    private var playerRef: WeakReference<PlayerActivity>? = null
    @Volatile var lastPollOk = 0L

    val mainActivity: MainActivity? get() = mainRef?.get()
    val player: PlayerActivity? get() = playerRef?.get()?.takeIf { p -> !p.isFinishing }

    fun attachMain(a: MainActivity) { mainRef = WeakReference(a) }
    fun attachPlayer(p: PlayerActivity?) { playerRef = if (p == null) null else WeakReference(p) }

    /** 任一個畫面（首頁或播放器）在前景就保持連線；全部離開（按首頁鍵、關掉 App）就停。 */
    @Synchronized
    fun onStart(ctx: Context) {
        app = ctx.applicationContext
        started++
        if (!running) {
            running = true
            val g = ++gen
            thread(isDaemon = true, name = "remote-poll") { pollLoop(g) }
            thread(isDaemon = true, name = "remote-state") { stateLoop(g) }
        }
    }

    @Synchronized
    fun onStop() {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) {
            running = false
            reportNow()
        }
    }

    private fun cookie(): String = try { CookieManager.getInstance().getCookie(BuildConfig.BASE_URL) ?: "" } catch (_: Exception) { "" }

    private fun http(method: String, path: String, body: JSONObject?, readTimeout: Int): Pair<Int, String> {
        val c = URL(BuildConfig.BASE_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = readTimeout
            c.setRequestProperty("User-Agent", "YTmateTV/${BuildConfig.VERSION_NAME}")
            val ck = cookie()
            if (ck.isNotEmpty()) c.setRequestProperty("Cookie", ck)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { w -> w.write(body.toString()) }
            }
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { r -> r.readText() } ?: ""
            return code to txt
        } finally {
            c.disconnect()
        }
    }

    // ══ 領指令 ══
    private fun pollLoop(g: Int) {
        var backoff = 2000L
        while (running && g == gen) {
            if (cookie().isEmpty()) { sleep(5000); continue }
            try {
                val (code, txt) = http("GET", "/api/remote/poll?wait=20", null, 35_000)
                if (code == 401 || code == 403) { sleep(20_000); continue }     // 還沒登入／不是裝置 session：等頁面重新登入
                if (code != 200) { sleep(backoff); backoff = (backoff * 2).coerceAtMost(30_000); continue }
                backoff = 2000L
                lastPollOk = System.currentTimeMillis()
                val arr = JSONObject(txt).optJSONArray("cmds") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val cmd = arr.getJSONObject(i)
                    val id = cmd.optString("id")
                    val dup = synchronized(seen) {
                        if (seen.contains(id)) true else {
                            seen.add(id)
                            if (seen.size > 300) seen.remove(seen.first())
                            false
                        }
                    }
                    if (dup) { ack(id, true, "（重複的指令，已略過）"); continue }
                    Log.i(TAG, "remote cmd ${cmd.optString("type")} id=$id args=${cmd.optJSONObject("args")}")
                    main.post { dispatch(cmd) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "remote poll failed: $e")
                sleep(backoff); backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    // ══ 回報狀態（每 10 秒；有變化或有執行結果時立刻）══
    private fun stateLoop(g: Int) {
        while ((running && g == gen) || (!running && acks.isNotEmpty())) {
            try {
                val st = buildStateOnMain()
                val body = JSONObject().put("state", st)
                val list = JSONArray()
                while (true) { val a = acks.poll() ?: break; list.put(a) }
                if (list.length() > 0) body.put("acks", list)
                if (cookie().isNotEmpty()) {
                    val (code, _) = http("POST", "/api/remote/state", body, 15_000)
                    if (code >= 500 && list.length() > 0) for (k in 0 until list.length()) acks.add(list.getJSONObject(k))
                }
            } catch (e: Exception) {
                Log.w(TAG, "remote state failed: $e")
            }
            if ((!running && acks.isEmpty()) || g != gen) break
            synchronized(lock) {
                if (!wake) try { lock.wait(10_000) } catch (_: InterruptedException) {}
                wake = false
            }
        }
    }

    fun reportNow() {
        synchronized(lock) { wake = true; lock.notifyAll() }
    }

    fun ack(id: String, ok: Boolean, msg: String) {
        if (id.isEmpty()) return
        acks.add(JSONObject().put("id", id).put("ok", ok).put("msg", msg))
        Log.i(TAG, "remote ack id=$id ok=$ok msg=$msg")
        reportNow()
    }

    private fun buildStateOnMain(): JSONObject {
        var out = JSONObject()
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            try { out = buildState() } catch (e: Exception) { Log.w(TAG, "state build: $e") }
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    private fun buildState(): JSONObject {
        val ctx = app ?: return JSONObject()
        val o = player?.remoteState() ?: JSONObject().put("screen", if (started > 0) "browse" else "off").put("playing", false)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        o.put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("volume_max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("muted", try { am.isStreamMute(AudioManager.STREAM_MUSIC) } catch (_: Exception) { false })
            .put("version", BuildConfig.VERSION_NAME)
            .put("subs", Prefs.get(ctx, "subs").ifEmpty { "en" })
            .put("quality", Prefs.get(ctx, "quality").ifEmpty { "auto" })
        val ci = MediaCache.info(ctx)
        o.put("cache_cap", ci.optLong("cap")).put("cache_used", ci.optLong("used")).put("free_space", ci.optLong("free"))
        return o
    }

    // ══ 執行指令（主執行緒）══
    private fun dispatch(cmd: JSONObject) {
        val id = cmd.optString("id")
        val args = cmd.optJSONObject("args") ?: JSONObject()
        when (cmd.optString("type")) {
            "play" -> {
                val m = mainActivity
                if (m == null) { ack(id, false, "電視上的 YTmate 沒有開著"); return }
                m.runJs("window.tvRemote ? (window.tvRemote(${JSONObject.quote(cmd.toString())}) ? 'ok' : 'bad') : 'old'") { r ->
                    if (r == null || !r.contains("ok")) ack(id, false, if (r != null && r.contains("old")) "電視頁面太舊，請更新伺服器" else "電視頁面還沒準備好")
                    // 成功的結果由頁面開始播放後回報（YTmateTV.remoteAck）
                }
            }
            "control" -> {
                val action = args.optString("action")
                val p = player
                val r = if (p != null) p.remoteControl(action, args.opt("value")) else idleControl(action, args.opt("value"))
                ack(id, r.first, r.second)
            }
            "toast" -> {
                val text = args.optString("text")
                val img = args.optString("image").takeIf { s -> s.startsWith("/api/") }?.let { s -> BuildConfig.BASE_URL + s } ?: ""
                val p = player
                if (p != null) p.showNotice(text, img)
                else mainActivity?.runJs("window.tvNotify && window.tvNotify(${JSONObject.quote(text)}, ${JSONObject.quote(img)})", null)
                Log.i(TAG, "notice shown text=$text")
                ack(id, true, "已顯示")
            }
            else -> ack(id, false, "這個版本的 App 不認得這個指令，請更新")
        }
        reportNow()
    }

    /** 沒有在播放時：只有音量、字幕、畫質能調。 */
    private fun idleControl(action: String, value: Any?): Pair<Boolean, String> {
        val ctx = app ?: return false to "App 還沒準備好"
        return when (action) {
            "volume_up", "volume_down", "volume_set", "mute" -> volume(action, value)
            "subs" -> { val v = value?.toString() ?: ""; if (v in setOf("en", "zh", "off")) { Prefs.set(ctx, "subs", v); true to "字幕設定：$v（下次播放套用）" } else false to "字幕設定不對" }
            "subs_toggle" -> {
                val cur = Prefs.get(ctx, "subs").ifEmpty { "en" }
                val nv = if (cur == "off") Prefs.get(ctx, "subs_last").ifEmpty { "en" } else { Prefs.set(ctx, "subs_last", cur); "off" }
                Prefs.set(ctx, "subs", nv); true to (if (nv == "off") "字幕：關閉（下次播放套用）" else "字幕：開啟（下次播放套用）")
            }
            "quality" -> { val v = value?.toString() ?: ""; if (v == "low" || v == "auto") { Prefs.set(ctx, "quality", v); true to (if (v == "low") "畫質：480p 省流量" else "畫質：自動") } else false to "畫質設定不對" }
            "stop" -> true to "目前沒有在播放"
            else -> false to "電視目前沒有在播放"
        }
    }

    /** 媒體音量（電視喇叭）：大聲／小聲／指定／靜音切換。會跳出系統的音量條。 */
    fun volume(action: String, value: Any?): Pair<Boolean, String> {
        val ctx = app ?: return false to "App 還沒準備好"
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = AudioManager.STREAM_MUSIC
        return try {
            when (action) {
                "volume_up" -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "volume_down" -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "volume_set" -> am.setStreamVolume(s, ((value as? Number)?.toInt() ?: am.getStreamVolume(s)).coerceIn(0, am.getStreamMaxVolume(s)), AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            }
            val muted = try { am.isStreamMute(s) } catch (_: Exception) { false }
            true to (if (muted) "靜音" else "音量 ${am.getStreamVolume(s)} / ${am.getStreamMaxVolume(s)}")
        } catch (e: Exception) {
            false to "電視不允許調整音量（$e）"
        }
    }

    /** 看到哪裡也存到伺服器（電視、網頁、其他電視共用）。背景送出，失敗就算了（App 自己也有記）。 */
    fun saveResume(vid: String, posMs: Long, durMs: Long, done: Boolean) {
        if (vid.isEmpty() || cookie().isEmpty()) return
        thread(isDaemon = true, name = "remote-resume") {
            try {
                val body = JSONObject().put("pos", posMs / 1000.0).put("dur", durMs / 1000.0).put("done", done)
                val (code, _) = http("POST", "/api/resume/" + java.net.URLEncoder.encode(vid, "UTF-8"), body, 15_000)
                Log.i(TAG, "resume server vid=$vid pos=$posMs done=$done code=$code")
            } catch (e: Exception) {
                Log.w(TAG, "resume server failed: $e")
            }
        }
    }

    /** 影片資訊（章節、摘要）：背景下載，回主執行緒交給 done。 */
    fun fetchInfo(vid: String, done: (JSONObject) -> Unit) {
        if (vid.isEmpty()) return
        thread(isDaemon = true, name = "remote-info") {
            try {
                val (code, txt) = http("GET", "/api/tv/info/" + java.net.URLEncoder.encode(vid, "UTF-8"), null, 15_000)
                if (code == 200) { val j = JSONObject(txt); main.post { done(j) } }
            } catch (e: Exception) {
                Log.w(TAG, "info failed: $e")
            }
        }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
}
