package net.iucat.ytmate.mobile

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 播放核心（整個 App 共用一個 ExoPlayer）：
 *  - 頁面（/m）傳來播放清單 → load()；PlayerActivity 只是把畫面接到這個播放器上，離開畫面照樣播（背景播放）。
 *  - PlaybackService 用同一個播放器建立媒體工作階段 → 通知列／鎖定畫面／藍牙耳機按鍵都能控制。
 *  - 每支影片有好幾個版本（原檔／480p／mp3），播不了自動換下一個版本；🎧 只聽聲音＝改播 mp3（沒有 mp3 就關掉畫面軌）。
 *  - 看到哪裡每 10 秒回報伺服器（電視、網頁、其他手機共用）。
 */
object PlayerHub {
    private const val TAG = "YTmateMobile"

    data class Src(val url: String, val kind: String, val codec: String)
    data class Sub(val url: String, val lang: String)
    data class Item(val vid: String, val title: String, val artist: String, val thumb: String, val durMs: Long,
                    val resumeMs: Long, val sources: List<Src>, val subs: List<Sub>)

    private var player: ExoPlayer? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    val items = ArrayList<Item>()
    private var srcIdx = IntArray(0)
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var app: Context
    var listTitle = ""
    private var sleepAt = 0L

    fun player(ctx: Context): ExoPlayer {
        player?.let { return it }
        app = ctx.applicationContext
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("YTmateMobile/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
        // 每次開檔才讀 cookie（重新登入後 cookie 會換）
        val ds = ResolvingDataSource.Factory(http) { spec ->
            val ck = Api.cookie()
            if (ck.isNotEmpty() && spec.uri.toString().startsWith(Api.base)) spec.withAdditionalHeaders(mapOf("Cookie" to ck)) else spec
        }
        val p = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(app).setDataSourceFactory(ds))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)          // 拔耳機自動暫停
            .setWakeMode(C.WAKE_MODE_NETWORK)          // 鎖螢幕背景播放不中斷
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(30000)
            .build()
        p.addListener(object : Player.Listener {
            // 2026-10-30：🩺 播放狀況回報——開始等待、播到一半緩衝、跳轉等待、錯誤
            override fun onPlaybackStateChanged(state: Int) {
                val now = SystemClock.uptimeMillis()
                if (state == Player.STATE_BUFFERING && pbReady) {
                    if (now - pbLastSeek < 3000) { if (pbSeekAt == 0L) pbSeekAt = pbLastSeek }
                    else if (p.playWhenReady && pbStallAt == 0L && pbSeekAt == 0L) pbStallAt = now
                } else if (state == Player.STATE_READY) {
                    when {
                        pbStartAt > 0 && p.playWhenReady -> { pbEvent(p, "start", now - pbStartAt); pbStartAt = 0L }
                        pbSeekAt > 0 -> pbEvent(p, "seek", now - pbSeekAt)
                        pbStallAt > 0 && now - pbStallAt >= 300 -> pbEvent(p, "stall", now - pbStallAt)
                    }
                    pbSeekAt = 0L; pbStallAt = 0L; pbReady = true
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) pbLastSeek = SystemClock.uptimeMillis()
            }

            override fun onPlayerError(error: PlaybackException) {
                pbEvent(p, "error", 0, error.errorCodeName); pbFlush()
                val i = p.currentMediaItemIndex
                if (i in items.indices && srcIdx[i] < items[i].sources.size - 1) {     // 換下一個版本
                    srcIdx[i]++
                    Log.w(TAG, "播放失敗，改用版本 ${srcIdx[i]}：${error.errorCodeName}")
                    val pos = p.currentPosition
                    p.replaceMediaItem(i, build(i))
                    p.seekTo(i, pos); p.prepare(); p.play()
                } else if (i < p.mediaItemCount - 1) {
                    p.seekToNextMediaItem(); p.prepare(); p.play()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                pbStartAt = SystemClock.uptimeMillis(); pbReady = false; pbStallAt = 0L; pbSeekAt = 0L
                val prev = lastIdx
                if (prev in items.indices && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) report(prev, items[prev].durMs, true)
                lastIdx = p.currentMediaItemIndex
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) { reportNow(); pbFlush() }
            }
        })
        applyTrackPrefs(p)
        p.setPlaybackParameters(PlaybackParameters(Prefs.get(app, "speed", "1").toFloatOrNull() ?: 1f))
        player = p
        ui.postDelayed(tick, 10000)
        return p
    }

    private var lastIdx = -1

    // ══ 🩺 播放狀況回報（2026-10-30）→ 伺服器 /api/playback/report；送不出去就丟掉，不影響播放 ══
    private val pbQ = ArrayList<JSONObject>()
    private var pbStartAt = 0L
    private var pbSeekAt = 0L
    private var pbStallAt = 0L
    private var pbLastSeek = 0L
    private var pbReady = false
    @Volatile private var pbLastFlush = 0L

    private fun pbEvent(p: Player, kind: String, waitMs: Long, err: String = "") {
        val i = p.currentMediaItemIndex
        val it = items.getOrNull(i) ?: return
        val src = it.sources.getOrNull(srcIdx.getOrElse(i) { 0 })
        val url = src?.url ?: ""
        val q = when {
            src?.kind == "audio" -> "audio"
            url.contains(".low.") -> "low"
            url.contains(".burn.") -> "burn"
            else -> "orig"
        }
        val o = JSONObject().put("kind", kind).put("wait_ms", waitMs).put("video_id", it.vid).put("quality", q)
            .put("pos", p.currentPosition.coerceAtLeast(0L) / 1000.0)
            .put("buffered", (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L) / 1000.0)
        if (err.isNotEmpty()) o.put("error", err)
        synchronized(pbQ) { pbQ.add(o); if (pbQ.size > 200) pbQ.removeAt(0) }
    }

    private fun pbFlush() {
        val arr: JSONArray
        synchronized(pbQ) {
            if (pbQ.isEmpty()) return
            arr = JSONArray(pbQ); pbQ.clear()
        }
        pbLastFlush = System.currentTimeMillis()
        io.execute {
            try { Api.json("POST", "/api/playback/report", JSONObject().put("events", arr).put("platform", "phone")) }
            catch (e: Exception) { Log.w(TAG, "播放狀況回報失敗：$e") }
        }
    }

    /** 確保背景播放服務在跑（通知列、鎖定畫面控制）。 */
    fun connectService(ctx: Context) {
        if (controllerFuture != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(ctx, token).buildAsync()
    }

    /** 頁面傳來的清單：{items:[{video_id,title,artist,thumb,duration,resume,sources:[{url,kind,codec}],subs:[{url,lang}]}], start, startPos(ms), title} */
    fun load(ctx: Context, json: String): Boolean {
        val root = try { JSONObject(json) } catch (e: Exception) { return false }
        val arr = root.optJSONArray("items") ?: return false
        val parsed = ArrayList<Item>()
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            val srcs = ArrayList<Src>()
            o.optJSONArray("sources")?.let { sa ->
                for (j in 0 until sa.length()) {
                    val x = sa.getJSONObject(j)
                    if (x.optString("url").isNotEmpty()) srcs.add(Src(abs(x.getString("url")), x.optString("kind"), x.optString("codec")))
                }
            }
            if (srcs.isEmpty()) continue
            val subs = ArrayList<Sub>()
            o.optJSONArray("subs")?.let { sl ->
                for (j in 0 until sl.length()) {
                    val x = sl.getJSONObject(j)
                    if (x.optString("url").isNotEmpty()) subs.add(Sub(abs(x.getString("url")), x.optString("lang")))
                }
            }
            parsed.add(Item(o.optString("video_id"), o.optString("title"), o.optString("artist"), abs(o.optString("thumb")),
                (o.optDouble("duration", 0.0) * 1000).toLong(), (o.optDouble("resume", 0.0) * 1000).toLong(), srcs, subs))
        }
        if (parsed.isEmpty()) return false
        val p = player(ctx)
        reportNow()
        items.clear(); items.addAll(parsed)
        srcIdx = IntArray(items.size)
        listTitle = root.optString("title")
        val start = root.optInt("start", 0).coerceIn(0, items.size - 1)
        val startPos = root.optLong("startPos", 0L).takeIf { it > 0 } ?: items[start].resumeMs
        p.setMediaItems(items.indices.map { build(it) }, start, startPos)
        lastIdx = start
        p.prepare()
        p.play()
        connectService(ctx)
        return true
    }

    private fun abs(u: String): String = if (u.startsWith("/")) Api.base + u else u

    fun listening(): Boolean = Prefs.get(app, "listen") == "1"

    private fun build(i: Int): MediaItem {
        val it = items[i]
        val audio = it.sources.firstOrNull { s -> s.kind == "audio" }
        val src = if (listening() && audio != null) audio else it.sources[srcIdx[i].coerceIn(0, it.sources.size - 1)]
        val meta = MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist.ifEmpty { listTitle })
        if (it.thumb.isNotEmpty()) meta.setArtworkUri(Uri.parse(it.thumb))
        val b = MediaItem.Builder().setUri(src.url).setMediaId("$i:${src.kind}").setMediaMetadata(meta.build())
        if (it.subs.isNotEmpty()) {
            b.setSubtitleConfigurations(it.subs.map { x ->
                val zh = x.lang.startsWith("zh")
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(x.url))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(x.lang.substringBefore("-ai"))
                    .setLabel((if (zh) "中文字幕" else "English") + (if (x.lang.endsWith("-ai")) "（AI）" else ""))
                    .setId("sub:${x.lang}")
                    .build()
            })
        }
        return b.build()
    }

    /** 🎧 只聽聲音：有 mp3 就換成 mp3（省流量），沒有就只關掉畫面軌。從同一個位置接著播。 */
    fun setListen(ctx: Context, on: Boolean): String {
        Prefs.set(ctx, "listen", if (on) "1" else "0")
        val p = player ?: return if (on) "🎧 只聽聲音：開" else "🎧 只聽聲音：關"
        applyTrackPrefs(p)
        if (items.isNotEmpty() && p.mediaItemCount == items.size) {
            val cur = p.currentMediaItemIndex
            val pos = p.currentPosition
            val playing = p.playWhenReady
            for (i in items.indices) if (i != cur) p.replaceMediaItem(i, build(i))
            p.replaceMediaItem(cur, build(cur))
            p.seekTo(cur, pos)
            p.prepare()
            p.playWhenReady = playing
        }
        return if (on) "🎧 只聽聲音：開（畫面關閉、省流量）" else "🎬 播放影片"
    }

    /** 字幕偏好（zh／en／off）＋只聽聲音時關掉畫面軌。 */
    fun applyTrackPrefs(p: Player) {
        val subs = Prefs.get(app, "subs", "zh")
        val b = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subs == "off")
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, listening())
        if (subs != "off") b.setPreferredTextLanguage(if (subs == "en") "en" else "zh")
        p.trackSelectionParameters = b.build()
    }

    fun setSpeed(ctx: Context, speed: Float) {
        Prefs.set(ctx, "speed", speed.toString())
        player?.setPlaybackParameters(PlaybackParameters(speed))
    }

    /** 睡眠定時：幾分鐘後暫停（0＝取消）。 */
    fun setSleep(minutes: Int) {
        sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
    }

    fun sleepLeftMin(): Int = if (sleepAt > 0) ((sleepAt - System.currentTimeMillis()) / 60000 + 1).toInt().coerceAtLeast(0) else 0

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying) reportNow()
            if (System.currentTimeMillis() - pbLastFlush > 15_000) pbFlush()
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { sleepAt = 0; p?.pause() }
            ui.postDelayed(this, 10000)
        }
    }

    private fun reportNow() {
        val p = player ?: return
        val i = p.currentMediaItemIndex
        if (i !in items.indices || p.mediaItemCount != items.size) return
        val dur = if (p.duration > 0) p.duration else items[i].durMs
        report(i, p.currentPosition, dur > 0 && p.currentPosition >= dur - 20000)
    }

    private fun report(i: Int, posMs: Long, done: Boolean) {
        val it = items.getOrNull(i) ?: return
        if (it.vid.isEmpty() || (posMs < 10000 && !done)) return
        val dur = items[i].durMs
        io.execute {
            Api.json("POST", "/api/resume/${it.vid}", JSONObject().put("pos", posMs / 1000.0).put("dur", dur / 1000.0)
                .put("by", "phone").put("done", done))
        }
    }

    fun nowPlaying(): JSONObject {
        val p = player
        val o = JSONObject()
        if (p == null || p.mediaItemCount == 0 || items.isEmpty()) return o.put("active", false)
        val i = p.currentMediaItemIndex.coerceIn(0, items.size - 1)
        val it = items[i]
        return o.put("active", true).put("playing", p.isPlaying).put("paused", !p.playWhenReady)
            .put("title", it.title).put("artist", it.artist.ifEmpty { listTitle }).put("thumb", it.thumb).put("video_id", it.vid)
            .put("pos", p.currentPosition / 1000.0).put("dur", (if (p.duration > 0) p.duration else it.durMs) / 1000.0)
            .put("index", i).put("count", items.size).put("listen", listening())
            .put("speed", p.playbackParameters.speed.toDouble()).put("sleep_min", sleepLeftMin())
    }

    fun control(ctx: Context, action: String, value: String): String {
        val p = player ?: return "目前沒有在播放"
        when (action) {
            "toggle" -> if (p.playWhenReady) p.pause() else p.play()
            "play" -> p.play()
            "pause" -> p.pause()
            "next" -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() else return "已經是最後一支"
            "prev" -> p.seekToPreviousMediaItem()
            "seek" -> p.seekTo(p.currentPosition + ((value.toDoubleOrNull() ?: 0.0) * 1000).toLong())
            "seek_abs" -> p.seekTo(((value.toDoubleOrNull() ?: 0.0) * 1000).toLong())
            "listen" -> return setListen(ctx, value == "on" || (value != "off" && !listening()))
            "speed" -> setSpeed(ctx, value.toFloatOrNull() ?: 1f)
            "sleep" -> setSleep(value.toIntOrNull() ?: 0)
            "stop" -> { reportNow(); p.stop(); p.clearMediaItems(); items.clear() }
            else -> return "不認得的操作"
        }
        return "ok"
    }
}
