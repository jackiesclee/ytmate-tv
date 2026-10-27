package net.iucat.ytmate.mobile

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 播放畫面：手機直拿、橫放都可以；右上角按鈕＝🎧 只聽聲音、倍速、睡眠定時、子母畫面。
 * 離開畫面（返回、切到別的 App）照樣在背景播（通知列可以控制）；播影片時按 Home 鍵自動變子母畫面。
 */
class PlayerActivity : Activity() {
    private lateinit var view: PlayerView
    private lateinit var bar: LinearLayout
    private lateinit var title: TextView
    private lateinit var btnListen: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnSleep: TextView
    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(m: androidx.media3.common.MediaMetadata) { updateBar() }
        override fun onIsPlayingChanged(isPlaying: Boolean) { updateBar() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.app = applicationContext
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        view = PlayerView(this)
        view.setShowSubtitleButton(true)
        view.setShowNextButton(true)
        view.setShowPreviousButton(true)
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        view.setFullscreenButtonClickListener { full ->
            requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(28), dp(8), dp(8))
        bar.setBackgroundColor(Color.parseColor("#99000000"))
        val back = button("‹ 返回") { finish() }
        title = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; maxLines = 2; setPadding(dp(8), 0, dp(8), 0)
        }
        btnListen = button("🎧") { toast(PlayerHub.setListen(this, !PlayerHub.listening())); updateBar() }
        btnSpeed = button("1x") { pickSpeed() }
        btnSleep = button("⏲") { pickSleep() }
        val pip = button("▣") { enterPip() }
        bar.addView(back)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btnListen); bar.addView(btnSpeed); bar.addView(btnSleep)
        if (Build.VERSION.SDK_INT >= 26) bar.addView(pip)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> bar.visibility = v })
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val p = PlayerHub.player(this)
        view.player = p
        p.addListener(listener)
        PlayerHub.connectService(this)
        updateBar()
        if (p.mediaItemCount == 0) { toast("目前沒有在播放"); finish() }
    }

    override fun onStop() {
        PlayerHub.player(this).removeListener(listener)
        view.player = null        // 畫面拿掉，聲音繼續（背景播放）
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        immersive(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    @Suppress("DEPRECATION")
    private fun immersive(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            window.decorView.systemUiVisibility = if (on) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) else 0
        }
    }

    /** 播影片時按 Home 鍵 → 子母畫面（只聽聲音時不用，直接背景播）。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val p = PlayerHub.player(this)
        if (p.isPlaying && !PlayerHub.listening()) enterPip()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } catch (_: Exception) {
            toast("這台裝置沒有開放子母畫面（設定 → 應用 → YTmate → 允許子母畫面）")
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        view.useController = !isInPictureInPictureMode
        bar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    private fun updateBar() {
        val np = PlayerHub.nowPlaying()
        title.text = np.optString("title") + (if (np.optInt("count") > 1) "  (${np.optInt("index") + 1}/${np.optInt("count")})" else "")
        btnListen.text = if (PlayerHub.listening()) "🎧開" else "🎧"
        val sp = np.optDouble("speed", 1.0)
        btnSpeed.text = if (sp == 1.0) "1x" else "${sp}x"
        val sl = np.optInt("sleep_min")
        btnSleep.text = if (sl > 0) "⏲${sl}分" else "⏲"
    }

    private fun pickSpeed() {
        val speeds = arrayOf("0.75", "1", "1.25", "1.5", "1.75", "2")
        AlertDialog.Builder(this).setTitle("播放速度").setItems(speeds.map { "${it}x" }.toTypedArray()) { _, w ->
            PlayerHub.setSpeed(this, speeds[w].toFloat()); updateBar()
        }.show()
    }

    private fun pickSleep() {
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        AlertDialog.Builder(this).setTitle("睡眠定時（時間到自動暫停）")
            .setItems(mins.map { if (it == 0) "關閉" else "$it 分鐘" }.toTypedArray()) { _, w ->
                PlayerHub.setSleep(mins[w]); updateBar()
                toast(if (mins[w] == 0) "已關閉睡眠定時" else "${mins[w]} 分鐘後自動暫停")
            }.show()
    }

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
