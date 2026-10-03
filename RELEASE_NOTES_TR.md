# Nuvio Türkiye 0.1.29-alpha — Windows ve Android

- Kitaplığa Kaydedildi ve Bulut sekmelerinin yanına İndirilenler eklendi. İndirmeleri görmek ve yönetmek için Ayarlar'a gitmek gerekmiyor.
- Her kaynağın sağına indirme butonu eklendi. Butona basmak oynatmayı başlatmaz; mevcut veya tamamlanan indirme yanlışlıkla değiştirilmez.
- Torrent listesi en az 5 bildirilen seed, 1080p sınıfı veya üzeri çözünürlük ve seçilen dosyada en az 1 GiB boyut şartıyla filtrelenir. Seed, çözünürlük veya dosya boyutu bilinmeyen torrentler gösterilmez.
- Torrent olmayan kaynaklar 1080p sınıfı veya üzeri çözünürlükte gösterilir; bunlara boyut sınırı uygulanmaz. Sinemaskop 1920×800 gibi 1080p sınıfındaki videolar korunur.
- Doğrudan kaynaklar hazır oldukça görünür, torrentlerin tamamlanmasını beklemez. Torrentler üstte, diğer kaynaklar altta ayrı bölümlerde kalır.
- Her bölümde dosya boyutu büyükten küçüğe sıralanır; eşitse çözünürlük ve yayın kalitesi kullanılır.
- Önceki arama, Türkçe filtre, katalog sayfalama ve torrent indirme düzeltmeleri korunur. İndirme simgesinin koyu arka planda görünürlüğü iyileştirildi.

Windows'ta mevcut gerçek medya ve içerik doğrulaması sürer. Android filtreleri eklentinin bildirdiği çözünürlük, dosya boyutu ve seed bilgilerini kullanır; Windows'a özel ffprobe doğrulaması Android'e eklenmedi.

Windows x64 için MSI; Android için arm64-v8a, armeabi-v7a, x86 ve x86_64 APK'ları sunulur. Çoğu güncel Android telefon için arm64-v8a dosyasını seçin. Android paket kimliği `com.wgodfather.nuvio`, önceki fork sürümüyle aynı imzayı kullanır. Resmî uygulamanın yanında kurulabilir. Android 7 ve üzeri hedeflenir.

Android'de HLS çevrimdışı indirme henüz desteklenmez; bu kaynakların indirme butonu pasiftir. Torrent ve doğrudan video indirmeleri desteklenir. Fiziksel ARM telefon ve iOS paketi bu çalışmada doğrulanmadı.

Bu alpha sürüm arayüz ve kaynak listesi güncellemesidir. Ayrı VPN geliştirme dalı bu yayına dahil edilmedi. Mevcut fork ayarları ve indirme kayıtları korunur. Hesap senkronizasyonu ve Trakt için kendi hizmet yapılandırmanız gerekir.
