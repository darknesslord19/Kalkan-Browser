package app.kalkan.browser

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.Collections
import java.util.Locale

// =====================================================================
// Sekme küçük resimleri
// =====================================================================

/** Sekmenin görünen alanının küçük bir önizlemesini alır. */
internal fun MainActivity.captureThumb(tab: MainActivity.Tab) {
    val wv = tab.webView
    val w = wv.width
    val h = wv.height
    if (w <= 0 || h <= 0 || wv.parent == null) return
    try {
        val scale = 0.32f
        val cropH = minOf(h, (w * 1.3f).toInt())
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (cropH * scale).toInt().coerceAtLeast(1)
        val bmp = tab.thumb?.takeIf { it.width == bw && it.height == bh && !it.isRecycled }
            ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        c.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
        wv.draw(c)
        tab.thumb = bmp
    } catch (_: Throwable) {
    }
}

private fun MainActivity.tabTitle(t: MainActivity.Tab): String = when {
    isHome(t.url) || t.url.isEmpty() -> if (t.incognito) "Gizli sekme" else "Yeni sekme"
    t.title.isNotBlank() && t.title != t.url && !t.title.startsWith("http") -> t.title
    else -> hostOf(t.url).removePrefix("www.").ifEmpty { displayUrl(t.url) }
}

private fun MainActivity.tabSubtitle(t: MainActivity.Tab): String =
    if (isHome(t.url) || t.url.isEmpty()) "Kalkan" else displayUrl(t.url)

private val LETTER_COLORS = intArrayOf(
    0xFFF5A524.toInt(), 0xFF3E8EF7.toInt(), 0xFF30A46C.toInt(), 0xFFE5484D.toInt(),
    0xFF8E4EC6.toInt(), 0xFF12A594.toInt(), 0xFFD6409F.toInt(), 0xFF6E56CF.toInt()
)

/** Sitenin simgesi; yoksa renkli baş harf. */
private fun MainActivity.faviconView(size: Int): FrameLayout {
    val f = FrameLayout(this)
    val iv = ImageView(this).apply { id = View.generateViewId() }
    val letter = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 10f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
    }
    f.addView(letter, FrameLayout.LayoutParams(size, size))
    f.addView(iv, FrameLayout.LayoutParams(size, size))
    f.tag = Pair(iv, letter)
    return f
}

private fun MainActivity.bindFavicon(f: FrameLayout, t: MainActivity.Tab) {
    @Suppress("UNCHECKED_CAST")
    val pair = f.tag as Pair<ImageView, TextView>
    val iv = pair.first
    val letter = pair.second
    val fav = t.favicon
    when {
        fav != null && !fav.isRecycled -> {
            iv.visibility = View.VISIBLE
            iv.imageTintList = null
            iv.setImageBitmap(fav)
            letter.visibility = View.GONE
        }
        isHome(t.url) || t.url.isEmpty() -> {
            iv.visibility = View.VISIBLE
            iv.setImageResource(if (t.incognito) R.drawable.ic_incognito else R.drawable.ic_shield)
            iv.imageTintList = if (t.incognito) ColorStateList.valueOf(Color.parseColor("#A78BFA")) else null
            letter.visibility = View.GONE
        }
        else -> {
            iv.visibility = View.GONE
            val host = hostOf(t.url).removePrefix("www.").removePrefix("m.")
            letter.visibility = View.VISIBLE
            letter.text = host.firstOrNull()?.uppercase(Locale("tr")) ?: "?"
            letter.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(LETTER_COLORS[(host.hashCode() and 0x7fffffff) % LETTER_COLORS.size])
            }
        }
    }
}

// =====================================================================
// Sekmeler paneli
// =====================================================================

internal fun MainActivity.showTabs() {
    current?.let { captureThumb(it) }
    val d = BottomSheetDialog(this)
    val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val brand = ContextCompat.getColor(this, R.color.brand)
    val incogBg = Color.parseColor(if (dark) "#2A2140" else "#EDE7FA")
    var showIncognito = current?.incognito == true
    var query = ""
    var gridMode = Store.str("tabs_layout", "grid") == "grid"
    var visible: MutableList<MainActivity.Tab> = ArrayList()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, 0)
    }

    // ---------- Başlık ----------
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(6), dp(6), dp(4))
    }
    header.addView(TextView(this).apply {
        text = "Sekmeler"
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor())
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    fun headerIcon(icon: Int, desc: String) = ImageButton(this).apply {
        setImageResource(icon)
        setBackgroundResource(selectableBg())
        imageTintList = ColorStateList.valueOf(textColor())
        contentDescription = desc
    }
    val searchBtn = headerIcon(R.drawable.ic_search, "Sekmelerde ara")
    val moreBtn = headerIcon(R.drawable.ic_more, "Diğer")
    header.addView(searchBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
    header.addView(moreBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
    root.addView(header)

    // ---------- Normal / Gizli seçici ----------
    val toggle = MaterialButtonToggleGroup(this).apply {
        isSingleSelection = true
        isSelectionRequired = true
    }
    fun segButton(label: String, icon: Int) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        id = View.generateViewId()
        text = label
        setIconResource(icon)
        iconSize = dp(18)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        isAllCaps = false
        insetTop = 0
        insetBottom = 0
    }
    val normalBtn = segButton("Sekmeler", R.drawable.ic_add)
    val incogBtn = segButton("Gizli", R.drawable.ic_incognito)
    toggle.addView(normalBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
    toggle.addView(incogBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
    root.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(16); marginEnd = dp(16); topMargin = dp(2)
    })

    // ---------- Arama ----------
    val search = EditText(this).apply {
        hint = "Açık sekmelerde ara"
        setSingleLine()
        textSize = 14.5f
        setBackgroundResource(R.drawable.bg_url)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search_small, 0, 0, 0)
        compoundDrawablePadding = dp(8)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setTextColor(textColor())
        setHintTextColor(mutedColor())
        visibility = View.GONE
    }
    root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(16); marginEnd = dp(16); topMargin = dp(10)
    })

    // ---------- Boş durum ----------
    val empty = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(48), dp(32), dp(48))
        visibility = View.GONE
    }
    val emptyIcon = ImageView(this).apply { imageTintList = ColorStateList.valueOf(mutedColor()) }
    val emptyTitle = TextView(this).apply {
        textSize = 17f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor())
        setPadding(0, dp(12), 0, dp(4))
    }
    val emptyText = TextView(this).apply { textSize = 13.5f; gravity = Gravity.CENTER; setTextColor(mutedColor()) }
    empty.addView(emptyIcon, LinearLayout.LayoutParams(dp(44), dp(44)))
    empty.addView(emptyTitle); empty.addView(emptyText)
    root.addView(empty)

    // ---------- Liste ----------
    val rv = RecyclerView(this)
    val span = if (resources.configuration.screenWidthDp >= 600) 3 else 2
    val glm = GridLayoutManager(this, if (gridMode) span else 1)
    rv.layoutManager = glm
    rv.clipToPadding = false
    rv.setPadding(dp(10), dp(10), dp(10), dp(16))
    rv.itemAnimator?.changeDuration = 120
    val cardW = (resources.displayMetrics.widthPixels - dp(20)) / span - dp(12)
    val thumbH = (cardW * 1.2f).toInt()

    lateinit var refresh: () -> Unit

    fun closeWithUndo(t: MainActivity.Tab) {
        val url = t.pendingUrl ?: t.url
        val incog = t.incognito
        val pos = tabs.indexOf(t)
        val wasLast = tabs.size == 1
        closeTab(t)
        refresh()
        if (url.isNotEmpty() && !wasLast) {
            Snackbar.make(root, "Sekme kapatıldı", Snackbar.LENGTH_LONG)
                .setAction("Geri al") {
                    val nt = newTab(url, incog, background = true, quiet = true)
                    tabs.remove(nt)
                    tabs.add(pos.coerceIn(0, tabs.size), nt)
                    updateTabCount()
                    refresh()
                }.show()
        }
    }

    val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = visible.size
        override fun getItemViewType(position: Int) = if (gridMode) 0 else 1
        override fun getItemId(position: Int) = visible[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val card = MaterialCardView(parent.context).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
            }
            val col = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), if (viewType == 0) dp(6) else dp(12), dp(2), if (viewType == 0) dp(6) else dp(12))
            }
            val fav = faviconView(dp(if (viewType == 0) 18 else 26))
            top.addView(fav)
            val texts = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(4), 0)
            }
            val title = TextView(parent.context).apply {
                textSize = if (viewType == 0) 13f else 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
            }
            val sub = TextView(parent.context).apply {
                textSize = 12.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                visibility = if (viewType == 1) View.VISIBLE else View.GONE
            }
            texts.addView(title); texts.addView(sub)
            top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val close = ImageButton(parent.context).apply {
                setImageResource(R.drawable.ic_close)
                setBackgroundResource(selectableBg())
                contentDescription = "Sekmeyi kapat"
                scaleX = 0.8f; scaleY = 0.8f
            }
            top.addView(close, LinearLayout.LayoutParams(dp(38), dp(38)))
            col.addView(top)
            val thumb = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = if (viewType == 0) View.VISIBLE else View.GONE
            }
            col.addView(thumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbH))
            card.addView(col)
            card.tag = arrayOf<View>(fav, title, sub, close, thumb)
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val t = visible[position]
            val card = holder.itemView as MaterialCardView
            @Suppress("UNCHECKED_CAST")
            val v = card.tag as Array<View>
            val active = t === current
            card.setCardBackgroundColor(if (t.incognito) incogBg else ContextCompat.getColor(this@showTabs, R.color.field))
            card.strokeWidth = if (active) dp(2) else 0
            card.strokeColor = brand
            bindFavicon(v[0] as FrameLayout, t)
            (v[1] as TextView).apply {
                text = tabTitle(t)
                setTextColor(textColor())
            }
            (v[2] as TextView).apply {
                text = tabSubtitle(t)
                setTextColor(mutedColor())
            }
            (v[3] as ImageButton).apply {
                imageTintList = ColorStateList.valueOf(textColor())
                setOnClickListener { closeWithUndo(t) }
            }
            val thumb = v[4] as ImageView
            if (gridMode) {
                val b = t.thumb
                if (b != null && !b.isRecycled) {
                    thumb.setImageBitmap(b)
                    thumb.setBackgroundColor(Color.WHITE)
                } else {
                    thumb.setImageDrawable(null)
                    thumb.setBackgroundColor(if (t.incognito) Color.parseColor(if (dark) "#1C1630" else "#E2D9F7")
                        else ContextCompat.getColor(this@showTabs, R.color.bar))
                }
            }
            card.setOnClickListener {
                d.dismiss()
                if (t !== current) switchTo(t)
            }
        }
    }
    adapter.setHasStableIds(true)
    rv.adapter = adapter

    // Kaydırarak kapat, basılı tutup sürükleyerek sırala
    ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0 || query.isNotEmpty()) return false
            val a = visible[from]
            val b = visible[to]
            Collections.swap(visible, from, to)
            val ia = tabs.indexOf(a)
            val ib = tabs.indexOf(b)
            if (ia >= 0 && ib >= 0) Collections.swap(tabs, ia, ib)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val pos = vh.bindingAdapterPosition
            if (pos in visible.indices) closeWithUndo(visible[pos]) else refresh()
        }

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean
        ) {
            if (state == ItemTouchHelper.ACTION_STATE_SWIPE) {
                vh.itemView.alpha = 1f - minOf(1f, kotlin.math.abs(dX) / vh.itemView.width)
            }
            if (state == ItemTouchHelper.ACTION_STATE_DRAG) {
                vh.itemView.scaleX = if (active) 1.04f else 1f
                vh.itemView.scaleY = if (active) 1.04f else 1f
            }
            super.onChildDraw(c, rv, vh, dX, dY, state, active)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f
            saveSessionSoon()
        }
    }).attachToRecyclerView(rv)

    val listH = (resources.displayMetrics.heightPixels * 0.62).toInt()
    root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH))

    // ---------- Alt çubuk ----------
    val bottom = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(16))
    }
    val newBtn = MaterialButton(this).apply {
        isAllCaps = false
        textSize = 15f
        setIconResource(R.drawable.ic_add)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        cornerRadius = dp(24)
        insetTop = 0; insetBottom = 0
    }
    bottom.addView(newBtn, LinearLayout.LayoutParams(0, dp(50), 1f))
    root.addView(bottom)

    refresh = {
        val normalCount = tabs.count { !it.incognito }
        val incogCount = tabs.count { it.incognito }
        normalBtn.text = "Sekmeler  $normalCount"
        incogBtn.text = "Gizli  $incogCount"
        visible = tabs.filter { it.incognito == showIncognito }
            .filter { query.isEmpty() || tabTitle(it).contains(query, true) || it.url.contains(query, true) }
            .toMutableList()
        newBtn.text = if (showIncognito) "Yeni gizli sekme" else "Yeni sekme"
        newBtn.backgroundTintList = ColorStateList.valueOf(if (showIncognito) Color.parseColor("#7C5CFF") else brand)
        newBtn.setTextColor(if (showIncognito) Color.WHITE else Color.parseColor("#1A1205"))
        newBtn.iconTint = ColorStateList.valueOf(if (showIncognito) Color.WHITE else Color.parseColor("#1A1205"))
        glm.spanCount = if (gridMode) span else 1
        if (visible.isEmpty()) {
            empty.visibility = View.VISIBLE
            rv.visibility = View.GONE
            when {
                query.isNotEmpty() -> {
                    emptyIcon.setImageResource(R.drawable.ic_search)
                    emptyTitle.text = "Sonuç yok"
                    emptyText.text = "“$query” ile eşleşen sekme bulunamadı."
                }
                showIncognito -> {
                    emptyIcon.setImageResource(R.drawable.ic_incognito)
                    emptyTitle.text = "Gizli sekme yok"
                    emptyText.text = "Gizli sekmelerde geçmiş kaydedilmez, çerezler sekmeler kapanınca silinir."
                }
                else -> {
                    emptyIcon.setImageResource(R.drawable.ic_add)
                    emptyTitle.text = "Açık sekme yok"
                    emptyText.text = "Yeni bir sekme açarak başla."
                }
            }
        } else {
            empty.visibility = View.GONE
            rv.visibility = View.VISIBLE
        }
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
    }

    toggle.check(if (showIncognito) incogBtn.id else normalBtn.id)
    toggle.addOnButtonCheckedListener { _, id, checked ->
        if (!checked) return@addOnButtonCheckedListener
        showIncognito = id == incogBtn.id
        refresh()
    }
    newBtn.setOnClickListener {
        d.dismiss()
        newTab(MainActivity.HOME_URL, showIncognito)
    }
    searchBtn.setOnClickListener {
        if (search.visibility == View.VISIBLE) {
            search.setText("")
            search.visibility = View.GONE
        } else {
            search.visibility = View.VISIBLE
            showKeyboard(search)
        }
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            query = s?.toString()?.trim() ?: ""
            refresh()
        }
    })
    moreBtn.setOnClickListener {
        val pm = PopupMenu(this, moreBtn)
        pm.menu.add(0, 1, 0, if (gridMode) "Liste görünümü" else "Izgara görünümü")
        pm.menu.add(0, 2, 1, "Kapatılan sekmeyi aç")
        pm.menu.add(0, 3, 2, if (showIncognito) "Tüm gizli sekmeleri kapat" else "Tüm sekmeleri kapat")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    gridMode = !gridMode
                    Store.prefs.edit().putString("tabs_layout", if (gridMode) "grid" else "list").apply()
                    refresh()
                }
                2 -> {
                    val last = closedUrls.removeLastOrNull()
                    if (last != null) { d.dismiss(); newTab(last, false) } else toast("Kapatılan sekme yok")
                }
                3 -> {
                    val targets = tabs.filter { it.incognito == showIncognito }
                    if (targets.isEmpty()) { toast("Kapatılacak sekme yok"); return@setOnMenuItemClickListener true }
                    MaterialAlertDialogBuilder(this)
                        .setTitle("${targets.size} sekme kapatılsın mı?")
                        .setPositiveButton("Kapat") { _, _ ->
                            if (showIncognito) {
                                targets.forEach { closeTab(it) }
                                refresh()
                            } else {
                                d.dismiss()
                                closeAllTabs()
                            }
                        }
                        .setNegativeButton("Vazgeç", null)
                        .show()
                }
            }
            true
        }
        pm.show()
    }

    refresh()
    d.setContentView(root)
    d.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.9).toInt()
    d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    d.behavior.isDraggable = true
    d.show()
    val idx = visible.indexOf(current)
    if (idx >= 0) rv.scrollToPosition(idx)
}
