package net.iucat.ytmate.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.service.dreams.DreamService
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 系統螢幕保護程式（2026-10-17，v1.1）：電視閒置時輪播 YTmate 最新節目封面、新影片標題和時間。
 * 要在電視的「設定 → 螢幕保護程式」選「YTmate」才會用到（大陸版小米電視多半只能用小米自己的屏保，
 * 選不到也沒關係：YTmate 開著時，App 裡的螢幕保護一樣會出現）。
 * 內容就是電視版頁面的螢幕保護模式（/tv?saver=1），用同一個登入 cookie。
 */
class SaverService : DreamService() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false          // 按任何鍵就結束螢幕保護（系統處理）
        isFullscreen = true
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.webViewClient = object : WebViewClient() {}
        w.loadUrl(BuildConfig.BASE_URL + "/tv?saver=1")
        setContentView(w)
        web = w
    }

    override fun onDetachedFromWindow() {
        web?.destroy()
        web = null
        super.onDetachedFromWindow()
    }
}
