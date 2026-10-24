package app.kalkan.browser

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

/** Geçmiş ve yer imleri listesi. */
class LibraryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
    }

    private lateinit var mode: String
    private var items: List<Entry> = emptyList()
    private var query = ""
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private lateinit var empty: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_HISTORY
        title = if (mode == MODE_HISTORY) "Geçmiş" else "Yer imleri"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val search = EditText(this).apply {
            hint = "Ara"
            setSingleLine()
            setBackgroundResource(R.drawable.bg_url)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextColor(ContextCompat.getColor(context, R.color.onBar))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    reload()
                }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        empty = TextView(this).apply {
            text = if (mode == MODE_HISTORY) "Geçmiş boş" else "Henüz yer imi yok.\nMenüden \"Yer imi ekle\"ye dokun."
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
            setTextColor(ContextCompat.getColor(context, R.color.muted))
        }
        root.addView(empty)

        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val col = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    setBackgroundResource(tv.resourceId)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val t1 = TextView(parent.context).apply {
                    textSize = 15f; maxLines = 1
                    setTextColor(ContextCompat.getColor(context, R.color.onBar))
                }
                val t2 = TextView(parent.context).apply {
                    textSize = 12f; maxLines = 1
                    setTextColor(ContextCompat.getColor(context, R.color.muted))
                }
                col.addView(t1); col.addView(t2)
                col.tag = Pair(t1, t2)
                return object : RecyclerView.ViewHolder(col) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = items[position]
                @Suppress("UNCHECKED_CAST")
                val pair = holder.itemView.tag as Pair<TextView, TextView>
                val t1 = pair.first
                val t2 = pair.second
                t1.text = e.title.ifEmpty { e.url }
                val u = e.url.removePrefix("https://").removePrefix("http://")
                t2.text = if (mode == MODE_HISTORY) "${fmt.format(Date(e.time))} · $u" else u
                holder.itemView.setOnClickListener { open(e.url) }
                holder.itemView.setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@LibraryActivity)
                        .setTitle(e.title.ifEmpty { u })
                        .setItems(arrayOf("Aç", "Sil")) { _, w ->
                            if (w == 0) open(e.url) else {
                                if (mode == MODE_HISTORY) Store.removeHistory(e.url) else Store.removeBookmark(e.url)
                                reload()
                            }
                        }.show()
                    true
                }
            }
        }
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        reload()
    }

    private fun reload() {
        items = if (mode == MODE_HISTORY) Store.historyList(query) else Store.bookmarkList(query)
        empty.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun open(url: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (mode == MODE_HISTORY) menu.add(0, 1, 0, "Geçmişi temizle")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            1 -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Tüm geçmiş silinsin mi?")
                    .setPositiveButton("Sil") { _, _ -> Store.clearHistory(); reload() }
                    .setNegativeButton("Vazgeç", null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
