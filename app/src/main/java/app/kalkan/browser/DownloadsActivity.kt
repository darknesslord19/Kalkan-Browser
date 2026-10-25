package app.kalkan.browser

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Uygulama içi indirme yöneticisi ekranı. */
class DownloadsActivity : AppCompatActivity() {

    private var items: List<DownloadEngine.Task> = emptyList()
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private lateinit var empty: TextView
    private val listener: () -> Unit = { refresh() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "İndirme yöneticisi"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        empty = TextView(this).apply {
            text = "Henüz indirme yok.\nSayfalardaki dosyalar ve Media Sniffer'dan indirilenler burada görünür."
            gravity = Gravity.CENTER
            setTextColor(color(R.color.muted))
            setPadding(dp(24), dp(64), dp(24), 0)
        }
        root.addView(empty)
        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        rv.setPadding(dp(12), dp(10), dp(12), dp(24))
        rv.clipToPadding = false
        rv.itemAnimator = null
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val card = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_tile)
                    setPadding(dp(16), dp(12), dp(6), dp(14))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    }
                }
                val top = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val col = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
                val name = TextView(parent.context).apply {
                    textSize = 15f; maxLines = 2
                    setTextColor(color(R.color.onBar))
                }
                val info = TextView(parent.context).apply {
                    textSize = 12.5f; maxLines = 2
                    setTextColor(color(R.color.muted))
                    setPadding(0, dp(3), 0, 0)
                }
                col.addView(name); col.addView(info)
                top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fun btn(icon: Int) = ImageButton(parent.context).apply {
                    setImageResource(icon)
                    setBackgroundResource(tv.resourceId)
                    imageTintList = ColorStateList.valueOf(color(R.color.onBar))
                }
                val action = btn(R.drawable.ic_pause)
                val more = btn(R.drawable.ic_more)
                top.addView(action, LinearLayout.LayoutParams(dp(44), dp(44)))
                top.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
                card.addView(top)
                val bar = LinearProgressIndicator(parent.context).apply {
                    max = 100
                    trackCornerRadius = dp(3)
                    trackThickness = dp(5)
                    setIndicatorColor(color(R.color.brand))
                }
                card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10); marginEnd = dp(10)
                })
                card.tag = arrayOf<View>(name, info, action, more, bar)
                return object : RecyclerView.ViewHolder(card) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val t = items[position]
                @Suppress("UNCHECKED_CAST")
                val v = holder.itemView.tag as Array<View>
                (v[0] as TextView).text = t.name
                (v[1] as TextView).text = describe(t)
                val bar = v[4] as LinearProgressIndicator
                when (t.status) {
                    DownloadEngine.DONE -> bar.visibility = View.GONE
                    else -> {
                        bar.visibility = View.VISIBLE
                        val p = t.percent
                        if (p < 0 && t.status == DownloadEngine.RUNNING) {
                            if (!bar.isIndeterminate) { bar.visibility = View.INVISIBLE; bar.isIndeterminate = true; bar.visibility = View.VISIBLE }
                        } else {
                            if (bar.isIndeterminate) { bar.visibility = View.INVISIBLE; bar.isIndeterminate = false; bar.visibility = View.VISIBLE }
                            bar.setProgressCompat(p.coerceAtLeast(0), false)
                        }
                    }
                }
                val action = v[2] as ImageButton
                when (t.status) {
                    DownloadEngine.RUNNING, DownloadEngine.QUEUED -> {
                        action.setImageResource(R.drawable.ic_pause)
                        action.setOnClickListener { DownloadEngine.pause(t) }
                    }
                    DownloadEngine.PAUSED -> {
                        action.setImageResource(R.drawable.ic_play)
                        action.setOnClickListener { DownloadEngine.resume(t) }
                    }
                    DownloadEngine.FAILED -> {
                        action.setImageResource(R.drawable.ic_refresh)
                        action.setOnClickListener { DownloadEngine.resume(t) }
                    }
                    else -> {
                        action.setImageResource(R.drawable.ic_open)
                        action.setOnClickListener { open(t) }
                    }
                }
                v[3].setOnClickListener { menu(it, t) }
                holder.itemView.setOnClickListener { if (t.status == DownloadEngine.DONE) open(t) }
            }
        }
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        DownloadEngine.addListener(listener)
        refresh()
    }

    override fun onPause() {
        DownloadEngine.removeListener(listener)
        super.onPause()
    }

    private fun refresh() {
        items = DownloadEngine.list()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun describe(t: DownloadEngine.Task): String {
        val size = if (t.total > 0) "${MediaSniffer.formatSize(t.done)} / ${MediaSniffer.formatSize(t.total)}"
        else MediaSniffer.formatSize(t.done)
        return when (t.status) {
            DownloadEngine.RUNNING -> {
                val speed = if (t.speed > 0) " · ${MediaSniffer.formatSize(t.speed)}/sn" else ""
                val eta = if (t.speed > 0 && t.total > 0) {
                    val s = (t.total - t.done) / t.speed
                    " · " + (if (s >= 3600) "${s / 3600} sa ${(s % 3600) / 60} dk" else if (s >= 60) "${s / 60} dk ${s % 60} sn" else "$s sn") + " kaldı"
                } else ""
                val parts = if (t.parts.size > 1) " · ${t.parts.size} parça" else ""
                size + speed + eta + parts
            }
            DownloadEngine.QUEUED -> "Sırada · $size"
            DownloadEngine.PAUSED -> "Duraklatıldı · $size" + if (!t.resumable) " · baştan başlar" else ""
            DownloadEngine.FAILED -> "Başarısız: ${t.error ?: "hata"}"
            else -> "Tamamlandı · ${MediaSniffer.formatSize(t.total)} · İndirilenler/Kalkan"
        }
    }

    private fun open(t: DownloadEngine.Task) {
        val u = t.uri?.let { Uri.parse(it) }
        if (u == null || u.scheme != "content") {
            openSystem(); return
        }
        val mime = t.mime ?: contentResolver.getType(u) ?: "*/*"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "Bu dosyayı açacak uygulama yok", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(t: DownloadEngine.Task) {
        val u = t.uri?.let { Uri.parse(it) }
        val i = Intent(Intent.ACTION_SEND)
        if (u != null && u.scheme == "content") {
            i.setType(t.mime ?: "*/*").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            i.setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url)
        }
        startActivity(Intent.createChooser(i, "Paylaş"))
    }

    private fun openSystem() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            Toast.makeText(this, "İndirilenler açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun menu(anchor: View, t: DownloadEngine.Task) {
        val pm = PopupMenu(this, anchor)
        if (t.status == DownloadEngine.DONE) {
            pm.menu.add(0, 1, 0, "Aç")
            pm.menu.add(0, 2, 1, "Paylaş")
        }
        pm.menu.add(0, 3, 2, "Bağlantıyı kopyala")
        pm.menu.add(0, 4, 3, "Listeden kaldır")
        if (t.status == DownloadEngine.DONE) pm.menu.add(0, 5, 4, "Dosyayla birlikte sil")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> open(t)
                2 -> share(t)
                3 -> {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", t.url))
                    Toast.makeText(this, "Kopyalandı", Toast.LENGTH_SHORT).show()
                }
                4 -> DownloadEngine.remove(t, false)
                5 -> DownloadEngine.remove(t, true)
            }
            true
        }
        pm.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Tamamlananları temizle")
        menu.add(0, 2, 1, "Sistem indirmelerini aç")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> DownloadEngine.clearFinished()
            2 -> openSystem()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
