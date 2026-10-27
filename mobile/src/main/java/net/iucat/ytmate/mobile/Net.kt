package net.iucat.ytmate.mobile

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** App 私有設定（裝置金鑰、播放偏好）。其他 App 讀不到；移除 App 就清掉。 */
object Prefs {
    private const val FILE = "ytmate_mobile"
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String, def: String = ""): String = sp(ctx).getString(key, def) ?: def
    fun set(ctx: Context, key: String, value: String) { sp(ctx).edit().putString(key, value).apply() }
    fun token(ctx: Context): String = get(ctx, "device_token")
    fun setToken(ctx: Context, t: String) { sp(ctx).edit().putString("device_token", t).apply() }
    fun clear(ctx: Context) { sp(ctx).edit().clear().apply() }
}

/** 跟 YTmate 伺服器溝通（帶登入 cookie；cookie 過期時用裝置金鑰自動重新登入一次）。 */
object Api {
    val base: String = BuildConfig.BASE_URL
    lateinit var app: Context

    data class Resp(val code: Int, val body: String)

    fun cookie(): String = try { CookieManager.getInstance().getCookie(base) ?: "" } catch (_: Exception) { "" }

    fun relogin(): Boolean = reloginCode() == 200

    /** 200＝成功、401＝金鑰被取消、-1＝連不到伺服器。 */
    @Synchronized
    fun reloginCode(): Int {
        val tok = Prefs.token(app)
        if (tok.isEmpty()) return 401
        val r = raw("POST", "/api/tv/login", JSONObject().put("token", tok).toString().toByteArray(), "application/json", saveCookies = true)
        return r.code
    }

    fun request(method: String, path: String, body: ByteArray? = null, ctype: String? = null, timeoutMs: Int = 30000): Resp {
        var r = raw(method, path, body, ctype, timeoutMs = timeoutMs)
        if (r.code == 401 && relogin()) r = raw(method, path, body, ctype, timeoutMs = timeoutMs)
        return r
    }

    fun json(method: String, path: String, obj: JSONObject? = null): Resp =
        request(method, path, obj?.toString()?.toByteArray(), if (obj != null) "application/json" else null)

    private fun raw(method: String, path: String, body: ByteArray?, ctype: String?, saveCookies: Boolean = false,
                    timeoutMs: Int = 30000): Resp {
        return try {
            val c = URL(base + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "YTmateMobile/${BuildConfig.VERSION_NAME}")
            if (!saveCookies) cookie().takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Cookie", it) }
            if (ctype != null) c.setRequestProperty("Content-Type", ctype)
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (saveCookies) {
                val cm = CookieManager.getInstance()
                c.headerFields?.get("Set-Cookie")?.forEach { cm.setCookie(base, it) }
                cm.flush()
            }
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val out = ByteArrayOutputStream()
            stream?.use { it.copyTo(out) }
            c.disconnect()
            Resp(code, out.toString("UTF-8"))
        } catch (e: Exception) {
            Resp(-1, e.message ?: "連不到伺服器")
        }
    }
}
