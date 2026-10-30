package net.iucat.ytmate.tv

import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 🩺 播放狀況回報（2026-10-30）：開始播放等了多久、播到一半緩衝多久、快轉／跳轉等了多久、播放錯誤
 * → 伺服器 /api/playback/report（帶登入 cookie）。先存著，每 15 秒或離開播放畫面時一次送出；送不出去就丟掉（不影響播放）。
 * 伺服器的「🩺 播放狀況」頁會把這些跟當下伺服器忙不忙對照，判斷卡頓比較像伺服器、網路還是電視本身。
 */
object PbReport {
    private const val TAG = "YTmatePb"
    private val q = ArrayList<JSONObject>()
    @Volatile private var lastFlush = 0L

    fun add(e: JSONObject) {
        val full: Boolean
        synchronized(q) { q.add(e); full = q.size >= 40 }
        if (full) flush()
    }

    /** 播放中定時呼叫：距離上次送超過 15 秒才送。 */
    fun tick() {
        if (System.currentTimeMillis() - lastFlush > 15_000) flush()
    }

    fun flush() {
        val batch: JSONArray
        synchronized(q) {
            if (q.isEmpty()) return
            batch = JSONArray(q)
            q.clear()
        }
        lastFlush = System.currentTimeMillis()
        thread(isDaemon = true, name = "pb-report") {
            try {
                val c = URL(BuildConfig.BASE_URL + "/api/playback/report").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 10000
                c.setRequestProperty("Content-Type", "application/json")
                val ck = CookieManager.getInstance().getCookie(BuildConfig.BASE_URL) ?: ""
                if (ck.isNotEmpty()) c.setRequestProperty("Cookie", ck)
                c.outputStream.use { it.write(JSONObject().put("events", batch).put("platform", "tv").toString().toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "report failed: $e")
            }
        }
    }
}
