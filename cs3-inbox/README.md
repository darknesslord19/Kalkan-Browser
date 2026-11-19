# cs3-inbox

Kalkan'ın ürettiği CloudStream eklenti ZIP'lerini buraya yükle; GitHub Actions bunları `.cs3` dosyasına çevirir.

## Kullanım (telefondan)

1. Kalkan'da **Araçlar → CloudStream iskeleti** ile ZIP oluştur (İndirilenler/Kalkan klasörüne iner).
2. GitHub'da bu klasöre gel: **Add file → Upload files**, ZIP'i seç, **Commit changes**.
3. **Actions → CS3 Derle** birkaç dakikada biter.
4. İndirmek için **Releases** sayfasında `CS3 eklentileri #N` sürümündeki `.cs3` dosyasını al,
   ya da CloudStream'de **Ayarlar → Uzantılar → Depo ekle** kısmına şunu yaz (güncellemeler otomatik gelir):

   ```
   https://raw.githubusercontent.com/darknesslord19/Kalkan-Browser/builds/repo.json
   ```

## Notlar

- Her çalıştırmada klasördeki **tüm** ZIP'ler yeniden derlenir ve hepsi aynı repo'da yayınlanır.
- Bir eklentiyi kaldırmak için ZIP'ini sil. Güncellemek için aynı adlı ZIP'in üstüne yaz ve eklentinin `version` sayısını artır.
- Derleme hata verirse **Actions** sayfasındaki günlükte hangi ZIP'in başarısız olduğu yazar.
