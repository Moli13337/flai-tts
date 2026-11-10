package flai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ReaderService : Service() {

    inner class LocalBinder : Binder() {
        val svc: ReaderService get() = this@ReaderService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    private val player: Mp3Player by lazy { Mp3Player(this) }
    private var pool: TokenPool? = null
    private var baseUrl = Net.BASES[0]
    private var segs: List<String> = emptyList()
    private var speaker: String? = null
    private var speed = 1.0
    private var prefetch = 0.5
    private var cache: CacheManager? = null
    private var prog: ProgressStore? = null

    @Volatile
    var reading = false
        private set

    @Volatile
    var curIdx = 0
        private set

    @Volatile
    private var skipTo = -1

    var onLog: ((String) -> Unit)? = null
    var onProgress: ((Int, Int, Int) -> Unit)? = null
    var onEnd: (() -> Unit)? = null

    private fun slog(s: String) {
        onLog?.invoke(s)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notif(this, "准备朗读..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    fun launch(segs: List<String>, speaker: String, speed: Double, chunk: Int, prefetch: Double,
               bookKey: String, pool: TokenPool, baseUrl: String, startIdx: Int) {
        if (reading) return
        this.segs = segs
        this.speaker = speaker
        this.speed = speed
        this.prefetch = prefetch
        this.pool = pool
        this.baseUrl = baseUrl
        cache = CacheManager(File(externalCacheDir ?: filesDir, "tts_cache"), speaker, speed)
        val prog = ProgressStore(filesDir, bookKey)
        prog.resetIfNeeded(speaker, speed, chunk) { slog(it) }
        this.prog = prog
        reading = true
        runLoop(startIdx)
    }

    fun toggle(): Boolean {
        player.paused = !player.paused
        return player.paused
    }

    fun stop() {
        reading = false
        player.stop()
    }

    fun prev() {
        if (reading) skipTo = (curIdx - 1).coerceAtLeast(0)
        player.stop()
    }

    fun next() {
        if (reading) skipTo = curIdx + 1
        player.stop()
    }

    private fun runLoop(startIdx: Int) {
        val segs = this.segs
        val sp = speaker ?: return
        val pool = this.pool ?: return
        val cache = this.cache ?: return
        val prog = this.prog ?: return
        val pre = ConcurrentHashMap<Int, ByteArray>()
        val worker = arrayOf<Thread?>(null)

        fun prefetch(idx: Int) {
            if (idx >= segs.size || pre.containsKey(idx) || cache.cached(segs[idx]) != null) return
            val t = Thread {
                try {
                    val f = Reader.synth(segs[idx], sp, speed,
                        if (idx > 0) listOf(segs[idx - 1]) else null, pool, baseUrl, cache)
                    if (f != null) {
                        pre[idx] = f.readBytes()
                        slog("[i] 预取段 ${idx + 1} 完成")
                    }
                } catch (e: InterruptedException) {
                } catch (e: Exception) {
                    slog("[!] 预取失败: ${e.message}")
                }
            }
            worker[0] = t
            t.start()
        }

        Thread {
            var i = startIdx.coerceIn(0, segs.size - 1)
            try {
                while (reading) {
                    if (i >= segs.size) {
                        prog.save(segs.size, segs.size)
                        slog("[+] 读完")
                        break
                    }
                    curIdx = i
                    onProgress?.invoke(i, segs.size, -1)
                    try {
                        if (worker[0]?.isAlive == true) {
                            slog("[段 ${i + 1}] 等预取...")
                            worker[0]!!.join()
                            worker[0] = null
                        }
                        var mp3 = pre.remove(i)
                        if (mp3 == null) cache.cached(segs[i])?.let { mp3 = it.readBytes() }
                        if (mp3 == null) {
                            slog("[段 ${i + 1}/${segs.size}] 合成 <<${segs[i].take(18)}>>")
                            val f = Reader.synth(segs[i], sp, speed,
                                if (i > 0) listOf(segs[i - 1]) else null, pool, baseUrl, cache)
                            if (f == null) {
                                prog.save(i, segs.size)
                                slog("[-] 合成失败, 进度已存")
                                break
                            }
                            mp3 = f.readBytes()
                            slog("[段 ${i + 1}] ${mp3.size}B")
                        }
                        prog.save(i, segs.size)
                        var preFired = false
                        player.stopped = false
                        val done = player.play(mp3) { pct ->
                            if (!preFired && pct >= (prefetch * 100).toInt()) {
                                preFired = true
                                prefetch(i + 1)
                            }
                            update(this@ReaderService, "段 ${i + 1}/${segs.size}  $pct%")
                            onProgress?.invoke(i, segs.size, pct)
                        }
                        if (player.paused) {
                            player.paused = false
                            continue
                        }
                        if (!done) {
                            val skip = skipTo >= 0
                            if (!skip) {
                                slog("[i] 中断于第 ${i + 1} 段, 进度已存")
                                break
                            }
                        }
                        i = if (skipTo >= 0) skipTo.coerceIn(0, segs.size - 1) else i + 1
                        skipTo = -1
                        prog.save(i, segs.size)
                    } catch (e: Exception) {
                        slog("[!] 段 ${i + 1} 异常: ${e.javaClass.simpleName}: ${e.message}, 跳过")
                        i += 1
                    }
                }
            } catch (e: InterruptedException) {
                prog.save(i, segs.size)
                slog("[i] 已停止, 进度已存")
            }
            finish()
        }.start()
    }

    private fun finish() {
        reading = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onEnd?.invoke()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val ID = 1

        private fun notif(c: Context, text: String): Notification {
            val pi = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("蝴蝶体育朗读")
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        }

        fun start(c: Context) {
            val app = c.applicationContext
            app.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "朗读播放", NotificationManager.IMPORTANCE_LOW))
            app.startForegroundService(Intent(app, ReaderService::class.java))
        }

        fun update(c: Context, text: String) {
            val app = c.applicationContext
            app.getSystemService(NotificationManager::class.java).notify(ID, notif(app, text))
        }
    }
}
