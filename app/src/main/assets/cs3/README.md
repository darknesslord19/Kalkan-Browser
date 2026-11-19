# @@NAME@@ — CloudStream eklentisi

Darkness Tarayıcı ile **@@SITE@@** için @@DATE@@ tarihinde oluşturulan iskelet.

## İçindekiler

| Dosya | Ne işe yarar |
|---|---|
| `@@NAME@@/src/main/kotlin/.../@@NAME@@Provider.kt` | Sağlayıcı: ana sayfa, arama, detay, video bağlantıları |
| `@@NAME@@/src/main/kotlin/.../@@NAME@@Endpoints.kt` | Darkness'ın bulduğu @@COUNT@@ API ucu, Kotlin sabitleri olarak |
| `@@NAME@@/ENDPOINTS.md` | Aynı uçların ayrıntılı listesi (yöntem, durum, kaynak) |
| `@@NAME@@/build.gradle.kts` | Eklentinin adı, sürümü, dili, türleri |
| `.github/workflows/build.yml` | GitHub'da otomatik .cs3 derlemesi |

## Yapılacaklar

Darkness sayfa analizinden seçicileri, video çözme zincirini (iç içe iframe, packed JS, CryptoJS AES, base64, m3u8/mp4)
ve HLS başlıklarını otomatik doldurdu. Yine de şunlara göz at:

1. `@@NAME@@Provider.kt` başındaki **Analiz özeti**ni oku: hangi kısımların bulunduğunu ve HLS/şifre durumunu gösterir.
   Bulunamayan kısımlar için genel yedek seçiciler kullanılır; sonuç boş çıkarsa `cardSelector`, `episodeSelector` değerlerini düzelt.
2. `search()` içindeki arama adresini kontrol et (@@SEARCH_NOTE@@).
3. Video açılmıyorsa Darkness'da videoyu oynatıp **Araçlar → CS analizi** çalıştır ve iskeleti yeniden dışa aktar.
   DRM korumalı (Widevine/FairPlay) akışlar CloudStream'de oynatılamaz.
4. Çalıştığını gördüğünde `build.gradle.kts` içinde `status = 1` yap ve her güncellemede `version` sayısını artır.

## Derleme

**GitHub ile (önerilen):** Bu klasörün içeriğini boş bir GitHub deposuna yükle. Actions otomatik derler,
`.cs3` dosyalarını ve `repo.json`'u **builds** dalına koyar. CloudStream'de
*Ayarlar → Uzantılar → Depo ekle* kısmına şunu yaz:

```
https://raw.githubusercontent.com/KULLANICI/DEPO/builds/repo.json
```

**Kendi builder'ınla:** Sadece `@@NAME@@` klasörünü mevcut eklenti deponun köküne kopyala.

**Bilgisayarda:** Android SDK ve JDK 17 kuruluyken `gradle make` (çıktı: `@@NAME@@/build/@@NAME@@.cs3`).
