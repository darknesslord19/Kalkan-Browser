package app.kalkan.browser

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Denetim / derleme satırı durumları. */
object St {
    const val WAIT = 0
    const val RUN = 1
    const val OK = 2
    const val AUTO = 3
    const val WARN = 4
    const val FAIL = 5
}

class AuditRow(val key: String, val title: String, val tau: Long = 6000L) {
    @Volatile var state = St.WAIT
    @Volatile var note = ""
    @Volatile private var t0 = 0L

    /** Satır yüzdesi: bekleyen 0, biten 100, çalışan ise zamanla %95'e yaklaşan yumuşak eğri. */
    fun pct(): Int = when (state) {
        St.WAIT -> { t0 = 0L; 0 }
        St.RUN -> {
            val now = System.currentTimeMillis()
            if (t0 == 0L) t0 = now
            (95.0 * (1.0 - Math.exp(-(now - t0).toDouble() / tau))).toInt().coerceIn(1, 95)
        }
        else -> { t0 = 0L; 100 }
    }
}

/** Halka biçimli ilerleme göstergesi: içinde yüzde, %100 olunca durum simgesi (✓ / ✦ / ! / ✕). */
private class RingView(c: Context, private val row: AuditRow, private val col: Int, private val accent: Int, private val sym: String) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 3.2f * d; color = (0x33000000 and 0xFF000000.toInt()) or 0x8A8F98
        alpha = 70
    }
    private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 3.2f * d; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val box = android.graphics.RectF()
    override fun onDraw(cv: android.graphics.Canvas) {
        val p = row.pct()
        val st = row.state
        val pad = 3.2f * d
        box.set(pad, pad, width - pad, height - pad)
        cv.drawOval(box, track)
        arc.color = if (st == St.RUN || st == St.WAIT) accent else col
        cv.drawArc(box, -90f, 360f * p / 100f, false, arc)
        val cy = height / 2f
        if (p >= 100) {
            txt.color = col; txt.textSize = 15f * d
            cv.drawText(sym, width / 2f, cy - (txt.ascent() + txt.descent()) / 2f, txt)
        } else {
            txt.color = if (st == St.WAIT) 0xFF8A8F98.toInt() else accent; txt.textSize = 9.5f * d
            cv.drawText(p.toString(), width / 2f, cy - (txt.ascent() + txt.descent()) / 2f, txt)
        }
        if (st == St.RUN) postInvalidateOnAnimation()
    }
}

private class MaxHeightScroll(c: Context, private val maxH: Int) : ScrollView(c) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}

/**
 * Adım adım ilerleyen, rozetli satırlar ve ilerleme çubuğu olan şık pencere.
 * Tarama ve derleme akışları bunu kullanır.
 */
internal class CsPanel(private val act: MainActivity, title: String, subtitle: String) {
    private val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(22), act.dp(22), act.dp(22), act.dp(14))
    }
    private val fg = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val accent = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())

    private val titleV = TextView(act).apply {
        text = title; textSize = 21f; setTypeface(typeface, Typeface.BOLD); setTextColor(fg)
    }
    private val subV = TextView(act).apply {
        text = subtitle; textSize = 13f; setTextColor(fg); alpha = 0.65f
        setPadding(0, act.dp(2), 0, act.dp(14))
    }
    private val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progress = 0
        progressDrawable?.setTint(accent)
    }
    private val pctV = TextView(act).apply {
        textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(fg); alpha = 0.85f; gravity = Gravity.END
        setPadding(0, act.dp(4), 0, act.dp(10))
    }
    private val chip = TextView(act).apply {
        textSize = 13f; setTypeface(typeface, Typeface.BOLD); visibility = View.GONE
        setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6))
    }
    private val rowsBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val foot = TextView(act).apply {
        textSize = 13f; setTextColor(fg); alpha = 0.85f; visibility = View.GONE
        setPadding(0, act.dp(12), 0, 0)
    }
    private val content = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(rowsBox)
        addView(foot)
    }
    private val scroll = MaxHeightScroll(act, (act.resources.displayMetrics.heightPixels * 0.42f).toInt()).apply {
        addView(content)
        isVerticalScrollBarEnabled = false
    }
    private val buttons = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, act.dp(14), 0, 0)
    }
    private var dlg: AlertDialog? = null
    private var tracked: List<AuditRow>? = null
    private val tick = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val r = tracked
            if (r != null && r.isNotEmpty() && dlg != null) {
                val avg = r.sumOf { it.pct() } / r.size
                bar.progress = avg * 10
                pctV.text = "%$avg"
            }
            if (dlg != null) tick.postDelayed(this, 200)
        }
    }

    init {
        root.addView(titleV)
        root.addView(subV)
        root.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = act.dp(10) })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(8)))
        root.addView(pctV)
        root.addView(scroll)
        root.addView(buttons)
    }

    fun show() {
        dlg = MaterialAlertDialogBuilder(act).setView(ScrollView(act).apply { addView(root) }).setCancelable(false).create().also { it.show(); tick.post(ticker) }
    }

    fun dismiss() { tick.removeCallbacks(ticker); try { dlg?.dismiss() } catch (_: Exception) {}; dlg = null }

    fun setTitle(t: String, sub: String? = null) {
        titleV.text = t
        if (sub != null) subV.text = sub
    }

    fun setProgress(fraction: Float, label: String = "") {
        // Satırlar izleniyorsa yüzdeyi ticker hesaplar (tam %100'e kadar); yalnız satırsız kullanımda elle
        if (tracked != null) return
        bar.progress = (fraction.coerceIn(0f, 1f) * 1000).toInt()
        pctV.text = "%${(fraction * 100).toInt()}"
    }

    fun setFooter(text: String?) {
        foot.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        foot.text = text.orEmpty()
    }

    /** Başlık altında renkli özet rozeti (ör. "Hazırlık %87"). */
    fun setChip(text: String, color: Int) {
        chip.visibility = View.VISIBLE
        chip.text = text
        chip.setTextColor(color)
        chip.background = GradientDrawable().apply {
            cornerRadius = act.dp(20).toFloat()
            setColor((color and 0x00FFFFFF) or 0x26000000)
        }
    }

    fun clearButtons() { buttons.removeAllViews() }

    fun addButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
        val b = if (primary) MaterialButton(act) else
            MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = text
        b.isAllCaps = false
        b.cornerRadius = act.dp(14)
        b.setOnClickListener { onClick() }
        buttons.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(6) })
    }

    private fun color(s: Int) = when (s) {
        St.OK -> 0xFF2E9E5B.toInt()
        St.AUTO -> 0xFF3B82F6.toInt()
        St.WARN -> 0xFFF59E0B.toInt()
        St.FAIL -> 0xFFE5484D.toInt()
        else -> 0xFF8A8F98.toInt()
    }

    private fun symbol(s: Int) = when (s) {
        St.OK -> "✓"
        St.AUTO -> "✦"
        St.WARN -> "!"
        St.FAIL -> "✕"
        else -> "•"
    }

    fun render(rows: List<AuditRow>) {
        tracked = rows
        rowsBox.removeAllViews()
        for (r in rows) {
            val line = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, act.dp(7), 0, act.dp(7))
            }
            val c = color(r.state)
            val badge = RingView(act, r, c, accent, symbol(r.state))
            line.addView(badge, LinearLayout.LayoutParams(act.dp(38), act.dp(38)).apply { rightMargin = act.dp(12); topMargin = act.dp(1) })
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply {
                text = r.title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(fg)
                alpha = if (r.state == St.WAIT) 0.5f else 1f
            })
            if (r.note.isNotBlank()) col.addView(TextView(act).apply {
                text = r.note; textSize = 12.5f; setTextColor(fg); alpha = 0.7f
                setPadding(0, act.dp(2), 0, 0)
            })
            line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rowsBox.addView(line)
        }
    }
}
