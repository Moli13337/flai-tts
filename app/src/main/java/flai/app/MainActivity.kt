package flai.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import flai.app.databinding.ActivityMainBinding
import flai.app.databinding.PageReadBinding
import flai.app.databinding.PageSettingsBinding
import flai.app.databinding.PageVoiceBinding
import java.io.File

class MainActivity : Activity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var r: PageReadBinding
    private lateinit var v: PageVoiceBinding
    private lateinit var s: PageSettingsBinding
    private var pool: TokenPool? = null
    private var base = Net.BASES[0]
    private var bookKey: String? = null
    private var segs: List<String> = emptyList()
    private var speaker: String? = null
    private var svc: ReaderService? = null
    private var voicePage: VoicePage? = null
    private lateinit var prefs: android.content.SharedPreferences

    @Volatile
    private var curIdx = 0

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val srv = (binder as ReaderService.LocalBinder).svc
            svc = srv
            srv.onLog = { msg -> log(msg) }
            srv.onProgress = { idx, total, pct ->
                runOnUiThread {
                    if (pct >= 0) r.pbSeg.progress = pct
                    r.tvSeg.text = if (pct >= 0) "段 ${idx + 1}/$total  $pct%" else "段 ${idx + 1}/$total"
                }
            }
            srv.onEnd = {
                runOnUiThread {
                    r.btnPlay.text = "播放"
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            if (srv.reading) {
                r.btnPlay.text = "暂停"
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    private fun log(s: String) = runOnUiThread {
        r.tvLog.append(s + "\n")
        val max = r.tvLog.lineCount * r.tvLog.lineHeight - r.tvLog.height
        if (max > 0) r.tvLog.scrollTo(0, max)
    }

    private fun poolOrThrow(): TokenPool? {
        if (pool == null) {
            val p = TokenPool(base) { str -> log(str) }
            val f = File(filesDir, "token.txt")
            if (f.exists()) f.readLines().forEach { line ->
                val t = line.trim()
                if (t.isNotEmpty() && !t.startsWith("#")) p.add(t)
            }
            pool = p
        }
        return pool
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        r = PageReadBinding.bind(b.pageRead.root)
        v = PageVoiceBinding.bind(b.pageVoice.root)
        s = PageSettingsBinding.bind(b.pageSettings.root)
        setContentView(b.root)
        prefs = getSharedPreferences("flai", MODE_PRIVATE)

        applyProxy(prefs.getString("proxy", "") ?: "")
        s.etProxy.hint = "127.0.0.1:8899"

        r.etSpeed.setText(prefs.getString("speed", ""))
        r.etChunk.setText(prefs.getString("chunk", ""))
        r.etPrefetch.setText(prefs.getString("prefetch", ""))
        r.etVoice.setText(prefs.getString("voice", ""))

        r.tvLog.movementMethod = ScrollingMovementMethod()

        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)

        bindService(Intent(this, ReaderService::class.java), conn, BIND_AUTO_CREATE)

        b.tabRead.setOnClickListener { switchTab(0) }
        b.tabVoice.setOnClickListener { switchTab(1) }
        b.tabSettings.setOnClickListener { switchTab(2) }

        r.btnPick.setOnClickListener { pickBook() }
        r.btnResolve.setOnClickListener { resolveVoice(r.etVoice.text.toString().trim()) }
        r.btnPlay.setOnClickListener {
            val srv = svc
            if (srv == null) {
                Toast.makeText(this, "服务未就绪", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!srv.reading) startReading() else {
                val paused = srv.toggle()
                r.btnPlay.text = if (paused) "继续" else "暂停"
            }
        }
        r.btnStop.setOnClickListener {
            svc?.stop()
            r.btnPlay.text = "播放"
        }
        r.btnPrev.setOnClickListener { svc?.prev() }
        r.btnNext.setOnClickListener { svc?.next() }

        v.btnRefresh.setOnClickListener { voicePage?.refresh() }

        s.btnSaveProxy.setOnClickListener {
            val raw = s.etProxy.text.toString().trim()
            applyProxy(raw)
            prefs.edit().putString("proxy", raw).apply()
            Toast.makeText(this, "代理: ${Net.PROXY_HOST}:${Net.PROXY_PORT}", Toast.LENGTH_SHORT).show()
            log("[i] 代理 ${Net.PROXY_HOST}:${Net.PROXY_PORT}")
        }

        s.btnAddToken.setOnClickListener {
            val input = EditText(this)
            android.app.AlertDialog.Builder(this)
                .setTitle("粘贴 uToken (JWT)")
                .setView(input)
                .setPositiveButton("添加") { _, _ ->
                    val t = input.text.toString().trim()
                    if (t.length > 40) {
                        poolOrThrow()?.add(t)
                        File(filesDir, "token.txt").let { f ->
                            val old = if (f.exists()) f.readText() else ""
                            f.writeText(old + (if (old.endsWith("\n") || old.isEmpty()) "" else "\n") + t + "\n")
                        }
                        log("[+] token 已添加")
                        refreshPoolInfo()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        s.btnValidate.setOnClickListener {
            val p = pool ?: poolOrThrow() ?: return@setOnClickListener
            Thread {
                val ok = p.validate()
                runOnUiThread {
                    val text = "号池: ${p.size()}/${p.total()} 可用"
                    r.tvStatus.text = text
                    s.tvPoolInfo.text = text
                    if (!ok) Toast.makeText(this, "无可用 token, 请添加", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        val exVoice = intent.getStringExtra("voice")
        val exFile = intent.getStringExtra("file")
        val exAutoplay = intent.getBooleanExtra("autoplay", false)
        if (exFile != null) {
            Thread {
                try {
                    loadBook(File(exFile!!).readBytes(), exFile!!)
                    if (exVoice != null) {
                        val p = poolOrThrow() ?: return@Thread
                        if (!p.validate()) return@Thread
                        var sp: String? = null
                        val arr = Flai.voiceList(p.next() ?: return@Thread, base)
                        if (arr != null) {
                            for (k in 0 until arr.length()) {
                                val it = arr.getJSONObject(k)
                                if (it.optString("code").equals(exVoice, true)
                                    || it.optString("speaker_id") == exVoice
                                    || it.optString("voice_id").toString() == exVoice
                                    || it.optString("nickname") == exVoice
                                ) {
                                    sp = it.optString("speaker_id")
                                    runOnUiThread { r.tvVoice.text = "[${it.optString("code")}] $sp" }
                                    break
                                }
                            }
                        }
                        if (sp == null) {
                            val d = Flai.voiceInfoByCode(exVoice!!, p.next() ?: return@Thread, base)
                            if (d != null && d.optString("speaker_id").isNotEmpty()) {
                                sp = d.optString("speaker_id")
                                runOnUiThread { r.tvVoice.text = "[${d.optString("code")}] $sp" }
                            }
                        }
                        if (sp != null) {
                            speaker = sp
                            log("[+] 音色 $exVoice -> $sp")
                            if (exAutoplay) runOnUiThread { startReading() }
                        } else log("[-] 音色 $exVoice 无法解析")
                    }
                } catch (e: Exception) {
                    log("[!] auto: ${e.javaClass.simpleName}: ${e.message}")
                }
            }.start()
        }
    }

    private fun switchTab(idx: Int) {
        val pages = listOf(b.pageRead.root, b.pageVoice.root, b.pageSettings.root)
        val tabs = listOf(b.tabRead, b.tabVoice, b.tabSettings)
        for (i in pages.indices) {
            pages[i].visibility = if (i == idx) View.VISIBLE else View.GONE
            val color = if (i == idx) getColor(R.color.primary) else getColor(R.color.tab_inactive)
            tabs[i].setTextColor(color)
            tabs[i].compoundDrawables[1]?.setTint(color)
        }
        if (idx == 1 && voicePage == null) {
            voicePage = VoicePage(this, v.lvVoices, File(externalCacheDir ?: filesDir, "avatars"),
                { pool?.next() ?: poolOrThrow()?.next() }, base, { log(it) }, { code ->
                    runOnUiThread {
                        switchTab(0)
                        r.etVoice.setText(code)
                        resolveVoice(code)
                    }
                })
            voicePage?.refresh()
        }
    }

    private fun applyProxy(raw: String) {
        if (raw.isEmpty() || !raw.contains(":")) {
            Net.PROXY_HOST = "127.0.0.1"
            Net.PROXY_PORT = 8899
            return
        }
        val parts = raw.split(":", limit = 2)
        Net.PROXY_HOST = parts[0]
        Net.PROXY_PORT = parts[1].toIntOrNull() ?: 8899
    }

    private fun refreshPoolInfo() {
        val p = pool
        val text = if (p == null || p.size() == 0) "未校验" else "号池: ${p.size()}/${p.total()} 可用"
        r.tvStatus.text = text
        s.tvPoolInfo.text = text
    }

    private fun pickBook() {
        if (!android.os.Environment.isExternalStorageManager()) {
            log("[i] 需要所有文件访问权限, 跳转设置中请授权后返回")
            try {
                startActivityForResult(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ), 43
                )
            } catch (e: Exception) {
                startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), 43)
            }
            return
        }
        val input = EditText(this)
        input.hint = "/sdcard/Download/novel.txt"
        android.app.AlertDialog.Builder(this)
            .setTitle("TXT 文件路径")
            .setView(input)
            .setPositiveButton("读取") { _, _ ->
                val path = input.text.toString().trim()
                Thread {
                    try {
                        val bytes = File(path).readBytes()
                        loadBook(bytes, path)
                    } catch (e: Exception) {
                        log("[!] 读取失败: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }.start()
            }
            .setNeutralButton("SAF 选择") { _, _ ->
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/*"
                    }, 42
                )
            }
            .show()
    }

    private fun resolveVoice(vin: String) {
        val p = pool ?: poolOrThrow() ?: return
        val vv = vin.trim()
        if (vv.isEmpty()) {
            Toast.makeText(this, "先输入音色", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                var sp: String? = null
                var tag = vv
                val arr = Flai.voiceList(p.next() ?: return@Thread, base)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val it = arr.getJSONObject(i)
                        if (it.optString("code").equals(vv, true) || it.optString("speaker_id") == vv
                            || it.optString("voice_id").toString() == vv || it.optString("nickname") == vv
                        ) {
                            sp = it.optString("speaker_id")
                            tag = it.optString("code")
                            break
                        }
                    }
                }
                if (sp == null) {
                    val d = Flai.voiceInfoByCode(vv, p.next() ?: return@Thread, base)
                    if (d != null && d.optString("speaker_id").isNotEmpty()) {
                        sp = d.optString("speaker_id")
                        tag = d.optString("code")
                    }
                }
                runOnUiThread {
                    if (sp != null) {
                        speaker = sp
                        r.tvVoice.text = "[$tag] $sp"
                        prefs.edit().putString("voice", tag).apply()
                        log("[+] 音色 '$vv' -> $sp [$tag]")
                    } else {
                        log("[-] 音色 '$vv' 无法解析")
                    }
                }
            } catch (e: Exception) {
                log("[!] ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun loadBook(bytes: ByteArray, path: String) {
        bookKey = md5hex(bytes)
        val chunk = r.etChunk.text.toString().toIntOrNull() ?: CHUNK
        val text = bytes.decodeToString()
        val seg = Segmenter.split(text, chunk)
        segs = seg
        runOnUiThread {
            r.tvBook.text = "$path (${bytes.size}B, ${text.length} 字) -> ${seg.size} 段"
            log("[+] 书已加载: ${seg.size} 段")
        }
        val prog = ProgressStore(filesDir, bookKey!!)
        if (prog.speaker == speaker && prog.total != null && prog.idx < (prog.total ?: 0)) {
            curIdx = prog.idx
            runOnUiThread { log("[+] 续读: 第 ${curIdx + 1} 段") }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 43) {
            if (android.os.Environment.isExternalStorageManager()) {
                log("[+] 已授权所有文件访问")
                r.btnPick.performClick()
            } else {
                log("[!] 未授权, 仅可用 SAF 选择")
            }
            return
        }
        if (requestCode == 42 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                Thread {
                    try {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
                        loadBook(bytes, uri.lastPathSegment ?: "unknown")
                    } catch (e: Exception) {
                        log("[!] 读文件失败: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }.start()
            }
        }
    }

    private fun startReading() {
        if (segs.isEmpty() || speaker == null) {
            Toast.makeText(this, "先选书并解析音色", Toast.LENGTH_SHORT).show()
            return
        }
        val p = poolOrThrow() ?: return
        if (p.size() == 0) {
            Toast.makeText(this, "号池无可用 token", Toast.LENGTH_SHORT).show()
            return
        }
        val srv = svc
        if (srv == null) {
            Toast.makeText(this, "服务未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val speed = r.etSpeed.text.toString().toDoubleOrNull() ?: 1.0
        val prefetch = (r.etPrefetch.text.toString().toDoubleOrNull() ?: 0.5).coerceIn(0.0, 1.0)
        val chunk = r.etChunk.text.toString().toIntOrNull() ?: CHUNK
        prefs.edit()
            .putString("speed", r.etSpeed.text.toString())
            .putString("chunk", r.etChunk.text.toString())
            .putString("prefetch", r.etPrefetch.text.toString())
            .apply()
        ReaderService.start(this)
        r.btnPlay.text = "暂停"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        srv.launch(segs, speaker!!, speed, chunk, prefetch, bookKey!!, p, base, curIdx)
    }

    override fun onDestroy() {
        svc?.let { srv ->
            srv.onLog = null
            srv.onProgress = null
            srv.onEnd = null
        }
        unbindService(conn)
        svc = null
        super.onDestroy()
    }

    companion object {
        const val CHUNK = 250
    }
}
