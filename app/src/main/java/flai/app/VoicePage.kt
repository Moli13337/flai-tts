package flai.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.AbsListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class VoicePage(
    private val activity: Activity,
    lv: ListView,
    private val avatarDir: File,
    private val provideToken: () -> String?,
    private val base: String,
    private val log: (String) -> Unit,
    private val onPick: (String) -> Unit
) : BaseAdapter() {

    private val items = mutableListOf<org.json.JSONObject>()
    private var page = 0
    private var loading = false
    private var done = false
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {}

    init {
        lv.adapter = this
        lv.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                if (!loading && !done && total > 0 && first + visible >= total - 3) loadNext()
            }
            override fun onScrollStateChanged(view: AbsListView?, state: Int) {}
        })
        avatarDir.mkdirs()
    }

    fun refresh() {
        page = 0
        done = false
        items.clear()
        notifyDataSetChanged()
        loadNext()
    }

    private fun loadNext() {
        val token = provideToken() ?: run { log("[!] 无可用 token, 请先在设置中添加"); return }
        loading = true
        val p = page + 1
        Thread {
            try {
                val arr = Flai.voiceList(token, base, p)
                main.post {
                    loading = false
                    if (arr == null || arr.length() == 0) {
                        done = true
                        if (p > 1) log("[i] 没有更多了")
                        return@post
                    }
                    for (i in 0 until arr.length()) items.add(arr.getJSONObject(i))
                    page = p
                    notifyDataSetChanged()
                }
            } catch (e: Exception) {
                main.post {
                    loading = false
                    log("[!] 声音列表失败: ${e.message}")
                }
            }
        }.start()
    }

    override fun getCount(): Int = items.size
    override fun getItem(pos: Int): Any = items[pos]
    override fun getItemId(pos: Int): Long = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_voice, parent, false)
        val item = items[pos]
        val iv = view.findViewById<ImageView>(R.id.ivAvatar)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        val tvMeta = view.findViewById<TextView>(R.id.tvMeta)
        val tvCode = view.findViewById<TextView>(R.id.tvCode)

        val title = item.optString("title", item.optString("nickname"))
        val nick = item.optString("nickname")
        val gender = if (item.optInt("gender") == 1) "男声" else "女声"
        tvTitle.text = if (title == nick) title else "$title · $nick"
        tvMeta.text = "$gender · 赞 ${item.optInt("likes")} · ${item.optString("speaker_id")}"
        tvCode.text = item.optString("code")

        view.setOnClickListener {
            onPick(item.optString("code"))
        }
        tvCode.setOnClickListener {
            val cm = activity.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("code", item.optString("code")))
            Toast.makeText(activity, "已复制 ${item.optString("code")}", Toast.LENGTH_SHORT).show()
        }

        iv.tag = item.optString("avatar_url")
        iv.setImageResource(0)
        loadAvatar(item.optString("avatar_url"), iv)
        return view
    }

    private fun loadAvatar(name: String, iv: ImageView) {
        if (name.isEmpty()) return
        mem.get(name)?.let {
            if (iv.tag == name) iv.setImageBitmap(it)
            return
        }
        pool.execute {
            var bmp = readDisk(name)
            if (bmp == null) {
                val raw = Net.directGet(
                    "https://img.imgcd.com/cdn-cgi/imagedelivery/AaqG8Mefo3pS9ERxqUm9Cg/$name/public"
                ) ?: return@execute
                bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@execute
                try {
                    File(avatarDir, "$name.img").writeBytes(raw)
                } catch (e: Exception) {
                }
            }
            mem.put(name, bmp)
            main.post {
                if (iv.tag == name) iv.setImageBitmap(bmp)
            }
        }
    }

    private fun readDisk(name: String): Bitmap? {
        return try {
            val f = File(avatarDir, "$name.img")
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (e: Exception) {
            null
        }
    }
}
