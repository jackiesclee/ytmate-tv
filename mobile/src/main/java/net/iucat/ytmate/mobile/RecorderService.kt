package net.iucat.ytmate.mobile

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 🎤 錄音（前景服務：鎖螢幕、切到別的 App 都照錄）＋邊錄邊上傳到 YTmate「🎤 錄音」頻道。
 *  - 16kHz 單聲道 → AAC-LC 32 kbps（ADTS，每小時約 14 MB）。
 *  - 每約 60 秒存成一塊（App 私有空間 files/rec/<編號>/00000.part…），有網路就依序上傳（伺服器「邊錄邊傳」）；
 *    沒網路先存在手機，App 重開或網路恢復後自動補傳；全部傳完才在伺服器「結束」→ 轉文字、AI 摘要。
 *  - 狀態（給頁面顯示）：RecorderService.status()。
 */
class RecorderService : Service() {
    companion object {
        private const val TAG = "YTmateRec"
        private const val CH = "rec"
        private const val NID = 42
        private const val RATE = 16000
        private const val BITRATE = 32000
        private const val CHUNK_SEC = 60
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_UPLOAD = "upload"

        @Volatile var recording = false
        @Volatile var startedAt = 0L               // elapsedRealtime
        @Volatile var recordedBytes = 0L
        @Volatile var curSid = ""
        @Volatile var uploading = false
        @Volatile var lastError = ""
        @Volatile var lastDone = ""
        @Volatile private var stopReq = false

        fun recRoot(ctx: Context): File = File(ctx.filesDir, "rec").apply { mkdirs() }

        /** 還沒傳完的錄音塊數（所有錄音加起來）。 */
        fun pendingChunks(ctx: Context): Int =
            recRoot(ctx).listFiles()?.sumOf { d -> d.listFiles { f -> f.name.endsWith(".part") }?.size ?: 0 } ?: 0

        fun capacity(ctx: Context): JSONObject {
            val free = recRoot(ctx).usableSpace
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return JSONObject().put("free_mb", free / 1048576).put("hours", free / (BITRATE / 8.0) / 3600.0)
                .put("battery", bat).put("charging", bm.isCharging).put("mb_per_hour", BITRATE / 8.0 * 3600 / 1048576)
        }

        fun status(ctx: Context): JSONObject = JSONObject().put("recording", recording)
            .put("elapsed", if (recording) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0)
            .put("bytes", recordedBytes).put("pending", pendingChunks(ctx)).put("uploading", uploading)
            .put("error", lastError).put("done", lastDone)

        fun start(ctx: Context, title: String, kind: String, lang: String) {
            val i = Intent(ctx, RecorderService::class.java).setAction(ACTION_START)
                .putExtra("title", title).putExtra("kind", kind).putExtra("lang", lang)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { stopReq = true; ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP)) }

        /** App 開啟時：有沒傳完的錄音就在背景補傳。 */
        fun resumeUploads(ctx: Context) {
            if (recording || uploading) return
            val any = recRoot(ctx).listFiles()?.any { File(it, "state.json").exists() } ?: false
            if (!any) return
            val i = Intent(ctx, RecorderService::class.java).setAction(ACTION_UPLOAD)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (e: Exception) { Log.w(TAG, "補傳啟動失敗", e) }
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var recThread: Thread? = null
    private var upThread: Thread? = null
    private val upLock = Object()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Api.app = applicationContext
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "錄音", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!recording) {
                foreground(true, "🔴 錄音中")
                startRecording(intent.getStringExtra("title") ?: "", intent.getStringExtra("kind") ?: "meeting", intent.getStringExtra("lang") ?: "zh")
            }
            ACTION_STOP -> { stopReq = true; if (!recording) ensureUploader() }
            ACTION_UPLOAD -> { foreground(false, "⬆️ 上傳錄音中"); ensureUploader() }
        }
        return START_STICKY
    }

    private fun foreground(mic: Boolean, text: String) {
        val type = if (Build.VERSION.SDK_INT >= 30) {
            if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        try {
            ServiceCompat.startForeground(this, NID, note(text, mic), type)
        } catch (e: Exception) {
            Log.e(TAG, "前景服務啟動失敗", e)
            lastError = "沒辦法在背景錄音／上傳（${e.message}）"
        }
    }

    private fun note(text: String, recordingNow: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("open", "rec"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("YTmate 錄音")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (recordingNow) {
            b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAt))
            val stopPi = PendingIntent.getService(this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE)
            b.addAction(android.R.drawable.ic_media_pause, "停止錄音", stopPi)
        }
        return b.build()
    }

    private fun updateNote(text: String) {
        getSystemService(NotificationManager::class.java).notify(NID, note(text, recording))
    }

    // ── 錄音 ──────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startRecording(title: String, kind: String, lang: String) {
        val sid = "r" + System.currentTimeMillis()
        val dir = File(recRoot(this), sid).apply { mkdirs() }
        writeState(dir, JSONObject().put("sid", sid).put("title", title).put("kind", kind).put("lang", lang)
            .put("rid", "").put("next", 0).put("uploaded", 0).put("ended", false).put("chunks", -1)
            .put("started", System.currentTimeMillis()))
        curSid = sid; recordedBytes = 0; lastError = ""; lastDone = ""; stopReq = false
        startedAt = SystemClock.elapsedRealtime()
        recording = true
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytmate:rec").apply { acquire(12 * 3600_000L) }
        updateNote("🔴 錄音中（停止請按下面的按鈕，或回到 YTmate）")
        ensureUploader()
        recThread = Thread({ recordLoop(dir) }, "ytmate-rec").apply { start() }
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop(dir: File) {
        var rec: AudioRecord? = null
        var enc: MediaCodec? = null
        var out: FileOutputStream? = null
        var idx = 0
        var chunkStart = SystemClock.elapsedRealtime()
        fun openChunk() { out = FileOutputStream(File(dir, "%05d.tmp".format(idx))); chunkStart = SystemClock.elapsedRealtime() }
        fun sealChunk() {
            out?.close(); out = null
            val tmp = File(dir, "%05d.tmp".format(idx))
            if (tmp.length() > 0) { tmp.renameTo(File(dir, "%05d.part".format(idx))); idx++; updateStateNext(dir, idx); synchronized(upLock) { upLock.notifyAll() } }
            else tmp.delete()
        }
        try {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 2)
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("麥克風被其他 App 佔用或沒有權限")
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            rec.startRecording()
            openChunk()
            val pcm = ByteArray(4096)
            val info = MediaCodec.BufferInfo()
            var eosSent = false
            var done = false
            while (!done) {
                if (!eosSent) {
                    val inIx = enc.dequeueInputBuffer(10000)
                    if (inIx >= 0) {
                        val buf = enc.getInputBuffer(inIx)!!
                        buf.clear()
                        val n = rec.read(pcm, 0, minOf(pcm.size, buf.remaining()))
                        if (stopReq) {
                            enc.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosSent = true
                        } else if (n > 0) {
                            buf.put(pcm, 0, n)
                            enc.queueInputBuffer(inIx, 0, n, (SystemClock.elapsedRealtime() - startedAt) * 1000, 0)
                        } else enc.queueInputBuffer(inIx, 0, 0, 0, 0)
                    }
                }
                var outIx = enc.dequeueOutputBuffer(info, 10000)
                while (outIx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val ob = enc.getOutputBuffer(outIx)!!
                        val frame = ByteArray(info.size)
                        ob.position(info.offset); ob.get(frame)
                        out?.write(adts(frame.size)); out?.write(frame)
                        recordedBytes += frame.size + 7
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    enc.releaseOutputBuffer(outIx, false)
                    outIx = if (done) -1 else enc.dequeueOutputBuffer(info, 0)
                }
                if (!done && SystemClock.elapsedRealtime() - chunkStart >= CHUNK_SEC * 1000L) { sealChunk(); openChunk() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "錄音失敗", e)
            lastError = "錄音中斷：${e.message}"
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
            try { enc?.stop() } catch (_: Exception) {}
            enc?.release()
            sealChunk()
            val secs = (SystemClock.elapsedRealtime() - startedAt) / 1000
            patchState(dir) { it.put("ended", true).put("chunks", idx).put("seconds", secs) }
            recording = false
            curSid = ""
            wake?.let { if (it.isHeld) it.release() }
            synchronized(upLock) { upLock.notifyAll() }
            updateNote("⬆️ 錄音結束，正在把剩下的部分傳完")
        }
    }

    /** ADTS 檔頭（AAC-LC、16kHz、單聲道）：每個 AAC 音框前面加 7 個位元組，檔案切開再接起來都還是合法的 .aac。 */
    private fun adts(len: Int): ByteArray {
        val full = len + 7
        val profile = 2; val freq = 8; val ch = 1
        return byteArrayOf(0xFF.toByte(), 0xF1.toByte(), (((profile - 1) shl 6) + (freq shl 2) + (ch shr 2)).toByte(),
            (((ch and 3) shl 6) + (full shr 11)).toByte(), ((full and 0x7FF) shr 3).toByte(), (((full and 7) shl 5) + 0x1F).toByte(), 0xFC.toByte())
    }

    // ── 上傳 ──────────────────────────────────────────────────────────────
    private fun ensureUploader() {
        if (upThread?.isAlive == true) { synchronized(upLock) { upLock.notifyAll() }; return }
        upThread = Thread({ uploadLoop() }, "ytmate-up").apply { start() }
    }

    private fun uploadLoop() {
        uploading = true
        var backoff = 2000L
        while (true) {
            val dirs = recRoot(this).listFiles()?.filter { File(it, "state.json").exists() }?.sortedBy { it.name } ?: emptyList()
            if (dirs.isEmpty() && !recording) break
            var progressed = false
            var allBlocked = true
            for (d in dirs) {
                val st = readState(d) ?: continue
                val r = uploadOne(d, st)
                if (r == 1) progressed = true
                if (r != -1) allBlocked = false
            }
            if (!progressed) {
                synchronized(upLock) { upLock.wait(if (allBlocked) backoff else 3000L) }
                backoff = if (allBlocked) minOf(backoff * 2, 60000L) else 2000L
            } else backoff = 2000L
        }
        uploading = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 1＝有進展；0＝沒事可做（等錄音）；-1＝失敗（網路／伺服器），稍後重試。 */
    private fun uploadOne(d: File, st: JSONObject): Int {
        var rid = st.optString("rid")
        val parts = d.listFiles { f -> f.name.endsWith(".part") }?.sortedBy { it.name } ?: emptyList()
        val ended = st.optBoolean("ended")
        if (parts.isEmpty() && !ended) return 0
        if (rid.isEmpty()) {
            val title = st.optString("title")
            val body = JSONObject().put("filename", "手機錄音.aac").put("stream", true).put("title", title)
                .put("kind", st.optString("kind", "meeting")).put("lang", st.optString("lang", "zh"))
                .put("source", Prefs.get(this, "device_name", Build.MODEL))
            val r = Api.json("POST", "/api/recorder/recordings", body)
            if (r.code != 200) { lastError = "上傳暫停：${errText(r)}"; return -1 }
            rid = JSONObject(r.body).optString("id")
            patchState(d) { it.put("rid", rid) }
        }
        for (p in parts) {
            val idx = p.name.removeSuffix(".part").toInt()
            val r = Api.request("PUT", "/api/recorder/recordings/$rid/chunks/$idx", p.readBytes(), "application/octet-stream", 60000)
            if (r.code != 200) { lastError = "上傳暫停：${errText(r)}"; return -1 }
            p.delete()
            val up = patchState(d) { it.put("uploaded", it.optInt("uploaded") + 1) }?.optInt("uploaded") ?: 0
            lastError = ""
            if (recording) updateNote("🔴 錄音中，已上傳 $up 段")
        }
        if (ended) {
            val n = st.optInt("chunks")
            val r = Api.json("POST", "/api/recorder/recordings/$rid/finish", JSONObject().put("chunks", n))
            if (r.code == 200 || (r.code == 400 && r.body.contains("已經上傳完成"))) {
                d.deleteRecursively()
                lastDone = "✅ 錄音（${st.optLong("seconds") / 60} 分鐘）已全部上傳，YTmate 正在轉文字和做摘要"
                return 1
            }
            if (n == 0) { d.deleteRecursively(); return 1 }      // 什麼都沒錄到
            lastError = "結束上傳失敗：${errText(r)}"
            return -1
        }
        return if (parts.isNotEmpty()) 1 else 0
    }

    private fun errText(r: Api.Resp): String = if (r.code < 0) "沒有網路" else try { JSONObject(r.body).optString("error", "HTTP ${r.code}") } catch (_: Exception) { "HTTP ${r.code}" }

    private fun readState(d: File): JSONObject? = try { JSONObject(File(d, "state.json").readText()) } catch (_: Exception) { null }

    @Synchronized
    private fun writeState(d: File, o: JSONObject) {
        val tmp = File(d, "state.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(d, "state.json"))
    }

    private fun updateStateNext(d: File, next: Int) { patchState(d) { it.put("next", next) } }

    /** 讀出最新狀態 → 修改 → 寫回（錄音、上傳兩條執行緒都會改，一律走這裡）。 */
    @Synchronized
    private fun patchState(d: File, fn: (JSONObject) -> JSONObject): JSONObject? {
        val st = readState(d) ?: return null
        val o = fn(st)
        writeState(d, o)
        return o
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
