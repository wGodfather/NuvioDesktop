# Nuvio Türkiye — Çok platformlu VPN ve kararlı yayın planı

Hazırlanma: 3 Ekim 2026. Uygulama başlangıcı: aynı gün 07:15, Europe/Istanbul.
07:15 bitiş/yayın saati değildir; geliştirme ve doğrulamanın başlangıcıdır.

Kullanıcı talebi: VPN'i mobil, PC, Android TV ve Google TV'de çalışır hale getirmek;
iyi çalıştığını doğruladıktan sonra kendi fork'una pushlamak ve GitHub Releases'te
görünecek şekilde yayımlamak. VPN isteğe bağlı ve varsayılan kapalı kalacaktır.
Başarılı doğrulamadan sonra push ve sürüm yayını bu talep kapsamında yetkilidir.
Gerçekten eksik test, imza veya erişim varsa tamamlanmış gibi raporlanmayacaktır.

## 1. Kapsam ve sürüm

Mobil/PC kapsamı için sorulan soruya henüz yanıt yoktur. İlk çalışma varsayımı
Android telefon/tablet ve Windows PC'dir. iPhone/iPad, macOS ve Linux kullanıcı
tarafından dahil edilirse kendi native VPN, paket imzası ve cihaz testleriyle
ayrı iş paketleri eklenir; destek varmış gibi gösterilmez.

| Hedef | Uygulama yaklaşımı | Çıktı |
|---|---|---|
| Windows PC x64 | Mevcut WireGuard + SYSTEM broker + WFP geliştirmesini tamamlamak | MSI |
| Android telefon/tablet | Resmî Android WireGuard tunnel kütüphanesi ve VpnService | İmzalı full APK |
| Android TV | Android VPN backend'i, TV açılışı ve kumanda arayüzü | TV uyumlu full APK |
| Google TV | Android TV ile ortak backend, ayrı cihaz/izin/kumanda senaryoları | TV uyumlu full APK |

Kaynak: `wGodfather/NuvioDesktop`, dal `codex/optional-wireguard-vpn`, taslak PR #1.
Başlangıç kaynak commit'i `50b9ec59`; yayımlanmış taban `0.1.29-alpha`.
Mevcut deneysel çıktı `0.1.30-alpha / 30`. Kararlı sürüm hedefi `0.1.30`;
Android sürüm kodu mevcut 30'dan büyük olacaktır. Windows'un 0.1.30-alpha için
kullandığı iç MSI sürümü `1.1.30` olduğundan, kararlı MSI iç sürümü de yükseltilir.
Uygulama adı/tag ile MSI'nin iç sürümü ayrı tutulur; deneyselden kararlıya gerçek
yükseltme testi yapılmadan yalnızca `-alpha` eki silinip aynı MSI sürümü sunulmaz.
Yayımlanmış 0.1.29 ve önceki sürümlerin dosyaları değiştirilmez.

## 2. Bugünkü kanıtlar ve açık eksikler

Windows'ta deneysel VPN, profil içe aktarma, şifreli saklama, fiziksel çıkışı
engelleme ve gerçek eş kontrolü vardır. 59 masaüstü, 83 Android host testi;
23 yardımcı, 6 WFP ve 16 servis/gerçek eş kontrolü geçti. Demo eş testi ilk
denemede başarısız, aynı kaynakla ikinci denemede başarılıydı; nedeni açık kaldı.
Bu sonuçlar Android VPN bağlantısı veya bütün cihazlarda kararlılık kanıtı değildir.

Android `VpnPlatform.android.kt` şu anda desteklenmeyen backend'i döndürür.
Oynatma motoru ve indirme motoru ayrı NuvioEngine oturumlarına sahiptir; ikisi
de korunmalıdır. Android indirme işi `params.network` kullanmaktadır; VPN açıkken
fiziksel ağı seçen bu yol özellikle denetlenmelidir.
Manifestte TV launcher/banner ve TV kumanda akışı tamamlanmış değildir.
Windows yardımcı imzası, otomatik MSI servis yükseltme/kaldırma, tam DNS/IPv6/
torrent sızıntı ve hız kontrolleri açıktır.

Kullanıcı fiziksel test cihazı veya temiz Windows test makinesi olmadığını bildirdi.
ADB'de bağlı cihaz yoktur. Çalışma diski yaklaşık 2 GiB boş olduğundan büyük
derlemeler, emülatörler ve paketler öncelikle geçici CI makinelerinde çalıştırılır.
Kullanıcının dosyaları veya diğer sohbetin çalışma klasörü temizleme hedefi değildir.

## 3. Ortak VPN davranışı

Tek politika koordinatörü: Kapalı, Hazır, İzin/Kurulum gerekli, Bağlanıyor,
Bağlı, Trafik engellendi, Yeniden bağlanıyor, Hata. Bir adaptörün görünmesi veya
ayar anahtarının açık olması bağlantı kanıtı sayılmaz.

VPN açık isteği önce kalıcılaşır. Yeni torrent/indirme işleri kapatılır; mevcut
oynatma, indirme, tracker/DHT/peer ve yükleme soketleri doğrulanmış durdurma
bariyerinden geçer. Ağ koruması ve gerçek eş bağlantısı doğrulanınca işler açılır.
Kesinti, izin iptali ve ağ değişiminde normal ağa otomatik geçilmez. VPN kapatılırken
önce bütün ağ motorları durur, sonra koruma bırakılır; indirmeler kendiliğinden sürmez.
Oynatıcı kapanması diğer aktif indirme oturumunu yanlışlıkla kapatmamalıdır.

VPN cihaz genelini kapsar; etkisi kurulum/ayar ekranında açıkça anlatılır. Otomatik
bağlanma ayrıca isteğe bağlıdır. VPN hesabı/sunucu aboneliği uygulamaya dahil değildir.
VPN virüs taraması veya mutlak anonimlik sağlamaz.

## 4. Android telefon/tablet backend'i

Resmî `com.wireguard.android:tunnel` sürümü/kaynakları uygulama sırasında kontrol
edilip sabitlenir. GoBackend servisinin izin, TUN ve foreground yaşam döngüsü
incelenir; native servisle yarışan ikinci bir sahip oluşturulmaz. Root gerekmez.

- `VpnService.prepare()` ve Activity Result ile sistem onayı; ret/iptal durumu.
- Manifestte BIND_VPN_SERVICE ve doğru VPN servis tanımı; API 34+ için gerçekten
  uygun foreground tipi/izinleri. VPN işi sıradan data-sync gibi sınıflandırılmaz.
- Android Keystore ile şifreli profil ve uygulama özel depolaması; anahtar/log/
  yedek/senkronizasyon sızıntısı yok. Kilitli cihazda anahtar yoksa iş bekler.
- Sınırlı `.conf` okuma, anlaşılır alan hataları; tam tünel, tek eş başlangıcı.
  Endpoint alan adı desteği, yalnızca VPN endpoint'ini çözümleyen başlangıç yolu
  kanıtlandıktan sonra eklenir. Tracker/içerik DNS'i bu istisnadan çıkamaz.
- IPv4, IPv6 ve DNS politikası; kullanılamayan IPv6 için tünel dışına kaçış engeli.
- Ağ değişimi ve pil/Doze yönetimi; arka plan ve yeniden başlatma davranışı.
- Başka VPN, sistemden izin iptali ve always-on ayarıyla anlaşılır çakışma durumu.

Android'de VPN dışı bağlantıları işletim sistemi düzeyinde engelleme seçeneği
kullanıcının always-on/lockdown ayarıdır; normal uygulama bunu sessizce zorlayamaz.
3 Ekim uygulama kararı: lockdown VPN uygulamasının UID'sini muaf tuttuğu için
Nuvio'nun kendi torrent korumasının temeli olamaz. WireGuard servisi ayrı süreçte
çalışır; ana sürecin yeni soket/DNS işlemleri açıkça VPN Network'üne bağlanır.
Mevcut oturumlar geçişten önce kapanır; kaybolan VPN Network bağı fiziksel ağa
geri dönüş için temizlenmez. Always-on/lockdown diğer uygulamalar için isteğe
bağlı ek koruma olur; bu ayarı göstermeyen TV'de de aynı Nuvio bağlama/sızıntı
testleri geçmelidir. Başarısız bağlama durumunda torrent başlamaz.
Peer kaybında TUN'un kalması ile VPN servisinin/izninin tamamen kaldırılması
ayrı test edilir. Root olmadan her OEM TV'de kesintisiz bloklama varsayılmaz.
Güçlü koruma gereksinimi karşılanamayan cihazda korumalı torrent başlatılmaz;
bir UI etiketiyle sıfır sızıntı iddiası yapılmaz. Bu teknik uygunluk ilk prototipte
karar kapısıdır; başarısızsa önce güvenli mimari düzeltilir.

## 5. Bütün Android ağ girişlerini bağlamak

`P2pStreamingEngine.android.kt`: motor oluşturma/başlatma, magnet ekleme, yeniden
oynatma, cache ve eşzamanlı oturumlar. `AndroidTorrentDownload.kt`: ayrı indirme
motoru oluşturma, devam ettirme ve doğrulanmış kapatma.

`AndroidDownloadScheduler`, Worker ve JobService: arayüz açılmadan başlayan ve
süreç yeniden başlatıldıktan sonra devam eden işler de VPN tercihine uyar.
Fiziksel `Network.openConnection`/socket bağlama ve `params.network` seçimi VPN
korumasını aşamaz. HTTP indirmeler ve oynatıcı/debrid geçişleri ayrıca denetlenir.
Motorları kapatma yalnızca async bir çağrı bırakmak değildir; kapandıkları
beklenir ve hata varsa ağ kapısı açılmaz. Loopback motor/oynatıcı iletişimi korunur.

## 6. Android TV ve Google TV

Telefon/tablet ekranı TV'de aynen büyütülmez. TV cihaz algılama, Leanback launcher,
banner ve touchscreen zorunluluğunun kaldırılması ile aynı uygulama kimliğinin
TV'de açılışı sağlanır. Paket kimliği ve mevcut Android imza anahtarı korunur.

VPN ekranında D-pad/OK/geri, görünür odak, doğru ilk odak, dialog odağı ve uzun
metin kaydırması. Ana gezinme, Kitaplık İndirilenler, kaynak indirme butonu ve
oynatıcı gibi VPN'e ulaşmak için gereken akışlar kumandayla kullanılabilir olmalıdır.
VPN izin ekranı ve bildirimler TV sisteminde ayrı doğrulanır.

Profil aktarımı için cihazın dosya seçicisi varsa `.conf` içe aktarma. Dosya
seçicisi olmayan cihazda kumandayla kullanılabilir sınırlı profil metni girişi;
anahtar ekranda maskelenir. Kullanışlı USB/yerel aktarım desteği önce prototiplenir.
Açık HTTP üzerinden anahtar taşıyan kolay bir TV yükleme sayfası yapılmaz.
Bluetooth klavye/dokunmatik fare zorunlu tutulmaz.

## 7. Windows'u kararlı hale getirmek

Mevcut broker/WFP/DPAPI sınırları korunur. Eski soketlerin koruma değişiminde
yeniden denetlenmesi, ikinci kullanıcı/başka VPN, crash/reboot/sleep ve endpoint
yönetimi test edilir. Hata halinde normal bağlantıya otomatik dönüş yoktur.

MSI ile yardımcı servis kurma, doğrulanmış yükseltme, rollback, onarım ve kaldırma
entegrasyonu; aktif torrent varken güvenli geçiş; yalnızca Nuvio'ya ait kaynakların
temizlenmesi. Paylaşılan WireGuard sürücüsü veya başka VPN kuralları silinmez.
Güvenilir yardımcı imzası için mevcut sertifika/CI signing erişimi kontrol edilir.
Sertifika yoksa kendinden imzalı dosya güvenilir yayıncı imzası diye sunulmaz;
bu yayın bağımlılığı açıkça kaydedilir. Kullanıcının PC'sinde ağ kesen test yoktur.

## 8. Test altyapısı ve gerçek bağlantı

CI'da geçici Windows runner ve Android telefon/TV emülatörleri. İlk cihaz matrisi
API 24 alt sınırı, güncel hedef API, bir Android TV ve bir Google TV sistem imajı;
imaj/lisans/runner uygunluğu kontrol edilir. ARM64 ve armeabi-v7a native paketleme
testi; x86/x86_64 sanal cihaz testi ayrı raporlanır. ABI derlenmesi cihaz testi değildir.

Kontrollü WireGuard endpoint ve küçük yasal test torrent'i/uzak peer ile dış IP,
tracker/DHT/UDP/TCP trafiği, DNS ve IPv6 kaydı. Önce mevcut ücretsiz test/CI
altyapısıyla geçici endpoint kurulabilirliği araştırılır; ücretli VPS/abonelik
alınmaz. Public WireGuard demo yalnızca handshake kontrolüdür; IP/sızıntı/hız
kanıtı olarak kullanılmaz. Erişilebilir kontrollü endpoint yoksa bu test eksik kalır.
Özel anahtarlar yalnız test belleği/geçici güvenli depoda tutulur; günlüklerde yoktur.

Fiziksel telefon/TV ve gerçek ağ erişimi yokluğu, emülatör sonucuyla örtülmez.
OEM uyku, kumanda, VPN izin ayarı ve ARM performansı için cihaz doğrulaması hâlâ
gereklidir. İşlevsel sanal testler bağımsız ilerler; bütün hedeflerde stable kararı
için eksik fiziksel kanıt veya eşdeğer güvenilir cihaz laboratuvarı açık bağımlılıktır.

## 9. Kararlı sürüm kabul tablosu

| Alan | Geçiş koşulu |
|---|---|
| VPN kapalı | 0.1.29 arama/katalog/kaynak/indirme davranışı ve verileri korunuyor; zorunlu izin yok |
| Normal VPN | Profil import, gerçek handshake, seçili içerik indirme/oynatma ve yükleme çalışıyor |
| Sızıntı | Kontrollü peer yalnız VPN IP'sini görüyor; fiziksel adaptörde yetkisiz torrent/DNS/IPv6 paketi sıfır |
| Kesinti | Peer kaybı, servis çökmesi, izin iptali ve ağ geçişinde doğrudan torrent çıkışı yok |
| Yaşam döngüsü | UI kapalı, ekran kapalı, süreç/reboot ve devam ettirme işleri aynı politikalı |
| TV arayüzü | Açılış, profil/izin, ayarlar, kaynak/indirme ve oynatıcı sadece kumandayla kullanılabiliyor |
| Veri ve sırlar | Eski ayarlar/indirmeler korunuyor; profil backup/log/senkronizasyonda yok |
| Yükseltme | Yayımlanmış 0.1.29 ve deneysel 0.1.30-alpha'dan yükseltme, rollback/kaldırma geçti |
| Dağıtım | Android aynı fork imzası; Windows gerekli güvenilir imza; hash/ABI/lisans/SBOM doğrulandı |

Performans: aynı kontrollü içerikte VPN kapalı/açık en az üç ölçüm; medyan indirme
hızı, CPU, RAM, telefon pil ve TV oynatma takılması raporlanır. En az 60 dakika
eşzamanlı oynatma/indirme ve 10 bağlantı/ağ geçiş döngüsünde crash/veri kaybı yok.
100 Mbps kontrollü baz hız varsa en az 25 Mbps sürdürülebilir korumalı aktarım
ilk 1080p kabul hedefidir; içerik bit hızına göre üst kalite ayrıca doğrulanır.
Sabit hız kaybı yüzdesi garantisi verilmez; ölçüm kayda girer ve gerekirse darboğaz
düzeltilir. Demo sunucusundaki değişken handshake sonucunun kökü araştırılır.

## 10. Uygulama sırası — 07:15'ten itibaren

1. Kaynak/dal/HEAD ve varsa kapsam yanıtını doğrula; diğer sohbetin ana checkout'unu
   değiştirme. Plan ve kontrol listesini oku; test/storage/signing erişimini keşfet.
2. Android fail-closed/izin/TUN yaşam döngüsü prototipi ve gerçek eş bağlantısı.
   Güvenlik kapısı başarılı olmadan yalnız arayüzü bitirmeye yönelme.
3. Ortak koordinatör + Android oynatma/indirme/Worker/JobService motor bariyerleri;
   profil şifreleme, yeniden bağlanma, hata ve recovery akışları.
4. Telefon/tablet UI ve TV launcher/D-pad/izin/profil akışları; Google TV emülatörü.
5. Windows imza ve MSI lifecycle, crash/network/reboot kontrollerini tamamlama.
6. Regresyon, kontrollü peer, sızıntı/performans/uzun çalışma matrisi; başarısızlığı
   düzelt, yalnız değişiklik veya açıklanamayan hata gerektiriyorsa testi tekrar et.
7. Paketleri üret, sürüm/yükseltme/imza/hash/lisansları doğrula. Her hedef için
   test raporunda geçti/kaldı/çalıştırılamadı ve emülatör/fiziksel ayrımını yaz.
8. Kabul kapıları geçen kaynak commit'ini pushla, PR'ı son kapsamla güncelle ve
   `turkiye` ile güvenli şekilde birleştir. Aynı commit'ten paketler ve tag üret.
9. GitHub Release taslağına MSI, Android/TV APK'ları, SHA-256 ve doğrulama raporunu
   yükle; cihaz/ABI adları anlaşılır olsun. İmzalı paketleri son kez doğrula.
10. Bütün hedefler için kararlı kabul sağlanmışsa taslağı public Release'e çevir
    ve sayfadaki görünürlüğü/dosya indirmelerini doğrula. Bu koşul sağlanmadan
    eksik testli build'i stable olarak yayımlama; taslağı ve tamamlanan çalışmayı
    koru, eksik bağımlılığı kullanıcıya somut olarak bildir.

## 11. Yayın yetkisi ve eksik bağımlılıklar

Kullanıcı doğrulandıktan sonra GitHub push ve Releases yayını istedi; yalnızca
yayın için tekrar genel onay istenmez. Uygun testleri bitirmeden hazır gibi
gösterilmez. İnherited upstream issue bağlantısı kontrolü fork sahibinin bu
talebini sahte GitHub issue onayı olarak yazmaya gerekçe değildir; kontrolün
durumu açık kaydedilir. Store yayını veya ücretli hizmet satın alma kapsam dışıdır.

Bugün bilinen bağımlılıklar: fiziksel cihaz yok; Windows güvenilir imza erişimi
doğrulanmadı; kontrollü uzak peer/provider yok; emülatör için yerel disk yetersiz.
İlk aşamada mevcut CI ve ücretsiz test olanaklarıyla çözülebilenler çözülür.
Çözülemeyenler hazır kaynak, test çıktıları ve Release taslağı ile somutlaştırılır.
Tek başına derleme veya emulator başarısı bütün cihazlarda stable kanıtı değildir.

## 12. Kaynaklar ve zamanlayıcı

- [WireGuard resmî gömme bileşenleri](https://www.wireguard.com/embedding/)
- [Android VPN, izin, always-on ve lockdown](https://developer.android.com/develop/connectivity/vpn)
- [Android foreground service türleri](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [TV launcher, banner ve kumanda gereksinimleri](https://developer.android.com/training/tv/get-started/create)
- [OpenAI resmi zamanlanmış görev dokümanı](https://learn.chatgpt.com/docs/automations?surface=app)

Bu sohbet için tek seferlik 07:15 başlangıç görevi kurulacaktır. Geliştirme bu
saatten önce başlamaz; bu tur yalnız plan ve zamanlama içindir. Yerel dosyalı
zamanlanmış çalışma için bilgisayar açık, uygulama çalışır ve kaynak klasörü
erişilebilir olmalıdır. İş bittiğinde aynı değişikliği tekrar çalıştırma.
