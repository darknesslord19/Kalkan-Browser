package app.kalkan.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** GitHub bağlantı ayarı: anahtar ve depo. */
internal fun MainActivity.ghSettings(onSaved: (() -> Unit)? = null) {
    val tokenF = field("GitHub anahtarı (github_pat_…)", GhBuild.token)
    tokenF.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    val repoF = field("Depo (kullanıcı/depo)", GhBuild.repo)
    val info = small(
        "Anahtar nasıl alınır: github.com → Settings → Developer settings → Personal access tokens → " +
            "Fine-grained tokens → Generate new token.\n\n" +
            "• Repository access: yalnızca bu depo\n" +
            "• İzinler: Contents → Read and write, Actions → Read and write\n\n" +
            "Anahtar yalnızca bu telefonda saklanır, yedeğe girmez."
    )
    MaterialAlertDialogBuilder(this)
        .setTitle("GitHub bağlantısı")
        .setView(formBox(tokenF, repoF, info))
        .setPositiveButton("Kaydet") { _, _ ->
            val t = tokenF.text.toString().trim()
            val r = repoF.text.toString().trim()
            if (t.isEmpty()) { toast("Anahtar boş"); return@setPositiveButton }
            if (!Regex("[\\w.-]+/[\\w.-]+").matches(r)) { toast("Depo “kullanıcı/depo” biçiminde olmalı"); return@setPositiveButton }
            GhBuild.save(t, r)
            toast("Kaydedildi")
            onSaved?.invoke()
        }
        .setNeutralButton("Anahtarı sil") { _, _ -> GhBuild.clear(); toast("Anahtar silindi") }
        .setNegativeButton("Vazgeç", null)
        .show()
}

/** ZIP'i GitHub'a yükleyip derletir ve .cs3'ü indirir; adım adım ilerleyen pencere gösterir. */
internal fun MainActivity.ghBuildZip(zip: ByteArray, plugin: String, zipName: String) {
    if (!GhBuild.configured()) {
        ghSettings { if (GhBuild.configured()) ghBuildZip(zip, plugin, zipName) }
        return
    }
    val rows = listOf(
        AuditRow("conn", "GitHub bağlantısı"),
        AuditRow("up", "Eklenti paketi yükleniyor", 9000L),
        AuditRow("build", "Derleniyor (Kotlin → .cs3)", 80000L),
        AuditRow("get", "Yayınlama ve indirme", 8000L)
    )
    val panel = CsPanel(this, "Derleniyor", plugin)
    var cancel = false
    var stage = 0
    val t0 = System.currentTimeMillis()
    fun paint(note: String = "") {
        for ((i, r) in rows.withIndex()) {
            r.state = when { i < stage -> St.OK; i == stage -> St.RUN; else -> St.WAIT }
        }
        if (note.isNotBlank()) rows[stage].note = note.lines().joinToString(" · ").take(120)
        val secs = (System.currentTimeMillis() - t0) / 1000
        // Derleme süresi belirsiz: son adıma doğru yavaşlayan bir ilerleme eğrisi
        val within = if (stage == 2) (1f - 1f / (1f + secs / 90f)) * 0.5f else 0f
        panel.setProgress(((stage + within) / rows.size).coerceAtMost(0.97f), "${secs} sn · adım ${stage + 1}/${rows.size}")
        panel.render(rows)
    }
    panel.setFooter("Paket GitHub'da derlenir; bu genelde 2–4 dakika sürür. Uygulamayı kapatmadan bekle.")
    panel.addButton("İptal") { cancel = true; panel.setFooter("İptal ediliyor…") }
    panel.show()
    paint()
    Thread {
        val out = GhBuild.build(zip, plugin, zipName,
            progress = { s ->
                runOnUiThread {
                    stage = when {
                        s.contains("indiriliyor") -> 3
                        s.contains("Derle") -> 2
                        s.contains("yükleniyor") || s.contains("yeniden başlatılıyor") -> 1
                        else -> stage
                    }
                    paint(s)
                }
            },
            cancelled = { cancel },
            saveFile = { name, bytes -> Saver.save(this, name, "application/octet-stream") { it.write(bytes) } })
        runOnUiThread { showBuildResult(panel, rows, stage, out, plugin) }
    }.start()
}

private fun MainActivity.showBuildResult(panel: CsPanel, rows: List<AuditRow>, stage: Int, o: GhBuild.Outcome, plugin: String) {
    panel.clearButtons()
    if (o.ok) {
        rows.forEach { it.state = St.OK }
        rows[3].note = "$plugin.cs3 · İndirilenler/Kalkan"
        panel.setTitle("Eklenti hazır", plugin)
        panel.setProgress(1f, "Tamamlandı")
        panel.setChip("Derleme başarılı", 0xFF2E9E5B.toInt())
        panel.render(rows)
        panel.setFooter("CloudStream'e ekle düğmesi depo adresini CloudStream'de açar; güncellemeler otomatik gelir. Açılmazsa adresi kopyalayıp Ayarlar → Uzantılar → Depo ekle'ye yapıştır.")
        panel.addButton("CloudStream'e ekle", primary = true) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("cloudstreamrepo://" + GhBuild.repoJsonUrl())))
            } catch (_: Exception) {
                (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("repo", GhBuild.repoJsonUrl()))
                toast("CloudStream açılamadı, adres kopyalandı")
            }
        }
        panel.addButton("Depo adresini kopyala") {
            (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("repo", GhBuild.repoJsonUrl()))
            toast("Kopyalandı")
        }
        panel.addButton(".cs3 dosyasının yeri") { openSystemDownloads() }
        panel.addButton("Kapat") { panel.dismiss() }
    } else {
        rows.forEachIndexed { i, r -> r.state = if (i < stage) St.OK else if (i == stage) St.FAIL else St.WAIT }
        rows[stage.coerceIn(0, rows.size - 1)].note = o.message.lines().firstOrNull().orEmpty().take(140)
        panel.setTitle("Derleme tamamlanamadı", plugin)
        panel.setChip("Hata", 0xFFE5484D.toInt())
        panel.render(rows)
        panel.setFooter(o.message)
        if (o.authProblem) panel.addButton("GitHub ayarı", primary = true) { panel.dismiss(); ghSettings() }
        else if (o.link != null) panel.addButton("Günlüğü aç", primary = true) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.link))) } catch (_: Exception) { toast("Açılamadı") }
        }
        panel.addButton("Kapat") { panel.dismiss() }
    }
}
