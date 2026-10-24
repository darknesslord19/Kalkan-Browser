# 🛡️ Kalkan Tarayıcı

Reklam engelleyicili, gizlilik odaklı Android tarayıcısı — içinde **WireGuard VPN** ve **sistem geneli reklam engelleme** ile.

## 📥 İndir

**[Son sürümü indir (Releases)](../../releases/latest)** → `Kalkan-v1.0.X.apk` dosyasını telefonuna indirip kur.
(İlk kurulumda "Bilinmeyen kaynaklardan yükleme" iznini vermen gerekir.)

## ✨ Özellikler

**Reklam ve izleyici engelleme**
- EasyList, EasyPrivacy, AdGuard Türkçe, Peter Lowe ve StevenBlack listeleri (3 günde bir otomatik güncellenir)
- Ağ engelleme + kozmetik filtreleme (reklam boşluklarını da gizler)
- YouTube reklam temizleyici (reklam verisini ayıklar, kalanları otomatik atlar)
- Site bazında korumayı açıp kapatma, sayfa başına ve toplam engelleme sayacı
- İsteğe bağlı: çerez bildirimlerini ve sosyal medya düğmelerini gizleme

**VPN**
- **Sistem geneli koruma (DNS):** tüm uygulamalarda reklam/izleyici alan adlarını engeller, sunucu gerekmez
- **WireGuard VPN istemcisi:** Proton VPN, Mullvad vb. bir sağlayıcının `.conf` dosyasını içe aktar, tek tuşla bağlan

**Tarayıcı**
- Sınırsız sekme, gizli sekme (ayrı çerez profili), kapatılan sekmeyi geri açma, oturumu geri yükleme
- Akıllı adres çubuğu (geçmiş + yer imi önerileri), 6 arama motoru
- Yer imleri, geçmiş, indirme yöneticisi, sayfada bul, paylaş, yazdır/PDF
- Masaüstü sitesi modu, tam ekran video, arka planda oynatma
- Dosya yükleme, kamera/mikrofon/konum izinleri (site başına sorar)
- Açılır pencere engelleyici, üçüncü taraf çerez engelleme
- Koyu/açık tema, siteleri koyu gösterme, yazı boyutu, çekerek yenileme
- Varsayılan tarayıcı yapılabilir

## 🔧 Derleme

Her `main` dalına gönderimde GitHub Actions APK'yı derler ve **Releases** sayfasında yayınlar.
Elle başlatmak için: **Actions → APK Derle → Run workflow**.

Yerelde: Android SDK + JDK 17 kurulu iken `gradle assembleRelease`.

> İmzalama anahtarı (`keystore/kalkan.jks`) güncellemelerin üst üste kurulabilmesi için depoda tutuluyor. Depo herkese açıksa bu anahtarı GitHub Secrets'a taşımak daha güvenlidir.
