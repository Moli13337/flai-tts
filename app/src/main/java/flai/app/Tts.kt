package flai.app

import java.io.File
import java.util.regex.Pattern

object Segmenter {
    private val DECOR = Pattern.compile("^[\\s=\\-~*_·—…#>》（）()\\[\\]【】｜|,，.。·✦★☆❤—]*$")
    private val VALID = Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af a-zA-Z0-9]")
    private val SPLIT = Pattern.compile("(?<=[。！？!?；;\\n])")

    fun split(text: String, maxLen: Int): List<String> {
        val cleaned = text.lines().filterNot { DECOR.matcher(it).matches() }.joinToString("\n")
        val parts = SPLIT.split(cleaned)
        val segs = mutableListOf<String>()
        var cur = ""
        for (p in parts) {
            val s = p.trim('\r', '\n')
            if (s.isEmpty()) continue
            if (cur.isNotEmpty() && cur.length + s.length > maxLen) {
                segs.add(cur)
                cur = s
            } else {
                cur += s
            }
            while (cur.length > maxLen) {
                segs.add(cur.substring(0, maxLen))
                cur = cur.substring(maxLen)
            }
        }
        if (cur.isNotEmpty()) segs.add(cur)
        return segs.filter { VALID.matcher(it).find() }
    }
}

class ProgressStore(dir: File, bookKey: String) {
    private val file = File(dir, "$bookKey.progress.json")
    var idx = 0
    var total: Int? = null
    var speaker: String? = null
    var speed: Double = 1.0
    var chunk: Int = 250

    init {
        try {
            if (file.exists()) {
                val o = org.json.JSONObject(file.readText())
                if (o.optString("md5") == bookKey) {
                    idx = o.optInt("idx", 0)
                    total = if (o.has("total")) o.optInt("total") else null
                    speaker = o.optString("speaker", null)
                    speed = o.optDouble("speed", 1.0)
                    chunk = o.optInt("chunk", 250)
                }
            }
        } catch (e: Exception) {
        }
    }

    fun resetIfNeeded(speaker: String, speed: Double, chunk: Int, report: (String) -> Unit): Boolean {
        if (this.speaker == speaker && this.speed == speed && this.chunk == chunk) return false
        if (idx > 0) report("[!] 参数变化, 从头开始 (缓存仍有效)")
        this.speaker = speaker
        this.speed = speed
        this.chunk = chunk
        this.idx = 0
        return true
    }

    fun save(idx: Int, total: Int) {
        this.idx = idx
        this.total = total
        try {
            val o = org.json.JSONObject()
                .put("md5", file.nameWithoutExtension)
                .put("idx", idx)
                .put("total", total)
                .put("speaker", speaker ?: "")
                .put("speed", speed)
                .put("chunk", chunk)
            file.writeText(o.toString())
        } catch (e: Exception) {
        }
    }
}

class CacheManager(val dir: File, val speaker: String, val speed: Double) {
    init {
        dir.mkdirs()
    }

    fun path(seg: String): File {
        val h = md5hex("$speaker|$speed|$seg".toByteArray())
        return File(dir, "${h}_${(speed * 100).toInt().toString().padStart(3, '0')}.mp3")
    }

    fun cached(seg: String): File? = path(seg).takeIf { it.exists() && it.length() > 200 }

    fun encodeToCache(pcm: ByteArray, seg: String): File {
        Lame.init(16000, 1, 96)
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        val frame = 16000 * 2
        while (off < pcm.size) {
            val n = minOf(frame, pcm.size - off)
            val slice = pcm.copyOfRange(off, off + n)
            Lame.encode(slice)?.let { out.write(it) }
            off += n
        }
        Lame.flush()?.let { out.write(it) }
        val cp = path(seg)
        val tmp = File(cp.absolutePath + ".tmp" + Thread.currentThread().id)
        tmp.writeBytes(out.toByteArray())
        if (!tmp.renameTo(cp)) {
            cp.delete()
            tmp.renameTo(cp)
        }
        return cp
    }
}

object Reader {
    fun synth(seg: String, speaker: String, speed: Double, last: List<String>?,
              pool: TokenPool, base: String, cache: CacheManager): File? {
        cache.cached(seg)?.let { return it }
        var token = pool.next() ?: return null
        for (attempt in 0 until 5) {
            try {
                val pcm = Flai.tts(seg, last ?: emptyList(), speaker, speed, token, base)
                if (pcm != null) return cache.encodeToCache(pcm, seg)
                if (attempt >= 4) return null
                val wait = listOf(2L, 4L, 8L, 15L, 30L)[attempt]
                Thread.sleep(wait * 1000)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                val wait = listOf(2L, 4L, 8L, 15L, 30L)[attempt]
                Thread.sleep(wait * 1000)
            }
        }
        return null
    }
}

class Mp3Player(context: android.content.Context) {
    private val app = context.applicationContext
    private var mp: android.media.MediaPlayer? = null

    @Volatile
    var paused = false

    @Volatile
    var stopped = false

    fun play(data: ByteArray, onPos: (Int) -> Unit): Boolean {
        stop()
        paused = false
        stopped = false
        val p = android.media.MediaPlayer()
        mp = p
        try {
            p.setDataSource(object : android.media.MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= data.size) return -1
                    val n = minOf(size.toLong(), data.size - position).toInt()
                    System.arraycopy(data, position.toInt(), buffer, offset, n)
                    return n
                }
                override fun getSize(): Long = data.size.toLong()
                override fun close() {}
            })
            p.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setWakeMode(app, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            p.prepare()
            val total = p.duration.coerceAtLeast(1)
            var done = false
            var wasPaused = false
            p.setOnCompletionListener { done = true }
            p.start()
            while (!stopped && !done) {
                if (paused != wasPaused) {
                    if (paused) p.pause() else p.start()
                    wasPaused = paused
                }
                onPos(p.currentPosition * 100 / total)
                Thread.sleep(100)
            }
            val natural = done && !stopped
            return natural
        } finally {
            try {
                p.stop()
            } catch (e: Exception) {
            }
            try {
                p.release()
            } catch (e: Exception) {
            }
            mp = null
        }
    }

    fun stop() {
        stopped = true
        try {
            mp?.stop()
        } catch (e: Exception) {
        }
    }
}
