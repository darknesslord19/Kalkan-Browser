# OrnekSite — CloudStream eklentisi

Kalkan Tarayıcı ile **Örnek Site** için 18.11.2026 06:40 tarihinde oluşturulan iskelet.

## İçindekiler

| Dosya | Ne işe yarar |
|---|---|
| `OrnekSite/src/main/kotlin/.../OrnekSiteProvider.kt` | Sağlayıcı: ana sayfa, arama, detay, video bağlantıları |
| `OrnekSite/src/main/kotlin/.../OrnekSiteEndpoints.kt` | Kalkan'ın bulduğu 2 API ucu, Kotlin sabitleri olarak |
| `OrnekSite/ENDPOINTS.md` | Aynı uçların ayrıntılı listesi (yöntem, durum, kaynak) |
| `OrnekSite/build.gradle.kts` | Eklentinin adı, sürümü, dili, türleri |
| `.github/workflows/build.yml` | GitHub'da otomatik .cs3 derlemesi |

## Yapılacaklar

1. `OrnekSiteProvider.kt` içinde **TODO** yazan seçicileri sitenin HTML yapısına göre doldur.
2. `search()` içindeki arama adresini kontrol et (Canlı kaynağından bulundu).
3. Video sayfası iframe kullanmıyorsa `loadLinks()` içine kendi çözümünü ekle.
4. Çalıştığını gördüğünde `build.gradle.kts` içinde `status = 1` yap ve her güncellemede `version` sayısını artır.

## Derleme

**GitHub ile (önerilen):** Bu klasörün içeriğini boş bir GitHub deposuna yükle. Actions otomatik derler,
`.cs3` dosyalarını ve `repo.json`'u **builds** dalına koyar. CloudStream'de
*Ayarlar → Uzantılar → Depo ekle* kısmına şunu yaz:

```
https://raw.githubusercontent.com/KULLANICI/DEPO/builds/repo.json
```

**Kendi builder'ınla:** Sadece `OrnekSite` klasörünü mevcut eklenti deponun köküne kopyala.

**Bilgisayarda:** Android SDK ve JDK 17 kuruluyken `gradle make` (çıktı: `OrnekSite/build/OrnekSite.cs3`).
