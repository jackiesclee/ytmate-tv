package net.iucat.ytmate.tv

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.json.JSONObject
import java.io.File

/**
 * 播放暫存（2026-10-17，v1.1）：邊播邊把影片存在 App 的暫存區，並預先下載下一支的開頭，切換時秒開、
 * 兩岸網路短暫卡住也不會停。依電視剩餘空間自動決定大小（使用者擔心舊電視空間不大）：
 *   上限 ＝ min(500MB, 電視剩餘空間的 10%)；剩餘空間不到 1GB 就不暫存（只邊播邊緩衝，不佔空間）。
 *   每次打開 App、每次開始播放都重新檢查；空間變少就自動刪最舊的暫存。滿了也是自動刪最舊的。
 *   設定頁可以關閉（Prefs cache=off），也可以「清除暫存」。系統空間不足時 Android 也會優先清 App 暫存。
 */
@OptIn(UnstableApi::class)
object MediaCache {
    private const val TAG = "YTmateTV"
    const val MAX_BYTES = 500L * 1024 * 1024
    // 剩餘空間低於這個就不暫存（自動測試的模擬器空間很小，測試版門檻較低）
    private val MIN_FREE = if (BuildConfig.E2E) 100L * 1024 * 1024 else 1024L * 1024 * 1024
    private var cache: SimpleCache? = null
    private var db: StandaloneDatabaseProvider? = null
    @Volatile var capBytes = 0L
        private set

    private fun dir(ctx: Context) = File(ctx.cacheDir, "media")

    fun freeBytes(ctx: Context): Long = try { StatFs(ctx.cacheDir.path).availableBytes } catch (_: Exception) { 0L }

    @Synchronized
    fun usedBytes(): Long = try { cache?.cacheSpace ?: 0L } catch (_: Exception) { 0L }

    /** 依目前的剩餘空間算上限（已經暫存的部分也算可用空間）。 */
    @Synchronized
    fun computeCap(ctx: Context): Long {
        if (Prefs.get(ctx, "cache") == "off") return 0L
        val free = freeBytes(ctx) + usedBytes()
        if (free < MIN_FREE) return 0L
        return minOf(MAX_BYTES, free / 10)
    }

    /** 開始播放時呼叫：回傳可用的暫存（不暫存時回傳 null，並清掉舊的暫存）。 */
    @Synchronized
    fun get(ctx: Context): SimpleCache? {
        val cap = computeCap(ctx)
        capBytes = cap
        if (cap <= 0L) {
            if (cache != null || dir(ctx).exists()) clear(ctx)
            Log.i(TAG, "cache off cap=0 free=${freeBytes(ctx)}")
            return null
        }
        var c = cache
        if (c == null) {
            try {
                val d = db ?: StandaloneDatabaseProvider(ctx.applicationContext).also { p -> db = p }
                c = SimpleCache(dir(ctx), LeastRecentlyUsedCacheEvictor(cap), d)
                cache = c
            } catch (e: Exception) {
                Log.w(TAG, "cache init failed: $e")
                return null
            }
        }
        trim(c, cap)
        Log.i(TAG, "cache on cap=$cap used=${usedBytes()} free=${freeBytes(ctx)}")
        return c
    }

    /** 超過上限 → 從最久沒用的開始刪（LRU 淘汰器的上限在建立時固定，空間變少時靠這裡）。 */
    private fun trim(c: SimpleCache, cap: Long) {
        try {
            if (c.cacheSpace <= cap) return
            val keys = c.keys.map { k -> k to (c.getCachedSpans(k).maxOfOrNull { s -> s.lastTouchTimestamp } ?: 0L) }.sortedBy { p -> p.second }
            for ((k, _) in keys) {
                if (c.cacheSpace <= cap) break
                c.removeResource(k)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache trim failed: $e")
        }
    }

    /** 清除暫存，回傳釋放了多少 bytes。 */
    @Synchronized
    fun clear(ctx: Context): Long {
        var freed = 0L
        val c = cache
        try {
            if (c != null) {
                freed = c.cacheSpace
                for (k in c.keys.toList()) c.removeResource(k)
            } else {
                val d = dir(ctx)
                if (d.exists()) {
                    freed = d.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }
                    val p = db ?: StandaloneDatabaseProvider(ctx.applicationContext).also { x -> db = x }
                    SimpleCache.delete(d, p)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache clear failed: $e")
        }
        Log.i(TAG, "cache cleared freed=$freed")
        return freed
    }

    fun info(ctx: Context): JSONObject = JSONObject()
        .put("mode", Prefs.get(ctx, "cache").ifEmpty { "auto" })
        .put("cap", computeCap(ctx))
        .put("used", usedBytes())
        .put("free", freeBytes(ctx))
}
