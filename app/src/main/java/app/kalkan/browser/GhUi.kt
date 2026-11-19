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

/** ZIP'i GitHub'a yükleyip derletir ve .cs3'ü indirir. */
internal fun MainActivity.ghBuildZip(zip: ByteArray, plugin: String, zipName: String) {
    if (!GhBuild.configured()) {
        ghSettings { if (GhBuild.configured()) ghBuildZip(zip, plugin, zipName) }
        return
    }
    val msg = TextView(this).apply {
        text = "Başlıyor…"
        textSize = 15f
        setPadding(dp(24), dp(16), dp(24), dp(8))
    }
    var cancel = false
    val dlg = MaterialAlertDialogBuilder(this)
        .setTitle("Derleniyor")
        .setView(msg)
        .setCancelable(false)
        .setNegativeButton("İptal") { _, _ -> cancel = true }
        .show()
    Thread {
        val out = GhBuild.build(zip, plugin, zipName,
            progress = { s -> runOnUiThread { msg.text = s } },
            cancelled = { cancel },
            saveFile = { name, bytes -> Saver.save(this, name, "application/octet-stream") { it.write(bytes) } })
        runOnUiThread {
            try { dlg.dismiss() } catch (_: Exception) {}
            showGhResult(out, plugin)
        }
    }.start()
}

private fun MainActivity.showGhResult(o: GhBuild.Outcome, plugin: String) {
    val b = MaterialAlertDialogBuilder(this)
    if (o.ok) {
        b.setTitle(".cs3 hazır")
            .setMessage(
                "${o.message}\n\nİndirilenler/Kalkan/$plugin.cs3 olarak kaydedildi.\n\n" +
                    "CloudStream'e eklemek için Ayarlar → Uzantılar → Depo ekle kısmına şu adresi yaz " +
                    "(güncellemeler otomatik gelir):\n${GhBuild.repoJsonUrl()}"
            )
            .setPositiveButton("Adresi kopyala") { _, _ ->
                (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("repo", GhBuild.repoJsonUrl()))
                toast("Kopyalandı")
            }
            .setNeutralButton("İndirilenler") { _, _ -> openSystemDownloads() }
            .setNegativeButton("Tamam", null)
    } else {
        b.setTitle("Derleme tamamlanamadı")
            .setMessage(o.message)
            .setPositiveButton("Tamam", null)
        if (o.authProblem) b.setNeutralButton("GitHub ayarı") { _, _ -> ghSettings() }
        else if (o.link != null) b.setNeutralButton("Günlüğü aç") { _, _ ->
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.link))) } catch (_: Exception) { toast("Açılamadı") }
        }
    }
    b.show()
}
