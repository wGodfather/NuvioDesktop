# Nuvio Türkiye 0.1.28-alpha

- Film/dizi araması, Türkçe tür filtreleri ve katalog sayfalaması düzeltildi.
- Özel statik eklentinin arama adreslerinden kaynaklanan HTTP 404 giderildi.
- Torrent başlığındaki magnet kodlama hatasından kaynaklanan HTTP 500 düzeltildi.
- Windows torrent ve HLS indirmeleri, kaynak yenileme ve devam etme iyileştirildi.
- Windows kaynakları gerçek dosya boyutu, video çözünürlüğü ve içerik eşleşmesiyle
  doğrulanıyor; boyuta göre büyükten küçüğe, eşitse çözünürlük/kaliteye göre sıralanıyor.
- Android torrent indirmesi bağımsız Nuvio Engine oturumuna bağlandı. Doğru dosya
  seçimi, range ile devam etme, iptal/temizleme ve yerel ağ yönlendirmesi düzeltildi.
- Windows ve Android güncelleme kontrolü wGodfather/NuvioDesktop sürümlerini kullanıyor.

Android için 49 hedefli test geçti. Android 16 sanal cihazında Suits araması başarılı
oldu; küçük yerel test videosu torrentten indirilip boyutu ve SHA-256 ile doğrulandı.
Windows'un önceki 51 hedefli testi ile film/dizi araması, beş katalog, tür filtresi,
devam sayfası ve bölüm bilgileri de doğrulanmıştı.

Android APK: cihaz mimarisine uygun dosyayı seçin (çoğu güncel telefon arm64-v8a).
Paket kimliği `com.wgodfather.nuvio`; resmî uygulama ile yan yana kurulabilir.
Android 7+ hedeflenir; fiziksel ARM telefon ve iOS paketi bu çalışmada test edilmedi.
Android'de HLS çevrimdışı indirme ve Windows'a özel sıkı video doğrulaması bulunmaz;
Android torrent ve doğrudan video indirmeleri desteklenir.

Hesap senkronizasyonu ve Trakt için kendi hizmet yapılandırmanız gerekir.
Katalog, yerel ayarlar, kaynak eklentileri ve indirmeler yerel/misafir kullanımında çalışır.
Windows'un mevcut ayarları ve indirmeleri korunur. Android fork'u ayrı uygulamadır;
resmî uygulamanın özel profil verilerini kendiliğinden aktaramaz.

Yayın taslaktır; otomatik güncellemede görünmez. Yayımlama kullanıcı onayını bekler.
