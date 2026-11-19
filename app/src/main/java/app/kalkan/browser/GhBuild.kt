package app.kalkan.browser

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

/**
 * Darkness'ın ürettiği CloudStream eklenti ZIP'ini GitHub'a yükler, GitHub Actions'ta .cs3 olarak
 * derletir, bitince .cs3 dosyasını indirir.
 *
 * Akış: ZIP -> cs3-inbox/ (contents API) -> "CS3 Derle" iş akışı -> Releases'taki cs3-N sürümü -> indirme.
 * GitHub anahtarı (fine-grained token) yalnızca bu telefonda saklanır, yedeğe girmez.
 */
object GhBuild {
    const val DEFAULT_REPO = "darknesslord19/Darkness-Browser"
    private const val API = "https://api.github.com"

    val token: String get() = Store.str("gh_token", "")
    val repo: String get() = Store.str("gh_repo", DEFAULT_REPO).trim().ifBlank { DEFAULT_REPO }
    fun configured() = token.isNotBlank()
    fun save(token: String, repo: String) {
        Store.prefs.edit().putString("gh_token", token.trim()).putString("gh_repo", repo.trim()).apply()
    }
    fun clear() { Store.prefs.edit().remove("gh_token").apply() }
    fun repoJsonUrl() = "https://raw.githubusercontent.com/$repo/builds/repo.json"

    class Outcome(val ok: Boolean, val message: String, val file: Uri? = null, val link: String? = null, val authProblem: Boolean = false)

    private class Resp(val code: Int, val bytes: ByteArray, val location: String?) {
        val body: String get() = String(bytes, Charsets.UTF_8)
    }

    private fun readCapped(s: java.io.InputStream?, limit: Int = 8 shl 20): ByteArray {
        if (s == null) return ByteArray(0)
        val buf = java.io.ByteArrayOutputStream()
        val b = ByteArray(1 shl 14)
        s.use {
            while (buf.size() < limit) {
                val n = it.read(b)
                if (n <= 0) break
                buf.write(b, 0, n)
            }
        }
        return buf.toByteArray()
    }

    private fun http(
        method: String, url: String, body: ByteArray? = null, auth: Boolean = true,
        accept: String = "application/vnd.github+json", follow: Boolean = true
    ): Resp {
        val c = Net.open(url)
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 40000
            c.instanceFollowRedirects = follow
            c.setRequestProperty("User-Agent", "Kalkan")
            c.setRequestProperty("Accept", accept)
            if (auth) {
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val bytes = readCapped(if (code >= 400) c.errorStream else c.inputStream)
            return Resp(code, bytes, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    private fun apiMessage(r: Resp): String =
        try { JSONObject(r.body).optString("message").ifBlank { "HTTP ${r.code}" } } catch (_: Exception) { "HTTP ${r.code}" }

    private fun gitBlobSha(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${data.size}\u0000".toByteArray())
        md.update(data)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fail(msg: String, link: String? = null, auth: Boolean = false) = Outcome(false, msg, null, link, auth)

    /** Tam akış. Arka plan iş parçacığında çağır. */
    fun build(
        zip: ByteArray, plugin: String, zipName: String,
        progress: (String) -> Unit, cancelled: () -> Boolean,
        saveFile: (String, ByteArray) -> Uri
    ): Outcome {
        try {
            val r = repo
            progress("GitHub bağlantısı kontrol ediliyor…")
            val info = http("GET", "$API/repos/$r")
            if (info.code == 401) return fail("GitHub anahtarı geçersiz ya da süresi dolmuş.", auth = true)
            if (info.code == 404) return fail("Depo bulunamadı ya da anahtarın bu depoya erişimi yok ($r).", auth = true)
            if (info.code != 200) return fail("GitHub hatası: ${apiMessage(info)}")
            val repoJson = JSONObject(info.body)
            val perms = repoJson.optJSONObject("permissions")
            if (perms != null && perms.has("push") && !perms.optBoolean("push")) {
                return fail("Anahtarın yazma izni yok. Contents → Read and write izni ver.", auth = true)
            }
            val branch = repoJson.optString("default_branch", "main")

            // --- ZIP'i yükle (aynıysa derlemeyi yeniden başlat)
            val path = "cs3-inbox/" + Uri.encode(zipName)
            val existing = http("GET", "$API/repos/$r/contents/$path?ref=$branch")
            val oldSha = if (existing.code == 200) JSONObject(existing.body).optString("sha").ifBlank { null } else null
            val started = System.currentTimeMillis()
            var headSha: String? = null
            if (oldSha != null && oldSha == gitBlobSha(zip)) {
                progress("Aynı ZIP zaten yüklü, derleme yeniden başlatılıyor…")
                val d = http("POST", "$API/repos/$r/actions/workflows/cs3.yml/dispatches",
                    JSONObject().put("ref", branch).toString().toByteArray())
                if (d.code != 204) return fail("Derleme başlatılamadı: ${apiMessage(d)}. Anahtara Actions → Read and write izni ver.", auth = true)
            } else {
                progress("ZIP GitHub'a yükleniyor…")
                val body = JSONObject()
                    .put("message", "cs3-inbox: $plugin (Darkness)")
                    .put("content", Base64.encodeToString(zip, Base64.NO_WRAP))
                    .put("branch", branch)
                if (oldSha != null) body.put("sha", oldSha)
                val u = http("PUT", "$API/repos/$r/contents/$path", body.toString().toByteArray())
                if (u.code != 200 && u.code != 201) {
                    return fail("ZIP yüklenemedi: ${apiMessage(u)}", auth = u.code == 401 || u.code == 403 || u.code == 404)
                }
                headSha = JSONObject(u.body).optJSONObject("commit")?.optString("sha")
            }

            // --- Derlemenin başlamasını bekle
            progress("Derleme başlatılıyor…")
            var run: JSONObject? = null
            val findDeadline = started + 120_000
            while (run == null && System.currentTimeMillis() < findDeadline) {
                if (cancelled()) return fail("İptal edildi.")
                val q = if (headSha != null) "head_sha=$headSha" else "event=workflow_dispatch&per_page=5"
                val h = http("GET", "$API/repos/$r/actions/workflows/cs3.yml/runs?$q")
                if (h.code == 200) {
                    val arr = JSONObject(h.body).optJSONArray("workflow_runs")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val created = try { Instant.parse(o.optString("created_at")).toEpochMilli() } catch (_: Exception) { 0L }
                        if (headSha != null || created >= started - 15_000) { run = o; break }
                    }
                }
                if (run == null) Thread.sleep(4000)
            }
            val found = run ?: return fail("Derleme başlamadı. GitHub Actions kapalı ya da cs3.yml bulunamıyor olabilir.")
            val runId = found.getLong("id")
            val runNumber = found.getInt("run_number")
            val runUrl = found.optString("html_url")

            // --- Bitmesini bekle
            val t0 = System.currentTimeMillis()
            var conclusion = ""
            while (true) {
                if (cancelled()) return fail("İptal edildi. Derleme GitHub'da sürüyor olabilir.", runUrl)
                val rr = http("GET", "$API/repos/$r/actions/runs/$runId")
                val rj = if (rr.code == 200) JSONObject(rr.body) else null
                val secs = (System.currentTimeMillis() - t0) / 1000
                var step = ""
                val jr = http("GET", "$API/repos/$r/actions/runs/$runId/jobs")
                if (jr.code == 200) {
                    val jobs = JSONObject(jr.body).optJSONArray("jobs")
                    val steps = jobs?.optJSONObject(0)?.optJSONArray("steps")
                    if (steps != null) for (i in 0 until steps.length()) {
                        val s = steps.getJSONObject(i)
                        if (s.optString("status") == "in_progress") { step = s.optString("name"); break }
                    }
                }
                progress("Derleniyor… ${secs}s" + if (step.isNotEmpty()) "\n$step" else "")
                if (rj != null && rj.optString("status") == "completed") { conclusion = rj.optString("conclusion"); break }
                if (secs > 15 * 60) return fail("Derleme 15 dakikayı aştı. Durumu GitHub'da kontrol et.", runUrl)
                Thread.sleep(8000)
            }
            if (conclusion != "success") return fail(failureDetails(r, runId, conclusion), runUrl)

            // --- .cs3 dosyasını indir
            progress("Sürümden .cs3 indiriliyor…")
            var rel: JSONObject? = null
            for (i in 0 until 6) {
                val h = http("GET", "$API/repos/$r/releases/tags/cs3-$runNumber")
                if (h.code == 200) { rel = JSONObject(h.body); break }
                Thread.sleep(3000)
            }
            val assets = rel?.optJSONArray("assets") ?: return fail("Sürüm bulunamadı (cs3-$runNumber).", runUrl)
            var asset: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == "$plugin.cs3") { asset = a; break }
            }
            if (asset == null) {
                val names = (0 until assets.length()).map { assets.getJSONObject(it).optString("name") }
                return fail("$plugin.cs3 sürümde yok. Bulunanlar: ${names.joinToString().ifBlank { "hiçbiri" }}", runUrl)
            }
            var dl = http("GET", asset.getString("url"), accept = "application/octet-stream", follow = false)
            if ((dl.code == 301 || dl.code == 302 || dl.code == 307) && dl.location != null) {
                dl = http("GET", dl.location!!, auth = false, accept = "*/*")
            }
            if (dl.code != 200 || dl.bytes.isEmpty()) return fail(".cs3 indirilemedi (HTTP ${dl.code}).", runUrl)
            val uri = saveFile("$plugin.cs3", dl.bytes)
            return Outcome(true, "$plugin.cs3 hazır (${dl.bytes.size / 1024} KB).", uri, runUrl)
        } catch (e: Exception) {
            return fail("Beklenmeyen hata: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Başarısız derleme için kısa, anlaşılır neden: ilk Kotlin hataları ya da başarısız adım. */
    private fun failureDetails(r: String, runId: Long, conclusion: String): String {
        val head = if (conclusion == "cancelled") "Derleme iptal edildi (başka bir derleme başladı)." else "Derleme başarısız oldu."
        try {
            val jr = http("GET", "$API/repos/$r/actions/runs/$runId/jobs")
            val job = JSONObject(jr.body).optJSONArray("jobs")?.optJSONObject(0) ?: return head
            var stepName = ""
            val steps = job.optJSONArray("steps")
            if (steps != null) for (i in 0 until steps.length()) {
                val s = steps.getJSONObject(i)
                if (s.optString("conclusion") == "failure") { stepName = s.optString("name"); break }
            }
            val sb = StringBuilder(head)
            if (stepName.isNotEmpty()) sb.append("\nBaşarısız adım: ").append(stepName)
            var lr = http("GET", "$API/repos/$r/actions/jobs/${job.getLong("id")}/logs", follow = false)
            if ((lr.code == 301 || lr.code == 302) && lr.location != null) lr = http("GET", lr.location!!, auth = false, accept = "*/*")
            if (lr.code == 200) {
                val errs = lr.body.lines().map { it.substringAfter("Z ", it) }
                    .filter { it.startsWith("e: ") || it.contains("::error::") || it.contains("What went wrong") }
                    .map { it.replace(Regex("file:///[^ ]*/src/"), "").take(220) }
                    .distinct().take(5)
                if (errs.isNotEmpty()) sb.append("\n\n").append(errs.joinToString("\n"))
            }
            return sb.toString()
        } catch (_: Exception) {
            return head
        }
    }
}
