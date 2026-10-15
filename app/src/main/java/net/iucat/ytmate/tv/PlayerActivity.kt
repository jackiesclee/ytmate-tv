package net.iucat.ytmate.tv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 原生播放器（Media3 ExoPlayer）：電視版頁面按「播放」後全螢幕播放整個清單。
 *
 * 遙控器：
 *   確認鍵＝暫停／播放（控制列隱藏時）；左右＝倒轉／快轉 10 秒（按住不放會加速：30 秒、60 秒）；
 *   上下＝顯示控制列（可選上一支／下一支、字幕）；媒體鍵／頻道上下＝上一支／下一支；
 *   返回＝先收起控制列，再按一次回到頻道頁。
 * 媒體檔需要登入：沿用 WebView 的 session cookie 送出請求。
 * 只有聲音的項目（音樂）顯示縮圖＋標題；每支開始播放時左上角顯示標題 4 秒。播完整個清單自動回到頻道頁。
 *
 * 2026-10-15：每支影片附「多個版本」（伺服器依這台電視的解碼能力排好順序：原檔 → 480p 相容版 → 純聲音）。
 * 播放時如果發現影像軌這台電視解不了（例如舊電視遇到 AV1），自動換下一個版本、從同一個時間點繼續，
 * 不會再出現「只有聲音沒有畫面」。
 *
 * 2026-10-15（第二輪，參考 Media3 原始碼、Just Player〔moneytoo/Player〕與 Android TV 品質檢查表）：
 *   - 控制列一定會自動收起：關掉控制列的收起動畫（Media3 動畫途中再叫出控制列時，自動收起的計時會被取消，
 *     就是「按下一支後控制列一直不收」的原因）、開始播放時重新計時、再加一個保險（播放中、5 秒沒按鍵就收起）。
 *   - 螢幕常亮只在播放中（暫停時讓電視可以照常進入螢幕保護程式）。
 *   - 媒體工作階段（MediaSession）：系統的媒體鍵、藍牙遙控器、語音「暫停／繼續」都能控制。
 *   - 網路斷線自動重連（同一支、同一個時間點，最多 3 次），不會直接跳過。
 *   - 長影片記住看到哪裡，下次從那裡繼續。
 *   - 控制列出現時一併顯示標題；回到頻道頁時焦點停在最後播放的那一支。
 *   - 字幕預設自動開啟、英文優先（沒有英文就中文）；在設定頁或播放器的字幕選單改過之後，一直照新的設定（記在 App 裡）。
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    companion object {
        const val TAG = "YTmateTV"
        private const val HIDE_MS = 3500                 // 控制列幾毫秒沒操作就收起
        private const val WATCHDOG_IDLE_MS = 5000L       // 保險：播放中、這麼久沒按鍵，控制列還在就強制收起
        // 長影片記住播放位置：只記超過這個長度的影片（自動測試的影片只有 40 秒，所以測試版門檻較低）
        private val RESUME_MIN_MS = if (BuildConfig.E2E) 20_000L else 300_000L
        private const val NET_RETRIES = 3
        @Volatile
        var pending: String? = null      // MainActivity 傳來的播放清單 JSON（清單可能很長，不放 Intent）
        @Volatile
        var lastVideoId: String? = null  // 最後播放的影片（回到頻道頁時把焦點移到這一支）
    }

    private data class Src(val url: String, val kind: String, val codec: String)
    private data class Sub(val url: String, val lang: String)
    private data class Item(val sources: List<Src>, val title: String, val artist: String, val thumb: String, val note: String,
                            val subs: List<Sub>, val vid: String, val durMs: Long)

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private var session: MediaSession? = null
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
    private lateinit var netRetry: IntArray          // 每支影片網路重連了幾次
    private val noted = HashSet<Int>()
    private val resumed = HashSet<Int>()             // 這次已經套用過「從上次位置繼續」的影片
    private var lastKeyAt = 0L
    private var consumedKey = 0
    private var controllerVisible = false
    private var curIdx = -1                          // 目前這支（記錄播放位置用）
    private var curPos = 0L
    private var curDur = 0L
    private var subPref = "en"                       // 字幕設定：en／zh／off（跟頁面的設定同一份）
    private var applyingSubPref = false
    private var lastSubLog = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // 字幕：預設自動開啟、英文優先；照使用者上次的設定（控制列的字幕按鈕可以切換，切換後會記住）
        subPref = Prefs.get(this, "subs").ifEmpty { "en" }
        applySubPref()

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
            val subs = ArrayList<Sub>()
            val sl = o.optJSONArray("subs")
            if (sl != null) for (k in 0 until sl.length()) {
                val x = sl.getJSONObject(k)
                if (x.optString("url").isNotEmpty()) subs.add(Sub(x.getString("url"), x.optString("lang")))
            }
            o.optJSONObject("sub")?.let { x ->                // 舊版頁面只給一個字幕
                if (subs.isEmpty() && x.optString("url").isNotEmpty()) subs.add(Sub(x.getString("url"), x.optString("lang")))
            }
            items.add(Item(srcs, o.optString("title"), o.optString("artist"), o.optString("thumb"), o.optString("note"),
                subs, o.optString("video_id"),
                (o.optDouble("duration", 0.0) * 1000).toLong()))
        }
        if (items.isEmpty()) { finish(); return }
        srcIdx = IntArray(items.size)
        netRetry = IntArray(items.size)
        val mediaItems = ArrayList<MediaItem>()
        for (i in items.indices) mediaItems.add(buildItem(i))

        buildUi()
        view.player = player
        // 媒體工作階段：系統媒體鍵／藍牙遙控器／語音控制都送到這個播放器（Android TV 品質檢查表要求）
        try {
            // 縮圖也要登入：系統「正在播放」卡片的封面用同一個帶 cookie 的連線下載
            session = MediaSession.Builder(this, player).setId("ytmate-${SystemClock.uptimeMillis()}")
                .setBitmapLoader(DataSourceBitmapLoader(MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()), dataSource))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "media session failed: $e")
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                errorsInRow = 0
                val i = player.currentMediaItemIndex
                // 上一支：自然播完 → 清掉記錄的位置；中途換掉 → 記住看到哪裡
                if (curIdx in items.indices && curIdx != i) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) Resume.clear(this@PlayerActivity, items[curIdx].vid)
                    else Resume.save(this@PlayerActivity, items[curIdx].vid, curPos, curDur, RESUME_MIN_MS)
                }
                Log.i(TAG, "transition item=$i reason=$reason")
                if (i in items.indices) {
                    netRetry[i] = 0
                    if (curIdx != i && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
                        applyResume(i)
                    curIdx = i; curPos = 0L; curDur = 0L
                    lastVideoId = items[i].vid
                }
                showItem(i)
            }

            // 影像軌這台電視解不了（例如舊電視遇到 AV1）→ 換下一個版本
            override fun onTracksChanged(tracks: Tracks) {
                val i = player.currentMediaItemIndex
                val cur = items.getOrNull(i)?.sources?.getOrNull(srcIdx[i]) ?: return
                logSubtitle(tracks)
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

            // 使用者在控制列的字幕選單改了（關閉／換語言）→ 記住，之後每支都照這個
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                if (applyingSubPref) return
                val ov = parameters.overrides.values.firstOrNull { o -> o.type == C.TRACK_TYPE_TEXT && o.trackIndices.isNotEmpty() }
                // Media3 字幕選單：選某個字幕＝override；選「關閉」＝ignoredTextSelectionFlags（因為我們設了偏好語言，
                // 這樣其實關不掉）→ 一律改成真正關閉字幕軌
                val menuOff = ov == null && parameters.ignoredTextSelectionFlags != 0
                val newPref = when {
                    ov != null -> langGroup(ov.mediaTrackGroup.getFormat(ov.trackIndices[0]).language)
                    menuOff || parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) -> "off"
                    else -> subPref
                }
                if (newPref != subPref || menuOff) {
                    Log.i(TAG, "subtitle pref $subPref -> $newPref")
                    subPref = newPref
                    Prefs.set(this@PlayerActivity, "subs", newPref)
                    applySubPref(keepOverrides = ov != null)
                }
            }

            // 開始播放 → 控制列重新計時（Just Player 的做法）；螢幕常亮只在播放中
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(TAG, "playing=$isPlaying")
                updateKeepScreenOn()
                if (isPlaying && view.isControllerFullyVisible) {
                    view.controllerShowTimeoutMs = HIDE_MS
                    view.showController()                    // 播放中 → 重新排定 3.5 秒後收起
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                updateKeepScreenOn()
                if (state == Player.STATE_READY) {
                    val i = player.currentMediaItemIndex
                    if (i in items.indices) netRetry[i] = 0
                }
                if (state == Player.STATE_ENDED) {             // 整個清單播完 → 回到頻道頁
                    if (curIdx in items.indices) Resume.clear(this@PlayerActivity, items[curIdx].vid)
                    curIdx = -1
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val i = player.currentMediaItemIndex
                Log.w(TAG, "error item=$i src=${srcIdx.getOrNull(i)} ${error.errorCodeName}")
                // 網路斷線／逾時：同一支、同一個時間點重新連線（不換版本、不跳過）
                if (i in items.indices && isNetworkError(error.errorCode) && netRetry[i] < NET_RETRIES) {
                    netRetry[i]++
                    val n = netRetry[i]
                    Log.i(TAG, "net retry item=$i n=$n")
                    Toast.makeText(this@PlayerActivity, "網路不穩，重新連線中…（第 $n 次）", Toast.LENGTH_SHORT).show()
                    ui.postDelayed({
                        if (!isFinishing && player.currentMediaItemIndex == i) { player.prepare(); player.play() }
                    }, 2000L * n)
                    return
                }
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
        val s0 = start.coerceIn(0, mediaItems.size - 1)
        val startPos = Resume.get(this, items[s0].vid)
        player.setMediaItems(mediaItems, s0, startPos)
        player.prepare()
        player.playWhenReady = true
        curIdx = s0
        lastVideoId = items[s0].vid
        resumed.add(s0)
        if (startPos > 0) resumeToast(startPos)
        showItem(s0)
        ui.postDelayed(watchdog, 1000)
    }

    private fun langGroup(lang: String?): String = if ((lang ?: "").lowercase().startsWith("zh")) "zh" else "en"

    /** 套用字幕設定：off＝關閉；en／zh＝開啟、這個語言優先（沒有就用另一個語言）。 */
    private fun applySubPref(keepOverrides: Boolean = false) {
        applyingSubPref = true
        try {
            val b = player.trackSelectionParameters.buildUpon().setIgnoredTextSelectionFlags(0)
            if (subPref == "off") {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguages(*(if (subPref == "zh") arrayOf("zh", "en") else arrayOf("en", "zh")))
                if (!keepOverrides) b.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            }
            player.trackSelectionParameters = b.build()
        } finally {
            applyingSubPref = false
        }
    }

    /** 記錄目前顯示的字幕語言（自動測試檢查用）。 */
    private fun logSubtitle(tracks: Tracks) {
        var sel = "none"
        var any = false
        for (g in tracks.groups) if (g.type == C.TRACK_TYPE_TEXT) {
            any = true
            for (k in 0 until g.length) if (g.isTrackSelected(k)) sel = g.getTrackFormat(k).language ?: "und"
        }
        val line = "subtitle item=${player.currentMediaItemIndex} available=$any selected=$sel pref=$subPref"
        if (any && line != lastSubLog) { lastSubLog = line; Log.i(TAG, line) }
    }

    private fun isNetworkError(code: Int): Boolean =
        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED

    /** 換到一支「之前看到一半」的長影片 → 從那裡繼續。 */
    private fun applyResume(i: Int) {
        if (!resumed.add(i)) return
        val pos = Resume.get(this, items[i].vid)
        if (pos > 0) {
            player.seekTo(i, pos)
            resumeToast(pos)
        }
    }

    private fun resumeToast(pos: Long) {
        Log.i(TAG, "resume from=$pos")
        Toast.makeText(this, "從上次看到的 ${fmt(pos / 1000)} 繼續播放（按左鍵倒轉）", Toast.LENGTH_LONG).show()
    }

    private fun updateKeepScreenOn() {
        if (!::view.isInitialized) return
        val st = player.playbackState
        view.keepScreenOn = player.playWhenReady && (st == Player.STATE_READY || st == Player.STATE_BUFFERING)
    }

    /**
     * 保險：每秒檢查一次。播放中、控制列還開著、5 秒沒按鍵、而且沒有開著字幕選單（選單會搶走視窗焦點）→ 收起控制列。
     * 同時記下目前播放位置（換支或離開時記住長影片看到哪裡）。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (isFinishing) return
            if (player.currentMediaItemIndex == curIdx && player.playbackState == Player.STATE_READY) {
                curPos = player.currentPosition
                curDur = player.duration.takeIf { d -> d > 0 } ?: items.getOrNull(curIdx)?.durMs ?: 0L
            }
            // 自動測試版：每秒記一次控制列狀態（測試程式據此判斷控制列有沒有收起）
            if (BuildConfig.E2E) Log.i(TAG, "controller visible=${view.isControllerFullyVisible} playing=${player.isPlaying} tick")
            if (view.isControllerFullyVisible && player.isPlaying && hasWindowFocus() &&
                SystemClock.uptimeMillis() - lastKeyAt > WATCHDOG_IDLE_MS) {
                Log.i(TAG, "controller watchdog hide")
                view.hideController()
            }
            ui.postDelayed(this, 1000)
        }
    }

    private fun buildItem(i: Int): MediaItem {
        val it = items[i]
        val src = it.sources[srcIdx[i]]
        val meta = MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist)
        if (it.thumb.isNotEmpty()) meta.setArtworkUri(Uri.parse(it.thumb))
        val b = MediaItem.Builder().setUri(src.url).setMediaId("$i:${srcIdx[i]}").setMediaMetadata(meta.build())
        if (it.subs.isNotEmpty() && src.kind != "audio") {
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
        view.controllerShowTimeoutMs = HIDE_MS
        // 關掉控制列的收起／出現動畫：Media3 在「出現動畫途中」再被叫出時不會重新排定自動收起（控制列就一直開著），
        // 關掉動畫後每次顯示都一定會重新計時；舊電視也比較不卡。
        view.setControllerAnimationEnabled(false)
        view.useArtwork = false
        view.keepScreenOn = false                       // 由 updateKeepScreenOn() 依播放狀態切換
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            controllerVisible = v == View.VISIBLE
            Log.i(TAG, "controller visible=$controllerVisible playing=${if (::player.isInitialized) player.isPlaying else false}")
            onControllerVisibility(controllerVisible)
        })
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

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

        // 標題（左上角，每支開始時顯示 4 秒；控制列開著時也顯示）
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

    private fun isAudio(i: Int): Boolean = items.getOrNull(i)?.let { it.sources[srcIdx[i]].kind == "audio" } ?: false

    /** 控制列出現 → 標題一起出現（看得到在播哪一支）；控制列收起 → 影片的標題也收起。 */
    private fun onControllerVisibility(visible: Boolean) {
        if (!::titleBox.isInitialized || !::player.isInitialized) return
        val i = player.currentMediaItemIndex
        val it = items.getOrNull(i) ?: return
        ui.removeCallbacks(hideTitle)
        if (visible) {
            titleText.text = it.title
            subText.text = listOf(it.artist, "${i + 1} / ${items.size}").filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
            titleBox.animate().cancel(); titleBox.alpha = 1f
        } else if (!isAudio(i)) {
            ui.postDelayed(hideTitle, 300)
        }
    }

    private fun showItem(i: Int) {
        val it = items.getOrNull(i) ?: return
        val audio = isAudio(i)
        if (it.note.isNotEmpty() && noted.add(i)) Toast.makeText(this, it.note, Toast.LENGTH_LONG).show()
        titleText.text = it.title
        subText.text = listOf(it.artist, "${i + 1} / ${items.size}").filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
        titleBox.animate().cancel(); titleBox.alpha = 1f
        ui.removeCallbacks(hideTitle)
        if (!audio && !controllerVisible) ui.postDelayed(hideTitle, 4000)       // 音樂一直顯示標題；影片 4 秒後隱藏
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
        lastKeyAt = SystemClock.uptimeMillis()
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) Log.i(TAG, "key ${KeyEvent.keyCodeToString(code)} shown=$controllerShown focus=${focusName()}")
            // 按住不放：確認鍵只算一次；左右鍵繼續快轉（就算控制列中途出現也一樣）
            if (event.repeatCount > 0 && code == consumedKey) {
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) seekBy(+seekStep(event.repeatCount))
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) seekBy(-seekStep(event.repeatCount))
                return true
            }
            when (code) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP -> { player.seekToPreviousMediaItem(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { togglePlay(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); view.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekForward(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBack(); return true }
                KeyEvent.KEYCODE_MENU -> { if (controllerShown) view.hideController() else view.showController(); return true }
            }
            if (!controllerShown) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.repeatCount == 0) togglePlay(); consumedKey = code; return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(+seekStep(event.repeatCount)); consumedKey = code; return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-seekStep(event.repeatCount)); consumedKey = code; return true }
                }
            }
            consumedKey = 0
        }
        if (event.action == KeyEvent.ACTION_UP && code == consumedKey && code != KeyEvent.KEYCODE_BACK) {
            consumedKey = 0
            return true          // 按下時已經處理過的鍵：放開時不要再交給控制列（免得暫停後又被當成按了播放鍵）
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (controllerShown) view.hideController() else finish()
            }
            return true
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    /** 按住左右鍵不放會加速：前 3 下 10 秒、之後 30 秒、再久 60 秒（長影片找段落比較快）。 */
    private fun seekStep(repeat: Int): Long = when {
        repeat < 3 -> 10_000L
        repeat < 12 -> 30_000L
        else -> 60_000L
    }

    private fun seekBy(delta: Long) {
        val dur = player.duration
        var target = player.currentPosition + delta
        if (dur > 0) target = target.coerceAtMost(dur - 1000)
        player.seekTo(target.coerceAtLeast(0L))
        flashTitle(target.coerceAtLeast(0L))
    }

    private fun focusName(): String = try {
        currentFocus?.let { f -> if (f.id != View.NO_ID) resources.getResourceEntryName(f.id) else f.javaClass.simpleName } ?: "none"
    } catch (_: Exception) { "?" }

    private fun togglePlay() {
        if (player.isPlaying) { player.pause(); view.showController() } else player.play()
    }

    /** 快轉／倒轉時短暫顯示標題與目前時間 */
    private fun flashTitle(posMs: Long = player.currentPosition) {
        val pos = posMs / 1000
        val dur = (player.duration.takeIf { d -> d > 0 } ?: 0L) / 1000
        val i = player.currentMediaItemIndex
        val it = items.getOrNull(i) ?: return
        subText.text = "${fmt(pos)} / ${fmt(dur)}  ·  ${i + 1} / ${items.size}"
        titleText.text = it.title
        titleBox.animate().cancel(); titleBox.alpha = 1f
        ui.removeCallbacks(hideTitle)
        if (!isAudio(i)) ui.postDelayed(hideTitle, 2500)
    }

    private fun fmt(s: Long): String {
        val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
    }

    /** 記住目前這支看到哪裡（離開播放器、換支時呼叫）。 */
    private fun saveCurrent() {
        if (!::player.isInitialized || curIdx !in items.indices) return
        if (player.currentMediaItemIndex == curIdx) {
            curPos = player.currentPosition
            curDur = player.duration.takeIf { d -> d > 0 } ?: items[curIdx].durMs
        }
        Resume.save(this, items[curIdx].vid, curPos, curDur, RESUME_MIN_MS)
        lastVideoId = items[curIdx].vid
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            saveCurrent()
            player.pause()     // 按首頁鍵離開時暫停（回來可以繼續）
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}

/** 長影片的播放位置（App 私有空間，最多記 200 支，舊的自動淘汰）。 */
object Resume {
    private const val FILE = "ytmate_resume"
    private const val MAX = 200

    fun get(ctx: Context, vid: String): Long {
        if (vid.isEmpty()) return 0L
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(vid, null)
            ?.substringBefore(',')?.toLongOrNull() ?: 0L
    }

    /** 看了 10 秒以上、還沒看到最後 20 秒、而且影片夠長才記；否則清掉。 */
    fun save(ctx: Context, vid: String, pos: Long, dur: Long, minDur: Long) {
        if (vid.isEmpty()) return
        if (dur < minDur || pos < 10_000L || pos > dur - 20_000L) { clear(ctx, vid); return }
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val e = sp.edit().putString(vid, "$pos,${System.currentTimeMillis()}")
        val all = sp.all
        if (all.size > MAX) {
            all.entries.sortedBy { (it.value as? String)?.substringAfter(',')?.toLongOrNull() ?: 0L }
                .take(all.size - MAX).forEach { e.remove(it.key) }
        }
        e.apply()
        Log.i(PlayerActivity.TAG, "resume save vid=$vid pos=$pos")
    }

    fun clear(ctx: Context, vid: String) {
        if (vid.isEmpty()) return
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(vid).apply()
    }
}
