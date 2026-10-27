package net.iucat.ytmate.mobile

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** 背景播放：用 PlayerHub 的播放器建立媒體工作階段（通知列、鎖定畫面、藍牙耳機、小米「媒體控制」都能操作）。 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Api.app = applicationContext
        val open = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, PlayerHub.player(this)).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 從最近使用的 App 清單滑掉：沒在播就結束服務；正在播就繼續（跟音樂 App 一樣）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()      // 播放器留給 PlayerHub（同一個 App 裡共用）
        session = null
        super.onDestroy()
    }
}
