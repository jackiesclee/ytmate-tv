package net.iucat.ytmate.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 原生播放器（Media3 ExoPlayer）：電視版頁面按「播放」後全螢幕播放整個清單。
 *
 * 遙控器：
 *   確認鍵＝暫停／播放（控制列隱藏時）；左右＝倒轉／快轉 10 秒；
 *   上下＝顯示控制列（可選上一支／下一支、字幕）；媒體鍵／頻道上下＝上一支／下一支；
 *   返回＝先收起控制列，再按一次回到頻道頁。
 * 媒體檔需要登入：沿用 WebView 的 session cookie 送出請求。
 * 只有聲音的項目（音樂）顯示縮圖＋標題；每支開始播放時左上角顯示標題 4 秒。播完整個清單自動回到頻道頁。
 *
 * 2026-10-15：每支影片附「多個版本」（伺服器依這台電視的解碼能力排好順序：原檔 → 480p 相容版 → 純聲音）。
 * 播放時如果發現影像軌這台電視解不了（例如舊電視遇到 AV1），自動換下一個版本、從同一個時間點繼續，
 * 不會再出現「只有聲音沒有畫面」。
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    companion object {
        const val TAG = "YTmateTV"
        @Volatile
        var pending: String? = null      // MainActivity 傳來的播放清單 JSON（清單可能很長，不放 Intent）
    }

    private data class Src(val url: String, val kind: String, val codec: String)
    private data class Item(val sources: List<Src>, val title: String, val artist: String, val thumb: String, val note: String,
                            val subUrl: String, val subLang: String)

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var artBg: ImageView
    private lateinit var art: ImageView
    private lateinit var titleBox: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private val items = ArrayList<Item>()
    private val ui = Handler(Looper.getMainLooper())
    private var cookie: String = ""
    private var errorsInRow = 0
    private lateinit var srcIdx: IntArray            // 每支影片目前用第幾個版本
    private val noted = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 播放時螢幕不關閉
        val json = pending
        if (json == null) { finish(); return }

        val root = JSONObject(json)
        val arr = root.getJSONArray("items")
        val start = root.optInt("start", 0)
        cookie = CookieManager.getInstance().getCookie(BuildConfig.BASE_URL) ?: ""

        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("YTmateTV/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setDefaultRequestProperties(if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap())

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
        // 字幕預設關閉，控制列的字幕按鈕可以打開
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val srcs = ArrayList<Src>()
            val sa = o.optJSONArray("sources")
            if (sa != null) for (k in 0 until sa.length()) {
                val x = sa.getJSONObject(k)
                if (x.optString("url").isNotEmpty()) srcs.add(Src(x.getString("url"), x.optString("kind"), x.optString("codec")))
            }
            if (srcs.isEmpty() && o.optString("url").isNotEmpty())        // 舊版頁面（只有單一網址）也能播
                srcs.add(Src(o.getString("url"), if (o.optBoolean("audio")) "audio" else "original", ""))
            if (srcs.isEmpty()) continue
            val sub = o.optJSONObject("sub")
            items.add(Item(srcs, o.optString("title"), o.optString("artist"), o.optString("thumb"), o.optString("note"),
                sub?.optString("url") ?: "", sub?.optString("lang") ?: ""))
        }
        if (items.isEmpty()) { finish(); return }
        srcIdx = IntArray(items.size)
        val mediaItems = ArrayList<MediaItem>()
        for (i in items.indices) mediaItems.add(buildItem(i))

        buildUi()
        view.player = player
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                errorsInRow = 0
                Log.i(TAG, "transition item=${player.currentMediaItemIndex} reason=$reason")
                showItem(player.currentMediaItemIndex)
            }

            // 影像軌這台電視解不了（例如舊電視遇到 AV1）→ 換下一個版本
            override fun onTracksChanged(tracks: Tracks) {
                val i = player.currentMediaItemIndex
                val cur = items.getOrNull(i)?.sources?.getOrNull(srcIdx[i]) ?: return
                if (cur.kind == "audio") return
                var hasVideo = false
                var videoOk = false
                var mime = ""
                for (g in tracks.groups) {
                    if (g.type == C.TRACK_TYPE_VIDEO) {
                        hasVideo = true
                        if (g.length > 0) mime = g.getTrackFormat(0).sampleMimeType ?: ""
                        // allowExceedsCapabilities=true：解碼器存在、只是宣告的規格（profile／level）較保守時照樣播放
                        // （Android 7 的軟體解碼器常這樣，實際上播得動）；真的播不了會走 onPlayerError 換版本。
                        // 只有「完全沒有這種格式的解碼器」（例如舊電視遇到 AV1）才在這裡直接換。
                        if (g.isSupported(true)) videoOk = true
                    }
                }
                // 換版本後，播放器可能還會再回報一次「上一個版本」的影像軌：格式對不上目前版本就忽略
                if (hasVideo && cur.codec.isNotEmpty() && mime.isNotEmpty() && !mimeMatches(cur.codec, mime)) {
                    Log.i(TAG, "tracks item=$i stale mime=$mime (src=${cur.kind}/${cur.codec}) ignored")
                    return
                }
                Log.i(TAG, "tracks item=$i src=${cur.kind} codec=${cur.codec} mime=$mime hasVideo=$hasVideo videoOk=$videoOk")
                if (hasVideo && !videoOk) fallback(i, "這台電視解不了原檔畫面，改用相容版本")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) finish()     // 整個清單播完 → 回到頻道頁
            }

            override fun onPlayerError(error: PlaybackException) {
                val i = player.currentMediaItemIndex
                Log.w(TAG, "error item=$i src=${srcIdx.getOrNull(i)} ${error.errorCodeName}")
                if (i in items.indices && srcIdx[i] < items[i].sources.size - 1) {   // 還有其他版本 → 先換版本
                    fallback(i, "這個版本無法播放，改用相容版本")
                    return
                }
                errorsInRow++
                if (errorsInRow >= 3 || !player.hasNextMediaItem()) {
                    Toast.makeText(this@PlayerActivity, "無法播放（${error.errorCodeName}），請確認網路或稍後再試", Toast.LENGTH_LONG).show()
                    finish(); return
                }
                Toast.makeText(this@PlayerActivity, "這支無法播放，跳到下一支", Toast.LENGTH_SHORT).show()
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        })
        player.setMediaItems(mediaItems, start.coerceIn(0, mediaItems.size - 1), 0L)
        player.prepare()
        player.playWhenReady = true
        showItem(start.coerceIn(0, mediaItems.size - 1))
    }

    private fun buildItem(i: Int): MediaItem {
        val it = items[i]
        val src = it.sources[srcIdx[i]]
        val meta = MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist)
        if (it.thumb.isNotEmpty()) meta.setArtworkUri(Uri.parse(it.thumb))
        val b = MediaItem.Builder().setUri(src.url).setMediaId("$i:${srcIdx[i]}").setMediaMetadata(meta.build())
        if (it.subUrl.isNotEmpty() && src.kind != "audio") {
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.subUrl))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(it.subLang.substringBefore("-ai"))
                    .setLabel(if (it.subLang.startsWith("zh")) "中文字幕" else "English")
                    .build()))
        }
        Log.i(TAG, "item=$i use src=${src.kind} codec=${src.codec} url=${src.url}")
        return b.build()
    }

    private fun mimeMatches(codec: String, mime: String): Boolean {
        val m = mime.lowercase()
        return when (codec) {
            "h264" -> m.contains("avc")
            "hevc" -> m.contains("hevc")
            "vp9" -> m.contains("vp9")
            "av1" -> m.contains("av01") || m.contains("av1")
            else -> true
        }
    }

    /** 換成這支影片的下一個版本，從同一個時間點繼續播。 */
    private fun fallback(i: Int, msg: String) {
        if (i !in items.indices || srcIdx[i] >= items[i].sources.size - 1) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        srcIdx[i]++
        val next = items[i].sources[srcIdx[i]]
        Log.i(TAG, "fallback item=$i -> ${next.kind}")
        Toast.makeText(this, if (next.kind == "audio") "這台電視解不了這支影片的畫面，先播放聲音（相容版轉檔中）" else msg, Toast.LENGTH_LONG).show()
        player.replaceMediaItem(i, buildItem(i))
        player.seekTo(i, pos)
        player.prepare()
        player.play()
        showItem(i)
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        view = PlayerView(this)
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        view.setShowSubtitleButton(true)
        view.setShowNextButton(true)
        view.setShowPreviousButton(true)
        view.setShowFastForwardButton(true)
        view.setShowRewindButton(true)
        view.controllerShowTimeoutMs = 3500
        view.useArtwork = false
        view.keepScreenOn = true
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            Log.i(TAG, "controller visible=${v == View.VISIBLE} playing=${if (::player.isInitialized) player.isPlaying else false}")
        })

        // 音樂（只有聲音）：模糊背景＋置中縮圖，疊在影片層與控制列之間
        val overlay = view.overlayFrameLayout ?: root
        artBg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.28f; visibility = View.GONE }
        overlay.addView(artBg, FrameLayout.LayoutParams(-1, -1))
        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.parseColor("#171724")) }
        }
        overlay.addView(art, FrameLayout.LayoutParams(dp(448f), dp(252f), Gravity.CENTER))

        // 標題（左上角，每支開始時顯示 4 秒）
        titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40f), dp(28f), dp(40f), dp(48f))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT))
        }
        titleText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
        subText = TextView(this).apply { setTextColor(Color.parseColor("#C8C8D8")); textSize = 14f }
        titleBox.addView(titleText); titleBox.addView(subText)
        overlay.addView(titleBox, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
    }

    private val hideTitle = Runnable { titleBox.animate().alpha(0f).setDuration(400).start() }

    private fun showItem(i: Int) {
        val it = items.getOrNull(i) ?: return
        val audio = it.sources[srcIdx[i]].kind == "audio"
        if (it.note.isNotEmpty() && noted.add(i)) Toast.makeText(this, it.note, Toast.LENGTH_LONG).show()
        titleText.text = it.title
        subText.text = listOf(it.artist, "${i + 1} / ${items.size}").filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
        titleBox.animate().cancel(); titleBox.alpha = 1f
        ui.removeCallbacks(hideTitle)
        if (!audio) ui.postDelayed(hideTitle, 4000)       // 音樂一直顯示標題；影片 4 秒後隱藏
        if (audio) {
            art.setImageDrawable(null); artBg.setImageDrawable(null)
            art.visibility = View.VISIBLE; artBg.visibility = View.VISIBLE
            if (it.thumb.isNotEmpty()) loadImage(it.thumb) { bmp -> if (player.currentMediaItemIndex == i) { art.setImageBitmap(bmp); artBg.setImageBitmap(bmp) } }
        } else {
            art.visibility = View.GONE; artBg.visibility = View.GONE
        }
    }

    /** 縮圖也需要登入：帶 cookie 下載，背景執行緒解碼後回主執行緒顯示。 */
    private fun loadImage(url: String, done: (Bitmap) -> Unit) {
        thread(isDaemon = true) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
                c.inputStream.use { s -> BitmapFactory.decodeStream(s) }?.let { bmp -> ui.post { done(bmp) } }
                c.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val controllerShown = view.isControllerFullyVisible
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.i(TAG, "key ${KeyEvent.keyCodeToString(code)} shown=$controllerShown focus=${focusName()}")
            when (code) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP -> { player.seekToPreviousMediaItem(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { togglePlay(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); view.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekForward(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBack(); return true }
            }
            if (!controllerShown) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { togglePlay(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { player.seekForward(); flashTitle(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { player.seekBack(); flashTitle(); return true }
                }
            }
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (controllerShown) view.hideController() else finish()
            }
            return true
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun focusName(): String = try { currentFocus?.let { f -> if (f.id != View.NO_ID) resources.getResourceEntryName(f.id) else f.javaClass.simpleName } ?: "none" } catch (_: Exception) { "?" }

    private fun togglePlay() {
        if (player.isPlaying) { player.pause(); view.showController() } else player.play()
    }

    /** 快轉／倒轉時短暫顯示標題與目前時間 */
    private fun flashTitle() {
        val pos = player.currentPosition / 1000
        val dur = (player.duration.takeIf { d -> d > 0 } ?: 0L) / 1000
        val i = player.currentMediaItemIndex
        val it = items.getOrNull(i) ?: return
        subText.text = "${fmt(pos)} / ${fmt(dur)}  ·  ${i + 1} / ${items.size}"
        titleText.text = it.title
        titleBox.animate().cancel(); titleBox.alpha = 1f
        ui.removeCallbacks(hideTitle)
        if (it.sources[srcIdx[i]].kind != "audio") ui.postDelayed(hideTitle, 2500)
    }

    private fun fmt(s: Long): String {
        val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()     // 按首頁鍵離開時暫停（回來可以繼續）
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
