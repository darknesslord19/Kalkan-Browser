package app.kalkan.browser

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import org.json.JSONObject

/** Sayfada verilen seçiciye uyan öğeleri birkaç saniye vurgular; uyan öğe sayısını döndürür. */
private fun highlightJs(sel: String) = """
(function(q){
  var old = document.getElementById('__kalkan_hl'); if (old) old.remove();
  try {
    var n = document.querySelectorAll(q).length;
    var st = document.createElement('style'); st.id = '__kalkan_hl';
    st.textContent = q + '{outline:3px solid #3E8EF7!important;outline-offset:-2px!important;background-color:rgba(62,142,247,.18)!important}';
    document.documentElement.appendChild(st);
    var first = document.querySelector(q); if (first && first.scrollIntoView) first.scrollIntoView({block: 'center'});
    setTimeout(function(){ st.remove(); }, 4000);
    return n;
  } catch(e) { return -1; }
})(${JSONObject.quote(sel)})
""".trimIndent()

/** Kotlin dize sabiti için kaçışlar. */
private fun kq(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

internal fun MainActivity.showSelectorInfo(tab: MainActivity.Tab, json: String) {
    val o = try { JSONObject(json) } catch (_: Exception) { return }
    val sel = o.optString("sel")
    if (sel.isEmpty()) return
    val d = sheet(0.85f)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(24))
    }
    box.addView(sheetHeader("Seçici", "<${o.optString("tag")}> · ${tab.pageHost}").apply { setPadding(dp(6), dp(14), 0, dp(4)) })

    fun section(title: String) {
        box.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 11.5f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(mutedColor())
            setPadding(dp(6), dp(16), 0, dp(6))
        })
    }

    /** Dokununca menü açan satır: kopyala, Kotlin/JS olarak kopyala, vurgula. */
    fun row(value: String, meta: String?, isSelector: Boolean) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_tile)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
        }
        r.addView(TextView(this).apply {
            text = value
            typeface = Typeface.MONOSPACE
            textSize = 13.5f
            setTextColor(textColor())
            maxLines = 6
        })
        if (!meta.isNullOrEmpty()) r.addView(small(meta).apply { setPadding(0, dp(4), 0, 0) })
        r.setOnClickListener { v ->
            if (!isSelector) { copy(value); return@setOnClickListener }
            val pm = PopupMenu(this, v, Gravity.END)
            pm.menu.add(0, 1, 0, "Kopyala")
            pm.menu.add(0, 2, 1, "Kotlin (Jsoup) olarak kopyala")
            pm.menu.add(0, 3, 2, "JavaScript olarak kopyala")
            pm.menu.add(0, 4, 3, "Sayfada vurgula")
            pm.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> copy(value)
                    2 -> copy("document.select(${kq(value)})")
                    3 -> copy("document.querySelectorAll(${JSONObject.quote(value)})")
                    4 -> {
                        d.dismiss()
                        tab.webView.evaluateJavascript(highlightJs(value)) { n ->
                            val c = n?.toIntOrNull() ?: -1
                            toast(if (c < 0) "Seçici geçersiz" else "$c öğe vurgulandı")
                        }
                    }
                }
                true
            }
            pm.show()
        }
        r.setOnLongClickListener { copy(value); true }
        box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    section("Bu öğe")
    val unique = o.optInt("unique", 1)
    row(sel, if (unique == 1) "Sadece bu öğeye uyar" else "$unique öğeye uyar", true)

    val sim = o.optJSONArray("similar")
    if (sim != null && sim.length() > 0) {
        section("Benzer öğeler (liste / kart seçicisi)")
        val list = (0 until sim.length()).map { sim.getJSONObject(it) }
            .sortedWith(compareBy({ if (it.optInt("n") > 1) 0 else 1 }, { it.optString("s").length }))
        for (s in list) {
            val n = s.optInt("n")
            row(s.optString("s"), if (n == 1) "1 öğe" else "$n öğe", true)
        }
    }

    val text = o.optString("text")
    if (text.isNotEmpty()) {
        section("Metin")
        row(text, "Dokun: kopyala", false)
    }

    val attrs = o.optJSONObject("attrs")
    if (attrs != null && attrs.length() > 0) {
        section("Öznitelikler")
        for (k in attrs.keys()) {
            val v = attrs.optString(k)
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_tile)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                setOnClickListener { copy(v) }
                setOnLongClickListener { copy("$k=\"$v\""); true }
            }
            r.addView(TextView(this).apply {
                this.text = k
                typeface = Typeface.MONOSPACE
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(this@showSelectorInfo, R.color.brand_dark))
                setPadding(0, 0, dp(12), 0)
            })
            r.addView(TextView(this).apply {
                this.text = v
                typeface = Typeface.MONOSPACE
                textSize = 12.5f
                setTextColor(textColor())
                maxLines = 4
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }
    }

    box.addView(small("İpucu: Bir seçiciye dokununca Kotlin/JavaScript olarak kopyalayabilir ya da sayfada hangi öğelere uyduğunu görebilirsin.").apply {
        setPadding(dp(6), dp(14), dp(6), 0)
    })
    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    actions.addView(textBtn("Başka öğe seç") { d.dismiss(); startPicker("copy") })
    actions.addView(textBtn("Kapat") { d.dismiss() })
    box.addView(actions)

    d.setContentView(NestedScrollView(this).apply { addView(box) })
    d.show()
}
