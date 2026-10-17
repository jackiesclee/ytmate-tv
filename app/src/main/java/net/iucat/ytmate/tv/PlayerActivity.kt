package net.iucat.ytmate.tv

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.widget.ScrollView
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.MoreExecutors
import java.lang.ref.WeakReference
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
 *   上＝顯示控制列（可選上一支／下一支、字幕）；下＝字幕開／關；媒體鍵／頻道上下＝上一支／下一支；
 *   選單鍵＝顯示控制列＋這支影片的中文摘要；返回＝先收起控制列，再按一次回到頻道頁。
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
 *
 * 2026-10-16：
 *   - 控制列收起時按「下」＝字幕開／關（記住設定；再打開時回到上次的語言）。
 *   - 按左右快轉／倒轉時，畫面下方顯示時間軸（目前時間／總長度／這次跳了多少）；
 *     放開按鍵、恢復正常播放後時間軸自動消失。
 *
 * 2026-10-17（v1.1）：
 *   - 📱 手機遙控：播放、暫停、快轉、上一支／下一支、停止、字幕、畫質、音量（Remote 轉交 remoteControl()）；
 *     播放器開著時手機送來新的影片 → 同一個播放器換清單（onNewIntent），不用重開。
 *   - 看到哪裡：所有影片都記（音樂除外），並存到伺服器（電視、網頁、其他電視共用；取比較新的那一份）。
 *   - 快播完時右下角「5 秒後播放：下一支」：確認＝立刻播，返回＝取消（停在這支的結尾）。
 *   - 整份清單播完 → 問頁面有沒有待播清單（手機排的），有就接著播。
 *   - 章節：時間軸上顯示分段點，快轉時顯示目前章節名稱（伺服器提供 YouTube 章節）。
 *   - 選單鍵叫出控制列時，右邊顯示這支影片的中文摘要。
 *   - 右上角提示（新一集做好、同步到新影片）。
 *   - 播放時加速下載：緩衝往前最多 5 分鐘；播放暫存（依電視剩餘空間自動，最多 500MB，見 MediaCache）；
 *     目前這支緩衝夠了就在背景先下載下一支的開頭（約 3 分鐘），切換時秒開。
 *   - v1.1.2：長按確認鍵＝打開右邊的「接下來播放」（這份清單接下來的影片＋待播清單）：
 *     上下選擇、確認＝播放這支、長按確認＝刪除、返回＝關閉。短按確認還是暫停／播放（放開時才決定）。
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    companion object {
        const val TAG = "YTmateTV"
        private const val HIDE_MS = 3500                 // 控制列幾毫秒沒操作就收起
        private const val WATCHDOG_IDLE_MS = 5000L       // 保險：播放中、這麼久沒按鍵，控制列還在就強制收起
        // 看到哪裡：2026-10-17 跟使用者確認改成所有影片都記（音樂除外）；看了 10 秒以上、還沒到最後 20 秒才記
        private const val RESUME_MIN_MS = 0L
        private const val NET_RETRIES = 3
        // 「N 秒後播放下一支」：影片要夠長才顯示（自動測試的影片只有 40 秒，測試版門檻較低）
        private val COUNTDOWN_MIN_DUR = if (BuildConfig.E2E) 20_000L else 60_000L
        private const val COUNTDOWN_MS = 5_000L
        // 2026-10-17（v1.1.1，使用者回報播放沒以前順）：預先下載改成更保守——只抓下一支的前 90 秒、
        // 目前這支要已經整支緩衝好（或往前 3 分鐘以上）、播放 15 秒以上才開始；一卡頓就停止預先下載。
        private const val PREFETCH_SEC = 90L
        private const val PREFETCH_AFTER_MS = 15_000L
        private const val LONG_PRESS_MS = 600L           // 按住確認鍵超過這麼久（或收到重複按下）＝長按
        @Volatile
        var pending: String? = null      // MainActivity 傳來的播放清單 JSON（清單可能很長，不放 Intent）
        @Volatile
        var lastVideoId: String? = null  // 最後播放的影片（回到頻道頁時把焦點移到這一支）
        private var current: WeakReference<PlayerActivity>? = null

        /** 頁面查過了：沒有待播清單 → 回到頻道頁。 */
        fun onQueueEmpty() { current?.get()?.queueDone() }
    }

    private data class Src(val url: String, val kind: String, val codec: String)
    private data class Sub(val url: String, val lang: String)
    private data class Item(val sources: List<Src>, val title: String, val artist: String, val thumb: String, val note: String,
                            val subs: List<Sub>, val vid: String, val durMs: Long, val resumeMs: Long, val resumeAtMs: Long)

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
    private var srcIdx = IntArray(0)                 // 每支影片目前用第幾個版本
    private var netRetry = IntArray(0)               // 每支影片網路重連了幾次
    private val noted = HashSet<Int>()
    private val resumed = HashSet<Int>()             // 這次已經套用過「從上次位置繼續」的影片
    private var lastKeyAt = 0L
    private var playingSince = 0L
    private var consumedKey = 0
    // 快轉／倒轉時的時間軸
    private lateinit var seekBox: LinearLayout
    private lateinit var seekTrack: FrameLayout
    private lateinit var seekBuffered: View
    private lateinit var seekPlayed: View
    private lateinit var seekThumb: View
    private lateinit var seekTicks: TickView
    private lateinit var seekTime: TextView
    private lateinit var seekDur: TextView
    private lateinit var seekDeltaText: TextView
    private lateinit var osd: TextView
    private var seekShown = false
    private var seekReleasedAt = 0L                  // 0＝還按著
    private var seekAccum = 0L                       // 這次按住累積跳了多少
    private var seekTarget = -1L
    private var controllerVisible = false
    private var curIdx = -1                          // 目前這支（記錄播放位置用）
    private var curPos = 0L
    private var curDur = 0L
    private var subPref = "en"                       // 字幕設定：en／zh／off（跟頁面的設定同一份）
    private var applyingSubPref = false
    private var lastSubLog = ""
    // 2026-10-17
    private lateinit var nextCard: LinearLayout
    private lateinit var nextTitle: TextView
    private lateinit var summaryBox: TextView
    private lateinit var noticeBox: LinearLayout
    private lateinit var noticeImg: ImageView
    private lateinit var noticeText: TextView
    private var countdownShown = false
    // 「接下來播放」面板（長按確認鍵打開）
    private data class UpEntry(val kind: String, val vid: String, val title: String)   // kind: list（這份清單）／queue（待播清單）
    private lateinit var upPanel: LinearLayout
    private lateinit var upScroll: ScrollView
    private lateinit var upList: LinearLayout
    private val upEntries = ArrayList<UpEntry>()
    private val upRows = ArrayList<TextView>()
    private var upSel = 0
    private var upShown = false
    private var upQueueLoaded = false
    private var okDownAt = 0L
    private var okLongDone = false
    private var cancelledIdx = -1
    private var menuOpened = false
    private var waitingQueue = false
    private var queueAsked = false
    private var lastResumeSync = 0L
    private var playlistTitle = ""
    private val chapters = HashMap<String, List<Pair<Long, String>>>()   // video_id → [(開始毫秒, 標題)]
    private val summaries = HashMap<String, String>()
    private val infoAsked = HashSet<String>()
    private lateinit var http: DefaultHttpDataSource.Factory
    private var cacheFactory: CacheDataSource.Factory? = null
    private val prefetchExec = Executors.newSingleThreadExecutor()
    @Volatile private var prefetchWriter: CacheWriter? = null
    private var prefetchedIdx = -1
    // 卡頓統計（回報給手機遙控頁／伺服器 log，方便查「為什麼不順」）
    private var stalls = 0
    private var stallMs = 0L
    private var stallStart = 0L
    private var wasReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = pending
        if (json == null) { finish(); return }
        current = WeakReference(this)
        cookie = CookieManager.getInstance().getCookie(BuildConfig.BASE_URL) ?: ""

        http = DefaultHttpDataSource.Factory()
            .setUserAgent("YTmateTV/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setDefaultRequestProperties(if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap())
        // 播放暫存（依剩餘空間自動決定；空間不夠時不暫存）
        // v1.1.1：播放器對暫存「只讀不寫」——有預先下載好的部分就直接讀，正在播的影片不再整支寫進電視的儲存空間
        // （舊電視的儲存晶片很慢，邊播邊寫會拖慢播放）；只有「預先下載下一支的開頭」會寫入暫存。
        val cache = MediaCache.get(this)
        val dataSource: DataSource.Factory = if (cache != null) {
            cacheFactory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else http
        // 緩衝：網路有餘力就往前多抓（最多 5 分鐘）；記憶體小的舊電視少抓一點
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lowRam = am.isLowRamDevice || am.memoryClass < 192
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 300_000, 2_500, 5_000)
            .setTargetBufferBytes(if (lowRam) 32 * 1024 * 1024 else 64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        Log.i(TAG, "player buffer lowRam=$lowRam cache=${cache != null} cap=${MediaCache.capBytes}")

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
        // 字幕：預設自動開啟、英文優先；照使用者上次的設定（控制列的字幕按鈕可以切換，切換後會記住）
        subPref = Prefs.get(this, "subs").ifEmpty { "en" }
        applySubPref()

        buildUi()
        view.player = player
        // 媒體工作階段：系統媒體鍵／藍牙遙控器／語音控制都送到這個播放器（Android TV 品質檢查表要求）
        try {
            // 縮圖也要登入：系統「正在播放」卡片的封面用同一個帶 cookie 的連線下載
            session = MediaSession.Builder(this, player).setId("ytmate-${SystemClock.uptimeMillis()}")
                .setBitmapLoader(DataSourceBitmapLoader(MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()), http))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "media session failed: $e")
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                errorsInRow = 0
                val i = player.currentMediaItemIndex
                // 上一支：自然播完 → 標成看過；中途換掉 → 記住看到哪裡
                if (curIdx in items.indices && curIdx != i) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) rememberPos(curIdx, curDur, curDur, true)
                    else rememberPos(curIdx, curPos, curDur, false)
                }
                Log.i(TAG, "transition item=$i reason=$reason")
                hideCountdown()
                if (player.pauseAtEndOfMediaItems) player.pauseAtEndOfMediaItems = false
                cancelledIdx = -1
                if (i in items.indices) {
                    netRetry[i] = 0
                    if (curIdx != i && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
                        applyResume(i)
                    curIdx = i; curPos = 0L; curDur = 0L
                    lastVideoId = items[i].vid
                    stalls = 0; stallMs = 0L; stallStart = 0L; wasReady = false
                    requestInfo(i)
                    applyChapters(i)
                    if (prefetchedIdx != i) cancelPrefetch()
                    prefetchedIdx = -1
                }
                showItem(i)
                Remote.reportNow()
            }

            // 影像軌這台電視解不了（例如舊電視遇到 AV1）→ 換下一個版本
            override fun onTracksChanged(tracks: Tracks) {
                val i = player.currentMediaItemIndex
                val cur = items.getOrNull(i)?.sources?.getOrNull(srcIdx.getOrElse(i) { 0 }) ?: return
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
                if (isPlaying) playingSince = SystemClock.uptimeMillis()
                updateKeepScreenOn()
                if (isPlaying && view.isControllerFullyVisible) {
                    view.controllerShowTimeoutMs = HIDE_MS
                    view.showController()                    // 播放中 → 重新排定 3.5 秒後收起
                }
                Remote.reportNow()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) lastSeekAt = SystemClock.uptimeMillis()
            }

            override fun onPlaybackStateChanged(state: Int) {
                updateKeepScreenOn()
                trackStall(state)
                if (state == Player.STATE_READY) {
                    val i = player.currentMediaItemIndex
                    if (i in netRetry.indices) netRetry[i] = 0
                }
                if (state == Player.STATE_ENDED) onPlaylistEnded()
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
        if (!loadPlaylist(json, false)) { finish(); return }
        ui.postDelayed(watchdog, 1000)
    }

    /** 播放器已經開著時又收到新的清單（手機送來的影片、待播清單）→ 同一個播放器換清單。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val json = pending ?: return
        Log.i(TAG, "playlist replace (new intent)")
        if (upShown) closeUpNext()           // 換清單時收起「接下來播放」（內容已經不對了）
        saveCurrent()
        cancelPrefetch()
        ui.removeCallbacks(queueTimeout)
        waitingQueue = false
        queueAsked = false
        if (!loadPlaylist(json, true)) Toast.makeText(this, "收到的清單沒有可以播放的影片", Toast.LENGTH_LONG).show()
    }

    /** 解析頁面送來的清單（items、start、startPos），開始播放。回傳是否有可以播的影片。 */
    private fun loadPlaylist(json: String, replace: Boolean): Boolean {
        val root = try { JSONObject(json) } catch (e: Exception) { return false }
        val arr = root.optJSONArray("items") ?: return false
        val start = root.optInt("start", 0)
        val startPosOverride = root.optLong("startPos", 0L)
        val parsed = ArrayList<Item>()
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
            parsed.add(Item(srcs, o.optString("title"), o.optString("artist"), o.optString("thumb"), o.optString("note"),
                subs, o.optString("video_id"), (o.optDouble("duration", 0.0) * 1000).toLong(),
                (o.optDouble("resume", 0.0) * 1000).toLong(), (o.optDouble("resumeAt", 0.0) * 1000).toLong()))
        }
        if (parsed.isEmpty()) return false
        items.clear(); items.addAll(parsed)
        srcIdx = IntArray(items.size)
        netRetry = IntArray(items.size)
        noted.clear(); resumed.clear()
        cancelledIdx = -1; prefetchedIdx = -1; curIdx = -1
        hideCountdown()
        playlistTitle = root.optString("title")
        val mediaItems = ArrayList<MediaItem>()
        for (i in items.indices) mediaItems.add(buildItem(i))
        val s0 = start.coerceIn(0, mediaItems.size - 1)
        val startPos = if (startPosOverride > 0) startPosOverride else resumePos(s0)
        player.pauseAtEndOfMediaItems = false
        player.setMediaItems(mediaItems, s0, startPos)
        player.prepare()
        player.playWhenReady = true
        curIdx = s0
        lastVideoId = items[s0].vid
        resumed.add(s0)
        Log.i(TAG, "playlist loaded items=${items.size} start=$s0 startPos=$startPos replace=$replace title=$playlistTitle")
        if (startPosOverride > 0) {
            Log.i(TAG, "start pos=$startPosOverride")
            Toast.makeText(this, "從 ${fmt(startPosOverride / 1000)} 接著播", Toast.LENGTH_SHORT).show()
        } else if (startPos > 0) resumeToast(startPos)
        requestInfo(s0)
        applyChapters(s0)
        showItem(s0)
        Remote.reportNow()
        return true
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

    /** 這支要從哪裡開始：伺服器記的（電視、網頁、其他電視共用）跟 App 自己記的比，取比較新的。音樂一律從頭。 */
    private fun resumePos(i: Int): Long {
        val it = items.getOrNull(i) ?: return 0L
        if (it.sources.getOrNull(srcIdx.getOrElse(i) { 0 })?.kind == "audio") return 0L
        val (localPos, localAt) = Resume.entry(this, it.vid)
        val pos = if (it.resumeAtMs > 0 && it.resumeAtMs >= localAt) it.resumeMs else localPos
        Log.i(TAG, "resume pick vid=${it.vid} server=${it.resumeMs}@${it.resumeAtMs} local=$localPos@$localAt -> $pos")
        return pos.coerceAtLeast(0L)
    }

    /** 換到一支「之前看到一半」的影片 → 從那裡繼續。 */
    private fun applyResume(i: Int) {
        if (!resumed.add(i)) return
        val pos = resumePos(i)
        if (pos > 0) {
            player.seekTo(i, pos)
            resumeToast(pos)
        }
    }

    private fun resumeToast(pos: Long) {
        Log.i(TAG, "resume from=$pos")
        Toast.makeText(this, "從上次看到的 ${fmt(pos / 1000)} 繼續播放（按左鍵倒轉）", Toast.LENGTH_LONG).show()
    }

    /** 記住看到哪裡（App 自己＋伺服器）。finished＝看完了（標成看過）。音樂不記。 */
    private fun rememberPos(i: Int, pos: Long, dur: Long, finished: Boolean) {
        val it = items.getOrNull(i) ?: return
        if (isAudio(i)) return
        if (finished) {
            Resume.clear(this, it.vid)
            Remote.saveResume(it.vid, dur, dur, true)
        } else {
            Resume.save(this, it.vid, pos, dur, RESUME_MIN_MS)
            if (pos >= 10_000L) Remote.saveResume(it.vid, pos, dur, false)
        }
    }

    private fun updateKeepScreenOn() {
        if (!::view.isInitialized) return
        val st = player.playbackState
        view.keepScreenOn = player.playWhenReady && (st == Player.STATE_READY || st == Player.STATE_BUFFERING)
    }

    /**
     * 保險：每秒檢查一次。播放中、控制列還開著、5 秒沒按鍵、而且沒有開著字幕選單（選單會搶走視窗焦點）→ 收起控制列。
     * 同時記下目前播放位置（換支或離開時記住看到哪裡；每 30 秒也存一次到伺服器）、更新「N 秒後播放下一支」、預先下載下一支。
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
            val now = SystemClock.uptimeMillis()
            if (view.isControllerFullyVisible && player.isPlaying && hasWindowFocus() &&
                now - lastKeyAt > WATCHDOG_IDLE_MS && now - playingSince > HIDE_MS) {
                Log.i(TAG, "controller watchdog hide")
                view.hideController()
            }
            if (player.isPlaying && curIdx in items.indices && now - lastResumeSync > 30_000L && curPos >= 10_000L) {
                lastResumeSync = now
                rememberPos(curIdx, curPos, curDur, false)
            }
            updateCountdown()
            maybePrefetch()
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

    /** 手機切換畫質：這支影片有對應的版本就馬上換（同一個時間點繼續），沒有就下一支開始套用。 */
    private fun switchQuality(v: String): Pair<Boolean, String> {
        if (v != "low" && v != "auto") return false to "畫質設定不對"
        Prefs.set(this, "quality", v)
        val i = player.currentMediaItemIndex
        val it = items.getOrNull(i) ?: return true to "已切換畫質設定"
        val want = if (v == "low") "low" else "original"
        val k = it.sources.indexOfFirst { s -> s.kind == want }
        if (k < 0) return true to (if (v == "low") "畫質：480p（這支還沒有 480p 版本，下次播放套用）" else "畫質：自動（下次播放套用）")
        if (k == srcIdx[i]) return true to (if (v == "low") "畫質：480p" else "畫質：自動（最佳）")
        val pos = player.currentPosition.coerceAtLeast(0L)
        srcIdx[i] = k
        player.replaceMediaItem(i, buildItem(i))
        player.seekTo(i, pos)
        player.prepare()
        player.play()
        Log.i(TAG, "quality switch item=$i -> $want")
        return true to (if (v == "low") "畫質：已切換成 480p" else "畫質：已切換成原檔（最佳）")
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun roundBg(color: String, radius: Float) = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(Color.parseColor(color)) }

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
            background = roundBg("#171724", 18f)
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

        // 快轉／倒轉的時間軸（畫面下方；控制列收起時才會出現）
        seekBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48f), dp(40f), dp(48f), dp(28f))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#D9000000"), Color.TRANSPARENT))
            visibility = View.GONE
        }
        seekDeltaText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        seekBox.addView(seekDeltaText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10f) })
        seekTrack = FrameLayout(this).apply { background = roundBg("#55FFFFFF", 3f) }
        seekBuffered = View(this).apply { background = roundBg("#88FFFFFF", 3f) }
        seekPlayed = View(this).apply { background = roundBg("#EC4899", 3f) }
        seekTrack.addView(seekBuffered, FrameLayout.LayoutParams(0, -1))
        seekTrack.addView(seekPlayed, FrameLayout.LayoutParams(0, -1))
        val trackWrap = FrameLayout(this)
        trackWrap.addView(seekTrack, FrameLayout.LayoutParams(-1, dp(6f), Gravity.CENTER_VERTICAL))
        seekTicks = TickView(this)                                   // 章節分段點
        trackWrap.addView(seekTicks, FrameLayout.LayoutParams(-1, dp(14f), Gravity.CENTER_VERTICAL))
        seekThumb = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
        trackWrap.addView(seekThumb, FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.CENTER_VERTICAL))
        seekBox.addView(trackWrap, LinearLayout.LayoutParams(-1, dp(18f)))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        seekTime = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        seekDur = TextView(this).apply { setTextColor(Color.parseColor("#C8C8D8")); textSize = 15f; gravity = Gravity.END }
        times.addView(seekTime, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(seekDur, LinearLayout.LayoutParams(0, -2, 1f))
        seekBox.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        overlay.addView(seekBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // 畫面上方中間的短提示（字幕開關等）
        osd = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(22f), dp(10f), dp(22f), dp(10f))
            background = roundBg("#CC17172A", 24f)
            visibility = View.GONE
        }
        overlay.addView(osd, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(36f) })

        // 右下角「N 秒後播放：下一支」
        nextCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
            background = roundBg("#E617172A", 14f)
            visibility = View.GONE
        }
        nextTitle = TextView(this).apply { setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
        val nextHint = TextView(this).apply { setTextColor(Color.parseColor("#C8C8D8")); textSize = 13f; text = "確認＝立即播放　返回＝取消" }
        nextCard.addView(nextTitle); nextCard.addView(nextHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        overlay.addView(nextCard, FrameLayout.LayoutParams(dp(380f), -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(40f); bottomMargin = dp(110f) })

        // 選單鍵叫出控制列時，右邊顯示這支影片的中文摘要
        summaryBox = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setLineSpacing(0f, 1.35f)
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
            background = roundBg("#E617172A", 14f)
            visibility = View.GONE
        }
        overlay.addView(summaryBox, FrameLayout.LayoutParams(dp(400f), -2, Gravity.TOP or Gravity.END).apply { rightMargin = dp(40f); topMargin = dp(120f) })

        // 右上角提示（新一集做好、同步到新影片；手機送來的提示）
        noticeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = roundBg("#F217172A", 14f)
            visibility = View.GONE
        }
        noticeImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE; clipToOutline = true; background = roundBg("#000000", 8f) }
        noticeText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; maxLines = 3 }
        noticeBox.addView(noticeImg, LinearLayout.LayoutParams(-1, dp(171f)).apply { bottomMargin = dp(10f) })
        noticeBox.addView(noticeText)
        overlay.addView(noticeBox, FrameLayout.LayoutParams(dp(330f), -2, Gravity.TOP or Gravity.END).apply { rightMargin = dp(32f); topMargin = dp(28f) })

        // 「接下來播放」面板（長按確認鍵打開；疊在控制列上面）
        upPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(24f), dp(22f), dp(20f))
            background = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(Color.parseColor("#F20B0B14"), Color.parseColor("#E00B0B14")))
            visibility = View.GONE
        }
        upPanel.addView(TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; text = "⏭ 接下來播放" })
        upPanel.addView(TextView(this).apply { setTextColor(Color.parseColor("#A3A3B8")); textSize = 12.5f; text = "上下選擇　確認＝播放這支　長按確認＝刪除　返回＝關閉" },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f); bottomMargin = dp(12f) })
        upScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFocusable = false }
        upList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        upScroll.addView(upList, FrameLayout.LayoutParams(-1, -2))
        upPanel.addView(upScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(upPanel, FrameLayout.LayoutParams(dp(430f), -1, Gravity.END))
        setContentView(root)
    }

    private val hideOsd = Runnable { osd.visibility = View.GONE }

    private fun showOsd(text: String) {
        osd.text = text
        osd.visibility = View.VISIBLE
        ui.removeCallbacks(hideOsd)
        ui.postDelayed(hideOsd, 2000)
    }

    private val hideNotice = Runnable { noticeBox.visibility = View.GONE }

    /** 右上角提示 6 秒（Remote 收到 toast 指令時呼叫）。 */
    fun showNotice(text: String, imageUrl: String) {
        noticeText.text = text
        noticeImg.visibility = View.GONE
        noticeImg.setImageDrawable(null)
        if (imageUrl.isNotEmpty()) loadImage(imageUrl) { bmp -> noticeImg.setImageBitmap(bmp); noticeImg.visibility = View.VISIBLE }
        noticeBox.visibility = View.VISIBLE
        ui.removeCallbacks(hideNotice)
        ui.postDelayed(hideNotice, 6000)
    }

    /** 控制列收起時按「下」：字幕開／關（記住設定；再打開時用上次的語言）。 */
    private fun toggleSubtitles(): String {
        val i = player.currentMediaItemIndex
        val it = items.getOrNull(i) ?: return "沒有在播放"
        if (it.subs.isEmpty() || isAudio(i)) {
            Log.i(TAG, "subtitle toggle: none available")
            showOsd("這支影片沒有字幕"); return "這支影片沒有字幕"
        }
        if (subPref == "off") {
            subPref = Prefs.get(this, "subs_last").ifEmpty { "en" }
        } else {
            Prefs.set(this, "subs_last", subPref)
            subPref = "off"
        }
        Prefs.set(this, "subs", subPref)
        applySubPref()
        Log.i(TAG, "subtitle toggle -> $subPref saved=${Prefs.get(this, "subs")}")
        val label = when (subPref) { "off" -> "字幕：關閉"; "zh" -> "字幕：中文"; else -> "字幕：English" }
        showOsd(label)
        return label
    }

    /** 快轉／倒轉時顯示時間軸；放開按鍵、恢復正常播放後自動消失。 */
    private fun showSeekBar(target: Long, delta: Long) {
        if (!seekShown) {
            seekShown = true
            seekAccum = 0L
            seekBox.visibility = View.VISIBLE
            Log.i(TAG, "seekbar visible=true")
        }
        seekAccum += delta
        seekTarget = target
        seekReleasedAt = 0L
        val sign = if (seekAccum >= 0) "+" else "−"
        val chap = chapterAt(player.currentMediaItemIndex, target)
        seekDeltaText.text = (if (seekAccum >= 0) "⏩ " else "⏪ ") + sign + fmt(kotlin.math.abs(seekAccum) / 1000) + (if (chap.isNotEmpty()) "　·　$chap" else "")
        if (chap.isNotEmpty()) Log.i(TAG, "seek chapter=$chap")
        updateSeekBar(target)
        ui.removeCallbacks(seekTick)
        ui.postDelayed(seekTick, 250)
    }

    private fun updateSeekBar(posMs: Long = player.currentPosition) {
        val dur = player.duration.takeIf { d -> d > 0 } ?: items.getOrNull(player.currentMediaItemIndex)?.durMs ?: 0L
        seekTime.text = fmt(posMs.coerceAtLeast(0L) / 1000)
        seekDur.text = if (dur > 0) fmt(dur / 1000) else "--:--"
        val w = seekTrack.width
        if (w <= 0 || dur <= 0) { seekTrack.post { if (seekShown) updateSeekBar(posMs) }; return }
        seekTicks.setDuration(dur)
        val played = (w * (posMs.toDouble() / dur)).toInt().coerceIn(0, w)
        val buffered = (w * (player.bufferedPosition.toDouble() / dur)).toInt().coerceIn(played, w)
        seekPlayed.layoutParams = (seekPlayed.layoutParams as FrameLayout.LayoutParams).apply { width = played }
        seekBuffered.layoutParams = (seekBuffered.layoutParams as FrameLayout.LayoutParams).apply { width = buffered }
        seekThumb.translationX = (played - dp(8f)).toFloat().coerceAtLeast(0f)
        seekPlayed.requestLayout()
    }

    private fun hideSeekBar() {
        ui.removeCallbacks(seekTick)
        if (!seekShown) return
        seekShown = false
        seekBox.visibility = View.GONE
        seekReleasedAt = 0L
        Log.i(TAG, "seekbar visible=false")
    }

    /** 時間軸顯示中：每 0.25 秒更新；放開按鍵後，等播放恢復正常（不在緩衝）1.2 秒就收起。 */
    private val seekTick = object : Runnable {
        override fun run() {
            if (!seekShown || isFinishing) return
            val now = SystemClock.uptimeMillis()
            updateSeekBar(if (seekReleasedAt == 0L && seekTarget >= 0) seekTarget else player.currentPosition)
            val settled = player.isPlaying || !player.playWhenReady || player.playbackState == Player.STATE_ENDED
            if (seekReleasedAt > 0 && settled && now - seekReleasedAt >= 1200) { hideSeekBar(); return }
            if (seekReleasedAt > 0 && now - seekReleasedAt > 15_000) { hideSeekBar(); return }   // 保險：一直在緩衝也不要永遠掛著
            ui.postDelayed(this, 250)
        }
    }

    /** 手機遙控的快轉／跳到：顯示時間軸，跟按住放開一樣自動消失。 */
    private fun remoteSeekRelease() {
        if (!seekShown) return
        seekReleasedAt = SystemClock.uptimeMillis()
        ui.removeCallbacks(seekTick); ui.postDelayed(seekTick, 250)
    }

    private val hideTitle = Runnable { titleBox.animate().alpha(0f).setDuration(400).start() }

    private fun isAudio(i: Int): Boolean = items.getOrNull(i)?.let { it.sources[srcIdx[i]].kind == "audio" } ?: false

    /** 控制列出現 → 標題一起出現（看得到在播哪一支）；控制列收起 → 影片的標題也收起。選單鍵叫出的 → 右邊加摘要。 */
    private fun onControllerVisibility(visible: Boolean) {
        if (!::titleBox.isInitialized || !::player.isInitialized) return
        val i = player.currentMediaItemIndex
        val it = items.getOrNull(i) ?: return
        ui.removeCallbacks(hideTitle)
        if (visible) hideSeekBar()                     // 控制列有自己的時間軸
        if (visible) {
            titleText.text = it.title
            subText.text = listOf(it.artist, "${i + 1} / ${items.size}").filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
            titleBox.animate().cancel(); titleBox.alpha = 1f
            if (menuOpened) showSummary(i)
        } else {
            summaryBox.visibility = View.GONE
            menuOpened = false
            if (!isAudio(i)) ui.postDelayed(hideTitle, 300)
        }
    }

    private fun showSummary(i: Int) {
        val it = items.getOrNull(i) ?: return
        val s = summaries[it.vid]
        summaryBox.text = "📝 這支在講什麼\n\n" + when {
            s == null -> "（摘要載入中…）"
            s.isEmpty() -> "（這支影片還沒有摘要）"
            else -> s
        }
        summaryBox.visibility = View.VISIBLE
        noticeBox.visibility = View.GONE              // 摘要是自己叫出來的：右上角的提示先收起，不要蓋住
        Log.i(TAG, "summary shown len=${s?.length ?: -1}")
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

    // ══ 章節、摘要（伺服器 /api/tv/info）══
    private fun requestInfo(i: Int) {
        val vid = items.getOrNull(i)?.vid ?: return
        if (vid.isEmpty() || !infoAsked.add(vid)) return
        Remote.fetchInfo(vid) { j ->
            val list = ArrayList<Pair<Long, String>>()
            val arr = j.optJSONArray("chapters")
            if (arr != null) for (k in 0 until arr.length()) {
                val c = arr.getJSONObject(k)
                list.add(((c.optDouble("start", 0.0)) * 1000).toLong() to c.optString("title"))
            }
            chapters[vid] = list
            summaries[vid] = j.optString("summary")
            Log.i(TAG, "info vid=$vid chapters=${list.size} summary=${summaries[vid]?.length}")
            val cur = player.currentMediaItemIndex
            if (items.getOrNull(cur)?.vid == vid) {
                applyChapters(cur)
                if (summaryBox.visibility == View.VISIBLE) showSummary(cur)
            }
        }
    }

    private fun applyChapters(i: Int) {
        val vid = items.getOrNull(i)?.vid ?: return
        val starts = (chapters[vid] ?: emptyList()).map { p -> p.first }.filter { t -> t > 0 }.toLongArray()
        try { view.setExtraAdGroupMarkers(starts, BooleanArray(starts.size)) } catch (_: Exception) {}
        seekTicks.setMarks(starts)
        if (starts.isNotEmpty()) Log.i(TAG, "chapters item=$i n=${starts.size + 1}")
    }

    private fun chapterAt(i: Int, posMs: Long): String {
        val list = chapters[items.getOrNull(i)?.vid ?: return ""] ?: return ""
        var t = ""
        for ((st, title) in list) if (st <= posMs) t = title
        return t
    }

    // ══「N 秒後播放：下一支」══
    private fun updateCountdown() {
        val i = player.currentMediaItemIndex
        val dur = player.duration
        val remain = if (dur > 0) dur - player.currentPosition else -1L
        val show = player.isPlaying && i in items.indices && !isAudio(i) && player.hasNextMediaItem() && cancelledIdx != i &&
            dur >= COUNTDOWN_MIN_DUR && remain in 1..COUNTDOWN_MS
        if (!show) { if (countdownShown && !(player.playWhenReady && remain in 0..COUNTDOWN_MS)) hideCountdown(); return }
        val next = items.getOrNull(i + 1) ?: return
        val secs = ((remain + 999) / 1000).coerceAtLeast(1)
        nextTitle.text = "$secs 秒後播放：${next.title}"
        if (!countdownShown) {
            countdownShown = true
            nextCard.visibility = View.VISIBLE
            Log.i(TAG, "countdown show item=$i next=${i + 1}")
        }
    }

    private fun hideCountdown() {
        if (!::nextCard.isInitialized) return
        if (countdownShown) Log.i(TAG, "countdown hide")
        countdownShown = false
        nextCard.visibility = View.GONE
    }

    private fun cancelCountdown() {
        val i = player.currentMediaItemIndex
        cancelledIdx = i
        player.pauseAtEndOfMediaItems = true          // 停在這支的結尾（不自動跳下一支）
        hideCountdown()
        showOsd("已取消自動播放下一支")
        Log.i(TAG, "countdown cancel item=$i")
    }

    // ══ 整份清單播完：有待播清單就接著播 ══
    private fun onPlaylistEnded() {
        if (curIdx in items.indices) rememberPos(curIdx, curDur, curDur, true)
        curIdx = -1
        val m = Remote.mainActivity
        if (!queueAsked && m != null && m.askQueueNext()) {
            queueAsked = true
            waitingQueue = true
            Log.i(TAG, "playlist ended -> ask queue")
            ui.removeCallbacks(queueTimeout)
            ui.postDelayed(queueTimeout, 8000)
        } else finish()
    }

    private val queueTimeout = Runnable { if (waitingQueue) { Log.i(TAG, "queue timeout"); finish() } }

    fun queueDone() {
        if (!waitingQueue) return
        waitingQueue = false
        ui.removeCallbacks(queueTimeout)
        Log.i(TAG, "queue empty -> finish")
        finish()
    }

    // ══ 預先下載下一支的開頭（有播放暫存時）══
    private var prefetchDiagAt = 0L
    private var stallStartLast = 0L
    private var lastSeekAt = 0L                      // 自己快轉／跳轉造成的緩衝不算卡頓

    /** 播放中變成「緩衝中」＝卡頓：記次數與時間；一卡頓就先停掉預先下載（把頻寬讓給目前這支）。 */
    private fun trackStall(state: Int) {
        val now = SystemClock.uptimeMillis()
        if (state == Player.STATE_READY) {
            if (stallStart > 0) {
                val d = now - stallStart
                stallMs += d
                Log.i(TAG, "stall end item=${player.currentMediaItemIndex} ms=$d total=$stalls/${stallMs}ms")
                stallStart = 0L
            }
            wasReady = true
        } else if (state == Player.STATE_BUFFERING && wasReady && player.playWhenReady && !seekShown && stallStart == 0L &&
            now - lastSeekAt > 3000) {
            stalls++
            stallStart = now
            stallStartLast = now
            val pf = prefetchWriter != null
            if (pf) cancelPrefetch()
            Log.i(TAG, "stall start item=${player.currentMediaItemIndex} n=$stalls pos=${player.currentPosition} prefetching=$pf")
            Remote.reportNow()
        }
    }

    private fun maybePrefetch() {
        val f = cacheFactory ?: return
        val i = player.currentMediaItemIndex
        val n = i + 1
        if (n !in items.indices || prefetchedIdx == n) return
        val dur = player.duration
        val ahead = player.bufferedPosition - player.currentPosition
        val playedMs = SystemClock.uptimeMillis() - playingSince
        val fullyBuffered = dur > 0 && player.bufferedPosition >= dur - 1000
        val ready = player.isPlaying && player.playbackState == Player.STATE_READY && playedMs >= PREFETCH_AFTER_MS &&
            (fullyBuffered || ahead > 180_000L) && SystemClock.uptimeMillis() - stallStartLast > 30_000L
        if (!ready) {
            // 自動測試版：每 5 秒記一次還沒開始預先下載的原因（方便查模擬器上的時序問題）
            if (BuildConfig.E2E && SystemClock.uptimeMillis() - prefetchDiagAt > 5000) {
                prefetchDiagAt = SystemClock.uptimeMillis()
                Log.i(TAG, "prefetch wait item=$n playing=${player.isPlaying} played=$playedMs ahead=$ahead buffered=${player.bufferedPosition} dur=$dur")
            }
            return
        }
        prefetchedIdx = n
        val it = items[n]
        val src = it.sources[srcIdx[n]]
        val durMs = it.durMs
        val cap = MediaCache.capBytes
        prefetchExec.execute {
            try {
                val size = headSize(src.url)
                var len = if (size > 0) {
                    if (durMs > 0) minOf(size, maxOf(4L shl 20, size * (PREFETCH_SEC * 1000) / durMs + (1L shl 20))) else minOf(size, 15L shl 20)
                } else 15L shl 20
                if (cap > 0) len = minOf(len, cap / 4)
                Log.i(TAG, "prefetch item=$n kind=${src.kind} bytes=$len size=$size")
                val w = CacheWriter(f.createDataSource(), DataSpec.Builder().setUri(Uri.parse(src.url)).setPosition(0).setLength(len).build(), null, null)
                prefetchWriter = w
                w.cache()
                Log.i(TAG, "prefetch item=$n done")
            } catch (e: Exception) {
                Log.i(TAG, "prefetch item=$n stopped: $e")
            } finally {
                prefetchWriter = null
            }
        }
    }

    private fun cancelPrefetch() { try { prefetchWriter?.cancel() } catch (_: Exception) {} }

    private fun headSize(url: String): Long = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.connectTimeout = 10000; c.readTimeout = 15000
        if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
        val n = if (c.responseCode == 200) c.contentLength.toLong() else -1L
        c.disconnect(); n
    } catch (_: Exception) { -1L }

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

    // ══ 📱 手機遙控 ══
    /** Remote 回報給手機的播放狀態。 */
    fun remoteState(): JSONObject {
        val i = player.currentMediaItemIndex
        val it = items.getOrNull(i)
        val o = JSONObject().put("screen", "player").put("playing", player.isPlaying).put("paused", !player.playWhenReady)
        if (it != null) {
            val dur = player.duration.takeIf { d -> d > 0 } ?: it.durMs
            o.put("video_id", it.vid).put("title", it.title).put("channel", it.artist).put("thumb", it.thumb)
                .put("pos", player.currentPosition.coerceAtLeast(0L) / 1000.0).put("dur", dur / 1000.0)
                .put("index", i).put("count", items.size)
                .put("buffered", (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L) / 1000.0)
                .put("stalls", stalls).put("stall_ms", stallMs + (if (stallStart > 0) SystemClock.uptimeMillis() - stallStart else 0L))
                .put("prefetching", prefetchWriter != null).put("cache", cacheFactory != null)
        }
        return o
    }

    /** 執行手機送來的操作，回傳（成功？、給手機看的訊息）。 */
    fun remoteControl(action: String, value: Any?): Pair<Boolean, String> {
        lastKeyAt = SystemClock.uptimeMillis()
        val num = (value as? Number)?.toDouble()
        val r: Pair<Boolean, String> = when (action) {
            "toggle" -> { togglePlay(); true to (if (player.playWhenReady) "▶ 播放" else "⏸ 暫停") }
            "pause" -> { player.pause(); view.showController(); true to "⏸ 暫停" }
            "resume" -> { player.play(); true to "▶ 播放" }
            "seek_rel" -> {
                val d = ((num ?: 10.0) * 1000).toLong()
                hideCountdown()
                val target = seekBy(d); remoteSeekRelease()
                true to "${if (d >= 0) "快轉" else "倒退"} ${kotlin.math.abs(d) / 1000} 秒（${fmt(target / 1000)}）"
            }
            "seek_abs" -> {
                val dur = player.duration
                var t = ((num ?: 0.0) * 1000).toLong().coerceAtLeast(0L)
                if (dur > 0) t = t.coerceAtMost(dur - 1000)
                val cur = player.currentPosition
                player.seekTo(t)
                Log.i(TAG, "seek to=$t")
                showSeekBar(t, t - cur); remoteSeekRelease()
                true to "跳到 ${fmt(t / 1000)}"
            }
            "next" -> if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); true to "下一支：${items.getOrNull(player.currentMediaItemIndex)?.title ?: ""}" } else false to "已經是最後一支"
            "prev" -> { player.seekToPreviousMediaItem(); true to "上一支／從頭播放" }
            "stop" -> { finish(); true to "已停止，回到 YTmate 首頁" }
            "subs_toggle" -> { val l = toggleSubtitles(); (l != "這支影片沒有字幕") to l }
            "subs" -> {
                val v = value?.toString() ?: ""
                if (v in setOf("en", "zh", "off")) { subPref = v; Prefs.set(this, "subs", v); applySubPref(); true to "字幕：$v" } else false to "字幕設定不對"
            }
            "quality" -> switchQuality(value?.toString() ?: "")
            "volume_up", "volume_down", "volume_set", "mute" -> Remote.volume(action, value)
            else -> false to "這個版本的 App 不認得這個操作"
        }
        Log.i(TAG, "remote control $action -> ${r.first} ${r.second}")
        Remote.reportNow()
        return r
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val controllerShown = view.isControllerFullyVisible
        lastKeyAt = SystemClock.uptimeMillis()
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) Log.i(TAG, "key ${KeyEvent.keyCodeToString(code)} shown=$controllerShown focus=${focusName()}")
            if (upShown) return upKey(event)
            // 按住不放：左右鍵繼續快轉（就算控制列中途出現也一樣）；確認鍵按住＝長按（打開「接下來播放」）
            if (event.repeatCount > 0 && code == consumedKey) {
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) seekBy(+seekStep(event.repeatCount))
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) seekBy(-seekStep(event.repeatCount))
                if (isOk(code) && !okLongDone) { okLongDone = true; openUpNext() }
                return true
            }
            // 「N 秒後播放下一支」顯示中：確認＝立刻播下一支（返回＝取消，在放開時處理）
            if (countdownShown && !controllerShown && event.repeatCount == 0 &&
                (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                Log.i(TAG, "countdown play next")
                hideCountdown()
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                consumedKey = code
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
                KeyEvent.KEYCODE_MENU -> {
                    if (controllerShown) view.hideController() else { menuOpened = true; view.showController() }
                    return true
                }
            }
            if (!controllerShown) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        // 短按＝暫停／播放、長按＝「接下來播放」：放開時才決定（見下面 ACTION_UP）
                        if (event.repeatCount == 0) { okDownAt = SystemClock.uptimeMillis(); okLongDone = false }
                        consumedKey = code; return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(+seekStep(event.repeatCount)); consumedKey = code; return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-seekStep(event.repeatCount)); consumedKey = code; return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.repeatCount == 0) toggleSubtitles(); consumedKey = code; return true }
                }
            }
            consumedKey = 0
        }
        if (upShown) return upKey(event)
        if (event.action == KeyEvent.ACTION_UP && code == consumedKey && code != KeyEvent.KEYCODE_BACK) {
            consumedKey = 0
            if (isOk(code) && okDownAt > 0) {
                val held = SystemClock.uptimeMillis() - okDownAt
                okDownAt = 0L
                if (!okLongDone) { if (held >= LONG_PRESS_MS) openUpNext() else togglePlay() }
                return true
            }
            if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && seekShown) {
                seekReleasedAt = SystemClock.uptimeMillis()          // 放開了：恢復正常播放後收起時間軸
                ui.removeCallbacks(seekTick); ui.postDelayed(seekTick, 250)
            }
            return true          // 按下時已經處理過的鍵：放開時不要再交給控制列（免得暫停後又被當成按了播放鍵）
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                when {
                    controllerShown -> view.hideController()
                    countdownShown -> cancelCountdown()
                    else -> finish()
                }
            }
            return true
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    // ══「接下來播放」：這份清單接下來的影片＋待播清單；確認＝播放、長按確認＝刪除 ══
    private fun openUpNext() {
        view.hideController()
        hideCountdown()
        upShown = true
        upOpenHeld = true            // 打開面板的那次長按還沒放開：放開／繼續按住都不算面板上的操作
        upOpenedAt = SystemClock.uptimeMillis()
        upQueueLoaded = false
        upSel = 0
        rebuildUpEntries(emptyList())
        upPanel.visibility = View.VISIBLE
        Log.i(TAG, "upnext open list=${upEntries.size}")
        Remote.queueGet { q ->
            if (!upShown) return@queueGet
            upQueueLoaded = true
            rebuildUpEntries(q)
            Log.i(TAG, "upnext queue n=${q.size}")
        }
    }

    private fun closeUpNext() {
        upShown = false
        upPanel.visibility = View.GONE
        okDownAt = 0L
        Log.i(TAG, "upnext close")
    }

    private fun rebuildUpEntries(queue: List<Pair<String, String>>?) {
        val keepQueue = if (queue == null) upEntries.filter { e -> e.kind == "queue" } else queue.map { (v, t) -> UpEntry("queue", v, t) }
        upEntries.clear()
        val cur = player.currentMediaItemIndex
        for (j in (cur + 1) until items.size) upEntries.add(UpEntry("list", j.toString(), items[j].title))
        upEntries.addAll(keepQueue)
        upSel = upSel.coerceIn(0, (upEntries.size - 1).coerceAtLeast(0))
        renderUpNext()
    }

    private fun renderUpNext() {
        upList.removeAllViews()
        upRows.clear()
        fun header(t: String) = upList.addView(TextView(this).apply {
            setTextColor(Color.parseColor("#F9A8D4")); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; text = t
            setPadding(dp(4f), dp(10f), dp(4f), dp(6f))
        })
        val nList = upEntries.count { e -> e.kind == "list" }
        val nQueue = upEntries.size - nList
        header("這份清單接下來（$nList）")
        if (nList == 0) upList.addView(TextView(this).apply { setTextColor(Color.parseColor("#8B8BA0")); textSize = 14f; text = "（這是最後一支）"; setPadding(dp(4f), 0, 0, dp(6f)) })
        var headerQueueDone = false
        for ((k, e) in upEntries.withIndex()) {
            if (e.kind == "queue" && !headerQueueDone) { header("📋 待播清單（播完後接著播，$nQueue）"); headerQueueDone = true }
            val row = TextView(this).apply {
                textSize = 15f; maxLines = 2
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                text = e.title
            }
            upList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6f) })
            upRows.add(row)
            styleUpRow(k)
        }
        if (!headerQueueDone) {
            header("📋 待播清單（播完後接著播）")
            upList.addView(TextView(this).apply { setTextColor(Color.parseColor("#8B8BA0")); textSize = 14f
                text = if (upQueueLoaded) "（空的；可以在手機遙控頁加）" else "（載入中…）"; setPadding(dp(4f), 0, 0, dp(6f)) })
        }
        upScroll.post { scrollToUpSel() }
    }

    private fun styleUpRow(k: Int) {
        val row = upRows.getOrNull(k) ?: return
        val sel = k == upSel
        row.setTextColor(if (sel) Color.parseColor("#111111") else Color.WHITE)
        row.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        row.background = roundBg(if (sel) "#FFFFFF" else "#26FFFFFF", 10f)
    }

    private fun scrollToUpSel() {
        val row = upRows.getOrNull(upSel) ?: return
        val y = row.top - dp(40f)
        upScroll.smoothScrollTo(0, y.coerceAtLeast(0))
    }

    private fun moveUpSel(d: Int) {
        if (upEntries.isEmpty()) return
        val old = upSel
        upSel = (upSel + d).coerceIn(0, upEntries.size - 1)
        if (old != upSel) { styleUpRow(old); styleUpRow(upSel); scrollToUpSel() }
    }

    /** 面板開著時的按鍵：上下選擇、確認（短按播放／長按刪除）、返回或選單鍵關閉；其他鍵不做事。 */
    private fun upKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            when {
                code == KeyEvent.KEYCODE_DPAD_UP -> moveUpSel(-1)
                code == KeyEvent.KEYCODE_DPAD_DOWN -> moveUpSel(+1)
                isOk(code) -> {
                    if (event.repeatCount == 0) {
                        // 新的一次按下（打開面板那次已經放開）
                        upOpenHeld = false; okDownAt = SystemClock.uptimeMillis(); okLongDone = false; consumedKey = code
                    } else if (!upOpenHeld && okDownAt > 0 && !okLongDone && consumedKey == code) {
                        okLongDone = true; deleteUpSel()
                    }
                }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            when {
                code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU -> closeUpNext()
                isOk(code) && upOpenHeld -> { upOpenHeld = false; okDownAt = 0L; okLongDone = false; consumedKey = 0 }
                isOk(code) -> {
                    val pressedHere = okDownAt > 0 && consumedKey == code
                    val held = SystemClock.uptimeMillis() - okDownAt
                    val wasLong = okLongDone
                    okDownAt = 0L; okLongDone = false; consumedKey = 0
                    if (pressedHere && !wasLong && openedAt > 0 && SystemClock.uptimeMillis() - openedAt > 300) {
                        if (held >= LONG_PRESS_MS) deleteUpSel() else playUpSel()
                    }
                }
            }
            return true
        }
        return true
    }
    private val openedAt: Long get() = if (upShown) upOpenedAt else 0L
    private var upOpenedAt = 0L
    private var upOpenHeld = false

    private fun playUpSel() {
        val e = upEntries.getOrNull(upSel) ?: return
        closeUpNext()
        if (e.kind == "list") {
            val j = e.vid.toIntOrNull() ?: return
            if (j in items.indices) { Log.i(TAG, "upnext play list idx=$j"); player.seekTo(j, 0L); player.play() }
        } else {
            // 待播清單的影片：交給電視頁面跟伺服器拿播放資料（取走這支和後面的），同一個播放器換清單
            Log.i(TAG, "upnext play queue vid=${e.vid}")
            val m = Remote.mainActivity
            if (m == null) { Toast.makeText(this, "電視上的 YTmate 頁面沒有開著", Toast.LENGTH_SHORT).show(); return }
            m.runJs("window.tvPlayQueueVid ? (window.tvPlayQueueVid(${JSONObject.quote(e.vid)}), 'ok') : 'old'") { r ->
                if (r == null || !r.contains("ok")) Toast.makeText(this, "電視頁面太舊，請更新伺服器", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteUpSel() {
        val e = upEntries.getOrNull(upSel) ?: return
        if (e.kind == "list") {
            val j = e.vid.toIntOrNull() ?: return
            if (j <= player.currentMediaItemIndex || j !in items.indices) return
            removeItemAt(j)
            Log.i(TAG, "upnext delete list idx=$j title=${e.title}")
            showOsd("🗑 已從這份清單刪除：${e.title}")
            rebuildUpEntries(null)
        } else {
            Log.i(TAG, "upnext delete queue vid=${e.vid}")
            Remote.queueRemove(e.vid) { ok ->
                if (ok) {
                    upEntries.removeAll { x -> x.kind == "queue" && x.vid == e.vid }
                    upSel = upSel.coerceIn(0, (upEntries.size - 1).coerceAtLeast(0))
                    if (upShown) renderUpNext()
                    showOsd("🗑 已從待播清單刪除：${e.title}")
                } else showOsd("刪除失敗，請確認網路")
            }
        }
    }

    /** 從目前這份清單拿掉第 j 支（只能拿掉還沒播到的）。 */
    private fun removeItemAt(j: Int) {
        player.removeMediaItem(j)
        items.removeAt(j)
        srcIdx = srcIdx.filterIndexed { i, _ -> i != j }.toIntArray()
        netRetry = netRetry.filterIndexed { i, _ -> i != j }.toIntArray()
        val shift = { set: HashSet<Int> -> val n = set.filter { i -> i != j }.map { i -> if (i > j) i - 1 else i }; set.clear(); set.addAll(n) }
        shift(noted); shift(resumed)
        if (prefetchedIdx == j) { cancelPrefetch(); prefetchedIdx = -1 } else if (prefetchedIdx > j) prefetchedIdx--
        if (cancelledIdx > j) cancelledIdx--
        if (curIdx > j) curIdx--
        Remote.reportNow()
    }

    /** 按住左右鍵不放會加速：前 3 下 10 秒、之後 30 秒、再久 60 秒（長影片找段落比較快）。 */
    private fun seekStep(repeat: Int): Long = when {
        repeat < 3 -> 10_000L
        repeat < 12 -> 30_000L
        else -> 60_000L
    }

    private fun seekBy(delta: Long): Long {
        val dur = player.duration
        var target = player.currentPosition + delta
        if (dur > 0) target = target.coerceAtMost(dur - 1000)
        target = target.coerceAtLeast(0L)
        val actual = target - player.currentPosition
        player.seekTo(target)
        Log.i(TAG, "seek to=$target")
        flashTitle(target)
        showSeekBar(target, actual)
        return target
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

    /** 記住目前這支看到哪裡（離開播放器、換清單時呼叫）。 */
    private fun saveCurrent() {
        if (!::player.isInitialized || curIdx !in items.indices) return
        if (player.currentMediaItemIndex == curIdx) {
            curPos = player.currentPosition
            curDur = player.duration.takeIf { d -> d > 0 } ?: items[curIdx].durMs
        }
        rememberPos(curIdx, curPos, curDur, false)
        lastVideoId = items[curIdx].vid
    }

    override fun onStart() {
        super.onStart()
        Remote.attachPlayer(this)
        Remote.onStart(this)
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            saveCurrent()
            player.pause()     // 按首頁鍵離開時暫停（回來可以繼續）
        }
        Remote.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        cancelPrefetch()
        prefetchExec.shutdownNow()
        if (Remote.player === this) Remote.attachPlayer(null)
        if (current?.get() === this) current = null
        session?.release()
        session = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
        Remote.reportNow()
    }

    /** 時間軸上的章節分段點。 */
    private class TickView(ctx: Context) : View(ctx) {
        private var marks = LongArray(0)
        private var dur = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FDE68A") }

        fun setMarks(m: LongArray) { marks = m; invalidate() }
        fun setDuration(d: Long) { if (d != dur) { dur = d; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (dur <= 0 || marks.isEmpty()) return
            val w = width.toFloat()
            val h = height.toFloat()
            val bw = resources.displayMetrics.density * 3f
            for (t in marks) {
                val x = (w * t / dur).coerceIn(0f, w - bw)
                canvas.drawRect(x, 0f, x + bw, h, paint)
            }
        }
    }
}

/** 播放位置（App 私有空間，最多記 200 支，舊的自動淘汰）。2026-10-17：所有影片都記（音樂由播放器排除）。 */
object Resume {
    private const val FILE = "ytmate_resume"
    private const val MAX = 200

    fun get(ctx: Context, vid: String): Long = entry(ctx, vid).first

    /** （位置, 記錄時間）；沒有記錄回傳 (0, 0)。 */
    fun entry(ctx: Context, vid: String): Pair<Long, Long> {
        if (vid.isEmpty()) return 0L to 0L
        val v = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(vid, null) ?: return 0L to 0L
        return (v.substringBefore(',').toLongOrNull() ?: 0L) to (v.substringAfter(',', "0").toLongOrNull() ?: 0L)
    }

    /** 看了 10 秒以上、還沒看到最後 20 秒才記；否則清掉。長度不知道時只看「10 秒以上」。 */
    fun save(ctx: Context, vid: String, pos: Long, dur: Long, minDur: Long) {
        if (vid.isEmpty()) return
        if (pos < 10_000L || (dur > 0 && (dur < minDur || pos > dur - 20_000L))) {
            Log.i(PlayerActivity.TAG, "resume skip vid=$vid pos=$pos dur=$dur")
            clear(ctx, vid); return
        }
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

    /** 看完了：記成「位置 0」（記錄時間＝現在），比伺服器舊的進度新，下次從頭播。 */
    fun clear(ctx: Context, vid: String) {
        if (vid.isEmpty()) return
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(vid, "0,${System.currentTimeMillis()}").apply()
    }
}
