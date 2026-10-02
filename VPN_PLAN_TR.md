# Nuvio — İsteğe bağlı WireGuard VPN entegrasyon planı

Tarih: 2 Ekim 2026. Durum: Windows x64 deneysel uygulaması geliştirme dalında hazırlanıyor; henüz yayımlanmış sürüme eklenmedi. İncelenen proje: NuvioDesktop-Fork. Kullanıcı kararı: VPN kullanımı isteğe bağlı olacak. Güncel uygulama ve test sınırları `VPN_IMPLEMENTATION_TR.md` dosyasındadır.

## 1. Amaç ve sınırlar

VPN kapalıyken mevcut Nuvio kullanımı devam eder. VPN'i açan kullanıcı için torrent indirme, torrent oynatma ve yükleme bağlantıları VPN dışına çıkmamalıdır. Koruma kurulamadığında işlem bekler; normal internet bağlantısına otomatik geri dönüş yapılmaz.

WireGuard şifreli tünel bileşenidir; sunucu veya abonelik sağlamaz. İlk sürüm, kullanıcının P2P destekleyen sağlayıcısından aldığı bağlantı dosyasını kullanır. Sağlayıcı satın alma, kendi sunucumuzu kiralama ve işletme bu planın otomatik sonucu değildir. Kullanıcının ücretsiz sunucu erişimi olduğu varsayılmaz.

VPN kötü amaçlı dosyaları temizlemez ve tam anonimlik vaat etmez. VPN işletmecisi bağlantı IP'sini görebilir; trafik kaydı politikası ve bağımsız denetimler sağlayıcı seçiminde ayrıca değerlendirilir. Kendi VPS'imizin bize özel çıkış IP'si de kimlikle ilişkilendirilebilir. Hedef, torrent kullanıcılarına ev IP'sinin görünmesini engellemek ve cihaz–VPN sunucusu arasındaki bağlantıyı şifrelemektir.

## 2. Mevcut koddan çıkan gereksinimler

| İncelenen alan | Mevcut durum | Tasarıma etkisi |
|---|---|---|
| Desktop P2pStreamingEngine | TorrServer ayrı işlem olarak başlatılıyor | Ağ koruması Java uygulamasıyla sınırlanamaz |
| TorrServer yerel API | 127.0.0.1 üzerinden iletişim | Yerel oynatıcı ve indirme trafiği engellenmemeli |
| Desktop DownloadsPlatformDownloader | İndirmeler aynı TorrServer motorunu kullanıyor | Oynatma ve eşzamanlı indirme birlikte yönetilmeli |
| TorrServer çalıştırılabilir dosyası | Paket, yerel dizin ve ortam değişkeninden çözümlenebiliyor | Korunan motorun yolu ve kimliği doğrulanmalı |
| Android P2pStreamingEngine | Nuvio Engine oynatma oturumu | Windows motoruna özgü çözüm Android'e taşınamaz |
| AndroidTorrentDownload | İndirme kendi motor oturumuna sahip | Bütün oturumları kapsayan ortak ağ politikası gerekir |
| DesktopStorage | Genel ayarlar yerel dosyada saklanıyor | VPN özel anahtarı bu genel ayar deposuna yazılmamalı |
| Windows paketleme | Compose/Gradle MSI dağıtımı | Yetkili servis, sürücü, güncelleme ve kaldırma yaşam döngüsü eklenmeli |

İlk somut hedef Windows masaüstüdür. Ortak ayar/durum modeli Android'e uygun hazırlanır; Android bağlantı uygulaması ayrı aşamadır. macOS ve Linux'ta Windows'a ait koruma düğmesi çalışıyormuş gibi gösterilmez; platform uygulaması tamamlanınca etkinleştirilir.

## 3. Kullanıcı davranışı ve varsayılanlar

| Ayar | İlk kurulum değeri | Davranış |
|---|---|---|
| VPN kullan | Kapalı | VPN kurulumu zorunlu değil, normal torrent kullanımı mümkün |
| Uygulama açılışında bağlan | Kapalı | Sadece kullanıcı etkinleştirirse otomatik bağlantı |
| VPN kopunca bağlantıları engelle | VPN açıkken zorunlu | Kullanıcıya yanıltıcı bir güvensiz VPN modu sunulmaz |
| Yeniden bağlanmayı dene | Açık | Artan bekleme aralıklarıyla aynı profile tekrar bağlanma |
| Profil | Yok | İçe aktarma yapılmadan bağlantı başlamaz |
| Bağlantı kapsamı | Windows ilk sürümünde bilgisayarın internet trafiği | Kapsam bağlantı öncesinde açıkça gösterilir |
| Açılışta torrentleri kendiliğinden sürdür | Mevcut tercihe bağlı | VPN gereken modda koruma doğrulanmadan hiçbir ağ işi sürdürülmez |

VPN tercihi cihaz ve işletim sistemi kullanıcısına bağlıdır; Nuvio içerik profili değiştirilince sıfırlanmaz. Özel anahtarlar hesap senkronizasyonu, yedek dışa aktarma veya eklenti API'lerine dahil edilmez. Aynı bilgisayardaki birden fazla Nuvio penceresi tek servis oturumunu kullanır. Bir işletim sistemi kullanıcısı diğerinin VPN anahtarını okuyamaz; sistem genelindeki tünel çakışmaları açıklayıcı hatayla ele alınır.

VPN kapalı kullanıcının her indirmesinde tekrar tekrar pencere gösterilmez. Ayarlarda ve torrent başlangıç ekranında sakin bir “VPN kapalı” bilgisi bulunabilir. “P2P açık” veya “yükleme kapalı” ayarı VPN koruması anlamına gelmez; yalnızca indiren torrent istemcisi de dış bağlantılar kurar.

## 4. Ayarlar ve durum ekranı

Ayarlar > Ağ ve VPN sayfasında VPN kullan anahtarı, profil içe aktarma, profil adı, sunucu adresi/konumu (biliniyorsa), bağlan, yeniden dene, bağlantıyı kes ve korumayı kapat bulunur. Bağlantı kapsamı ve “VPN virüs taraması yapmaz” açıklaması kısa metinle gösterilir. İsteğe bağlı gelişmiş bölümde MTU, tanı bilgisi ve profil silme bulunur. Özel anahtar varsayılan olarak gösterilmez.

Bağlantı durumu: Kapalı; Hazır; Bağlanıyor; VPN bağlı; Bağlantı kesildi — trafik engellendi; Yeniden bağlanıyor; Kurulum/izin gerekli; Hata — koruma kurulamadı.

“VPN bağlı” yalnızca arayüzün varlığına bakılarak gösterilmez: servis, tünel, yönlendirme, güvenlik duvarı ve DNS politikası birlikte doğrulanır. Son handshake zamanını tek başına sağlık testi saymayız; boşta kalan WireGuard tünelinde yeni handshake olmayabilir. Yerel doğrulama ile bağlantı kontrolü ayrı değerlendirilir.

İsteğe bağlı “Çıkış IP'sini kontrol et” yalnızca tünelden yapılır; kontrol hizmetine IP gönderildiği açıklanır. Hizmet arızası otomatik olarak veri sızıntısı kanıtı sayılmaz. VPN kapalıyken kullanıcı istemeden dış IP kontrolüne istek gönderilmez. Hiçbir ekran “virüssüz” ya da “tam anonim” rozeti taşımaz.

## 5. Durum geçişleri ve kesinti politikası

| Olay | Torrent davranışı | Koruma davranışı |
|---|---|---|
| VPN kapalı, yeni torrent | Normal bağlantı ile başlar | VPN kurulmaz |
| VPN açılıyor | Aktif ağ oturumları durdurulur, yeni işler bekler | Önce engelleme, sonra tünel kurulumu |
| Koruma doğrulandı | Kullanıcı açık işlemleri VPN üzerinden sürdürebilir | VPN dışı çıkış engellenir |
| Sunucu yanıt vermiyor | Kuyruk bekler, normal internete geçmez | Engelleme korunur |
| VPN koptu / adaptör kaldırıldı | Ağ işi durur; önbellekteki video oynayabilir | Sistem düzeyinde engelleme sürer |
| Sunucu/profil değişti | İşlemler geçici bekler | Eski tünel kaldırılırken koruma kaldırılmaz |
| Kullanıcı yalnızca bağlantıyı kesti | Torrentler bekler | Koruma açık kalır |
| Kullanıcı VPN korumasını kapattı | Önce motorlar durdurulur; işler duraklatılmış kalır | Sonra bize ait kurallar ve tünel kaldırılır |
| Uygulama çöküyor | Servis motorları denetler/durdurur | Sessiz doğrudan bağlantı olmaz |
| Koruma servisi çöküyor | Yeni işler başlamaz | Kalıcı engelleme kalır; kurtarma gerekir |
| Bilgisayar uyandı / ağ değişti | Sağlık tekrar doğrulanana kadar bekler | Wi-Fi, Ethernet, IPv6 ve yeni adaptörler kapsanır |

VPN açılması, daha önce VPN kapalıyken gerçekleşmiş bağlantıları geriye dönük gizleyemez. Geçişte açık torrent soketleri ve yükleme bağlantıları kapatılır; yalnızca yeni indirme başlatılmasını engellemek yeterli değildir. Duraklatma, motorun DHT/tracker/yükleme trafiğini gerçekten kesmeli; uygulama arayüzündeki bir durum etiketiyle yetinilmemelidir.

Aktif torrent varken korumayı kapatmanın somut sonucu uygulamada açıklanır: “Torrentler duraklatılacak. Yeniden başlatırsanız normal bağlantınız kullanılacak.” Bu, çalışma sırasında tekrar tekrar geliştirici izni istemek değildir; son üründe kullanıcının bilinçli mod değiştirmesidir. VPN kapatılınca duraklatılmış torrentler kendiliğinden doğrudan bağlantıda sürdürülmez.

İlk Windows sürümünde Nuvio'nun normal kapanışı arka plan indirmelerini duraklatır, motorların gerçekten durduğunu doğrular, sonra tüneli ve bize ait sistem genelindeki engellemeyi temizler. VPN kullan tercihi saklanır; otomatik bağlantı kapalıysa sonraki açılışta torrentler kullanıcı bağlanana ya da korumayı kapatana kadar bekler. Arka plan indirmesi desteği ayrı bir servis yaşam döngüsü aşamasıdır. Kapanış sırasında hata varsa koruma erken kaldırılmaz; kurtarma ekranı sunulur. Diğer uygulamalara, Nuvio kapalıyken de VPN koruması devam ettiği sözü verilmez.

## 6. Windows mimarisi ve kapsam kararı

Ortak VPN durum modeli ve politika koordinatörü; düşük yetkili Nuvio ayarlar arayüzü; Windows'a özgü yetkili kontrol servisi; resmi WireGuard gömülebilir tünel servisi; Windows Filtering Platform üzerinden ağ politikası kullanılır. Ana uygulama yönetici olarak çalıştırılmaz. Servis kurulumu işletim sisteminin yönetici iznini gerektirir. [Resmi gömme yaklaşımı](https://www.wireguard.com/embedding/), [tünel servisi örneği](https://git.zx2c4.com/wireguard-windows/about/embeddable-dll-service/README.md).

Windows ilk sürümünde tam tünel önerilir. Bilgisayarın diğer internet bağlantıları da VPN'den etkilenir; bağlantı kopunca sistem genelindeki internet erişimi geçici engellenebilir. Yerel ağdaki yazıcı/casting erişimi etkilenebilir; ilk sürümde geniş LAN istisnası verilmez. Nuvio–TorrServer arasındaki loopback trafiği korunur.

“Sadece Nuvio” seçeneği, Windows'ta bir ayar anahtarından ibaret değildir. TorrServer dahil süreç kapsamı, uygulamaya özgü yönlendirme, DNS ve yardımcı süreçler birlikte tasarlanmalıdır. Güvenli uygulama ve kesinti testleri tamamlanmadan bu seçenek gösterilmez. Kullanıcı sadece uygulama kapsamını isterse bu ayrı geliştirme hedefidir; ilk sürümün daha dar kapsamlı olduğu iddia edilmez.

Resmi Windows tünel servisinde tek peer ve /0 rota içeren yapılandırmalar bağlantı sırasında kill-switch kuralları etkinleştirebilir. Bu, servis çökmesi, profil değiştirme, tünel kaldırılması ve açılış arasındaki bütün boşlukları tek başına kapattığı anlamına gelmez. Ek yaşam döngüsü koruması gereklidir. [Windows ağ davranışı](https://git.zx2c4.com/wireguard-windows/about/docs/netquirk.md).

WFP kuralları kimlikli, bize ait ve atomik işlemlerle uygulanır. Dinamik WFP oturumu bitince kurallar silinebildiğinden güvenlik yalnızca böyle bir oturuma bırakılmaz. Kalıcı engelleme ve gerekirse açılış koruması, servis yeniden başlatma/kurtarma prosedürüyle birlikte prototipte doğrulanır. Kural öncelikleri, mevcut bağlantıların yeniden değerlendirilmesi ve adaptör değişimi özellikle test edilir. [Microsoft WFP yaşam döngüsü](https://learn.microsoft.com/en-us/windows/win32/fwp/object-management).

Gerekli dış tünel trafiği sadece VPN servisinin belirli endpoint bağlantısı için izinlidir. DHCP/NDP gibi ağın çalışması için gereken trafik ayrı ele alınır. Torrent motoru bu istisnaları kullanamaz. Servis ile arayüz arasındaki yerel iletişim erişim listesiyle korunur; keyfi komut, program yolu, güvenlik duvarı kuralı veya script çalıştıran yönetici API'si yapılmaz. DLL'ler mutlak güvenilir yoldan yüklenir; sıradan kullanıcıların yazabildiği dizinlerden yetkili kod çalıştırılmaz.

## 7. Bağlantı dosyası, anahtarlar ve DNS/IPv6

İlk sürüm tek peer ve tam tünel profilini destekler. Dosya boyutu, tekrar eden alanlar, anahtar biçimi, adresler, endpoint portu, DNS ve rotalar doğrulanır. PostUp/PostDown, PreUp/PreDown veya eşdeğer komut çalıştırma alanları reddedilir; dosya içindeki komutlar hiçbir durumda yürütülmez. Bilinmeyen alanlar sessizce güvenlik davranışını değiştiremez. Kısmi tünel dosyası güvenliymiş gibi kabul edilmez; uyumsuzluk açıklanır.

İlk güvenlik prototipinde IP adresi olan endpoint kullanılır. Alan adı olan endpoint desteği, tünel öncesi yalnızca VPN sunucusunu çözümleyen sınırlı DNS başlangıç akışı doğrulanınca eklenir. Bu başlangıç isteğinin VPN dışında olabileceği açıkça belgelenir; torrent tracker/peer veya içerik DNS sorguları bu yoldan çıkamaz. Sağlayıcı dosyalarında alan adı kullanımı yaygın olabileceğinden genel kullanıma uygunluk kapısında bu uyumluluk ayrıca değerlendirilir.

IPv4 trafiği tünele yönlendirilir. IPv6 destekleyen profilde IPv6 da tünele gider; desteklemeyende VPN dışı IPv6 engellenir. Windows genelinde IPv6 kalıcı kapatılmaz. DNS politikası VPN'in DNS adreslerini kullanır, yönlendirme ve uygulamaların fiziksel adaptöre bağlanma ihtimali test edilir. Yerel API için 127.0.0.1/::1 istisnaları belirlenir; tüm yerel alt ağa izin verilmez.

Windows'ta anahtarlar işletim sistemi korumalı depolama/DPAPI ve uygun dosya izinleriyle saklanır. Servis tarafından gereken çalışma dosyası gerekiyorsa yalnızca yetkili hesapların okuyabildiği dizinde tutulur; sırlar loglanmaz. Şifreli disk depolaması çalışan servisin belleğinden anahtar alınmasını önlediği iddiası taşımaz. Profil silme, çalışma dosyası, bellekteki referanslar ve bize ait servis yapılandırmasının temizlenmesini kapsar; SSD'de kesin fiziksel silme vaat edilmez. Kullanıcının dışarıdaki orijinal .conf dosyası izinsiz silinmez.

## 8. Torrent, oynatma ve indirme entegrasyonu

Ortak koordinatör torrent başlamadan, magnet motoruna verilmeden, tracker/DHT erişimi kurulmadan ve otomatik devamdan önce ağ politikasını kontrol eder. Windows'ta bütün TorrServer başlangıç yolları aynı kapıdan geçer. Özel binary yolu kullanılırsa yol/kimlik korumaya kaydedilmeden süreç başlatılmaz. Mevcut kimliği belirsiz yerel TorrServer'a sağlık yanıtı verdi diye güvenilmez; koruma modunda süreç sahipliği ve kullanılan dosya doğrulanır.

Motor önbelleği veya kalıcı durumu açıldığında otomatik tracker bağlantısı kurulabilirse güvenlik duvarı motor başlamadan önce hazır olur. Tracker, DHT, PEX, TCP/UDP peer bağlantıları, uTP ve yükleme trafiği birlikte kapsanır. UPnP/NAT-PMP'nin ev yönlendiricisinde port açması koruma modunda engellenir; sağlayıcıya ait port yönlendirmesi ayrı uygulanır. Yerel eş keşfi ilk sürümün korumalı modunda kapatılır ya da ağ kurallarıyla sınırlandırılır.

İndirme kuyruğunda “VPN bekleniyor” ve “VPN kesildi” durumları gerçek indirme hatasından ayrılır. Kısmi dosyalar/önbellek korunur; VPN gelince aynı dosyada devam davranışı doğrulanır. Eşzamanlı oynatma ve indirme arasında biri kapanınca diğerinin tüneli kaldırılmaz. Torrent yükleme tercihi korunur; VPN açık olması yüklemeyi kendiliğinden açmaz/kapatmaz.

HTTP video indirmeleri, kataloglar, eklenti istekleri ve harici oynatıcılar Windows tam tünel kapsamından etkilenir. Uzak debrid servisinin sunucuda yaptığı torrent indirmesini cihaz VPN'i koruyamaz; orada güven ilişkisi debrid sağlayıcısıyladır. Bu fark ürün açıklamasında korunur.

## 9. Performans ve sağlayıcı uyumluluğu

İlk sürüm sağlayıcıdan bağımsız .conf içe aktarma kullanır. Uyumluluk için P2P izni, aktarım kotası, hız sınırı, yakın sunucu seçenekleri, IPv6/DNS, bağlantı dosyası verme desteği, cihaz sınırı ve anahtar yenileme davranışı incelenir. Ücretsiz planların P2P desteklediği varsayılmaz.

Port yönlendirmesi zorunlu değildir; bazı torrentlerde erişilebilirliği ve hızı artırabilir. Sağlayıcı desteği, geçici port yenilemesi ve TorrServer'a port aktarımı doğrulanmadan otomatik açılmaz. Ev yönlendiricisine port açmakla karıştırılmaz. Gelen bağlantı yalnızca gereken torrent portuna sınırlandırılır; genel yönetim API'si dışarı açılmaz. [P2P ve port yönlendirmesi](https://protonvpn.com/support/port-forwarding).

Önce sağlayıcının normal MTU değeri kullanılır; paket kaybı/bağlantı sorununda kontrollü ayar sunulur. Çok atlamalı bağlantı ilk sürümün varsayılanı değildir. Hız ölçümü, aynı cihaz/ağda, yakın zamanlarda, kontrollü dosya ve sabit kaynakla yapılır; torrent swarm değişiminin sonucu çarpıtabileceği kaydedilir. Ek olarak CPU, bellek, disk, oynatma başlama süresi ve kararlılık ölçülür. Sırf tarayıcı hız testi başarılı diye torrent hızı doğrulanmış sayılmaz.

Hedef değerler ilk ölçümden sonra belirlenir; sabit yüzde hız kaybı garantisi verilmez. İsteğe bağlı karşılaştırmalı VPN kapalı testi kullanıcının test IP'sini görünür kılabilir; yalnızca kendi kontrollü test paylaşımımızda ve açık tercih ile yapılır. Korumalı gerçek torrent için otomatik VPN kapalı hız testi yapılmaz. [Hız etkenleri](https://protonvpn.com/support/increase-vpn-speeds).

## 10. Dosya güvenliği ve tanılama

VPN özelliği antivirüs olarak pazarlanmaz. İndirme ve video doğrulama davranışı korunur; Defender ve oynatıcı güncellemeleri önerilir. Video dosyası da oynatıcı açığından yararlanabilir. Dosya uzantısı tek başına güvenlik kanıtı değildir. Zararlı script/çalıştırılabilir dosyalar otomatik açılmaz. Ek antivirüs entegrasyonu istenirse ayrı gereksinim ve hata/karantina davranışı tasarlanır. [Microsoft korunma önerileri](https://support.microsoft.com/en-us/windows/security/threat-malware-protection/protect-your-pc-from-unwanted-software).

Tanı kayıtları hata kodu, süre, servis sürümü ve durum geçişleriyle sınırlanır. Özel anahtar, tam yapılandırma, gerçek/çıkış IP'si, magnet, infohash, tracker URL'si ve dosya adı hassas sayılır. Mevcut TorrServer çıktı loglama yolu redaksiyon için denetlenir. VPN tanısı otomatik hesap senkronizasyonu veya hata raporlamasına eklenmez; paylaşılacak rapor kullanıcıya önizlenir. Tekrarlanan bağlantı hataları log boyutunu sınırsız büyütemez.

## 11. Kurulum, güncelleme, kurtarma ve kaldırma

VPN kapalı kullanıcının açılışında servis kurma veya yönetici izni isteme yapılmaz. Gerekli bileşen ilk etkinleştirmede kurulabilir; MSI ile ayrı servis kurulumu seçeneği prototip aşamasında doğrulanır. Bileşen eksik/kurulum iptal edilmişse VPN açıkmış gibi görünmez; koruma isteyen torrent işleri bekler. VPN kapalı kullanıma dönmek kullanıcı tercihidir.

WireGuard DLL/sürücü ve yardımcı servis resmi kaynaklardan, sabitlenmiş sürüm ve sağlamalarıyla hazırlanır; imza doğrulaması uygulanır. Nuvio yardımcı servisinin imzalanması için gereken sertifika mevcut değilse dağıtım engeli olarak kaydedilir. İmzasız kodun Windows'ta teknik olarak hiç çalışmayacağı iddia edilmez; imza ve paket güvenilirliği teslim kalitesi şartıdır. Desteklenen x64/ARM64 gibi her mimari bağımsız doğrulanır. Lisanslar ve kaynak erişimi paket atıflarına eklenir; farklı WireGuard bileşenlerinin aynı lisansı taşıdığı varsayılmaz.

Güncellemede aktif motorlar durdurulur; koruma kaldırılmadan yeni bileşenler kurulur. Güncelleme başarısızsa sessiz VPN kapalıya geçiş yapılmaz. Başka VPN, güvenlik yazılımı ve kurumsal ağ politikaları algılanır; diğer servisler otomatik kapatılmaz. Çakışma çözülemezse korumalı işler bekler.

Kurtarma işlemi önce TorrServer/Nuvio ağ işlerinin durduğunu doğrular, sonra yalnızca bize ait servis/kuralları kaldırabilir. Genel Windows güvenlik duvarı sıfırlanmaz. Uygulama açılmasa da kullanılabilen imzalı yerel kurtarma aracı gerekir. Kalıcı engellemenin yeniden başlatmada etkinliği ve Nuvio servisinin devre dışı bırakılması ayrıca test edilir. Kullanıcıyı açıklamasız biçimde internetsiz bırakmak kabul edilmez; sistem bildirimi ve geri kazanma yolu sağlanır.

Kaldırma ve önceki sürüme dönüşte tünel, süreçler, bize ait kurallar ve sırlar temizlenir; başka uygulamanın WireGuard sürücüsü/servisi kaldırılmaz. Sürücü paylaşımı ihtimali kontrol edilir. Profilin dışarıdaki orijinal dosyası ve indirilen videolar VPN kaldırmanın parçası olarak silinmez.

## 12. Android aşaması

Ortak tercih/durum/politika modeli korunur. Android uygulaması resmi WireGuard tunnel kütüphanesi ve VpnService yaklaşımıyla yapılır. İlk bağlantıda işletim sisteminin VPN izin ekranı gereklidir; ön planda servis bildirimi, pil yönetimi, arka plan sınırları ve mevcut başka VPN ile çakışma ele alınır. Android'de aynı kullanıcı için etkin VPN çakışması Windows'takiyle aynı varsayımla yönetilmez. [Resmi gömme bileşenleri](https://www.wireguard.com/embedding/), [Android VPN rehberi](https://developer.android.com/develop/connectivity/vpn).

Oynatma ve ayrı indirme motor oturumları aynı politikanın altına alınır. Native motor soketlerinin tüneli kullanması fiziksel cihazda doğrulanır; VPN'in kendi dış bağlantısı dışında uygulama trafiğine bypass izni verilmez. Sistem always-on ve VPN dışı bağlantıları engelleme seçenekleri, kullanıcının işletim sistemi ayarıdır. Uygulama öldürülünce yalnızca arayüz düzeyindeki durdurma mantığıyla sızıntı olmaması garanti edilmez; güçlü kesinti koruması sistem engellemesi ve cihaz testleriyle değerlendirilir.

Android'de uygulama kapsamlı VPN mümkün olsa da sistemin VPN dışı bağlantıları engelleme ayarı, kapsam dışı uygulamaların internetini etkileyebilir; “diğer uygulamalar hiç etkilenmez” sözü verilmez. Yerel motor HTTP iletişimi korunur. Ağ değişimi, mobil veri, ekran kapalı indirme, süreç öldürme, izin geri alma, başka VPN açma ve yeniden başlatma fiziksel ARM cihazlarda test edilir. Mağaza dağıtımı planlanırsa o tarihteki VpnService yayın kuralları ayrıca doğrulanır. macOS/Linux çalışması için kendi yetki/servis/yönlendirme tasarımı gerekir.

## 13. Doğrulama ve yayın kapıları

| Kontrol | Kabul ölçütü |
|---|---|
| VPN kapalı kullanım | Mevcut arama, oynatma ve indirme davranışı; zorunlu VPN izni yok |
| Bağlanırken torrent | Koruma doğrulanmadan tracker/DHT/peer trafiği başlamıyor |
| VPN bağlı torrent | Kontrol edilen uzak peer yalnızca VPN çıkış IP'sini görüyor |
| Bağlantı kopması | Fiziksel ağda doğrudan torrent/DNS çıkışı görülmüyor |
| Servis/arayüz çökmesi | Her iki süreç ayrı ayrı öldürülünce sessiz sızıntı yok |
| Adaptör ve IPv6 değişimi | Wi-Fi, Ethernet, USB ağ, mobil hotspot ve IPv6 sızıntısı yok |
| Önceden açık bağlantılar | Mevcut TCP/UDP peer, tracker ve yükleme soketleri güvenli geçişte kapanıyor |
| Sunucu/profil değiştirme | Eski ve yeni tünel arasında korumasız boşluk yok |
| DNS/başlangıç çözümlemesi | İzinli altyapı trafiği dışında içerik/torrent DNS'i fiziksel ağdan çıkmıyor |
| Çoklu işler | Oynatma, indirme, iptal ve yükleme arasında tünel erken kaldırılmıyor |
| Önbellek ve devam | Kısmi dosya bozulmuyor; aynı seçili dosyada devam ediliyor |
| Sırlar | Log, senkronizasyon, hata raporu ve paketlerde anahtar bulunmuyor |
| Yetki sınırı | Yerel iletişimden keyfi yönetici komutu/başka kullanıcı profili erişimi mümkün değil |
| Yeniden başlatma | Koruma isteyen işler kilit doğrulanmadan yeniden başlamıyor |
| Kurtarma/kaldırma | İnternet geri geliyor; başka VPN'in kuralları/sürücüleri bozulmuyor |
| Paket ve mimari | Temiz kurulum, yükseltme, başarısız kurulum, kaldırma ve hedef mimariler geçiyor |

Güvenlik testleri kendi küçük test videosu, kontrollü torrent paylaşımı ve bize ait sunucu/peer ile yapılır. Fiziksel adaptör paket kaydı VPN'in şifreli UDP paketlerini görür; bunlar beklenen trafiktir. Yasak olan torrent/DNS verisinin tünel dışında doğrudan çıkmasıdır. Test ortamının izinli altyapı istisnaları önceden tanımlanır. Testler üretim kullanıcısının gerçek torrent adını/anahtarını raporlamaz.

Birim testleri karar tablosu ve kötü/uyumsuz .conf dosyalarını kapsar. Sistem testleri gerçek servis, sürücü, WFP, ağ ve motor davranışını doğrular. Ağ katmanı denenmeden yalnızca ekran/birim testleriyle “sızıntısız” denmez. “Test edilen koşullarda doğrudan sızıntı gözlenmedi” ifadesi kullanılır; her koşulda mutlak güvenlik vaat edilmez.

## 14. Uygulama sırası

| Aşama | Somut çıktı | Sonraki aşama için koşul |
|---|---|---|
| 1. Windows güvenlik prototipi | Kontrollü sunucuya tünel + WFP yaşam döngüsü + TorrServer kesinti testi | Servis/tünel çökmesinde koruma ve kurtarma çalışıyor |
| 2. Ortak durum/politika modeli | Tercih saklama, durum geçişleri, bütün torrent giriş kapıları | VPN kapalı normal; açıkken başarısız bağlantı bekliyor |
| 3. Profil ve ayarlar | Güvenli içe aktarma, sır saklama, durum sayfası, Türkçe metinler | Kötü dosya/izin iptali sır sızıntısı veya yanlış “bağlı” üretmiyor |
| 4. Motor ve kuyruk entegrasyonu | Oynatma/indirme/yükleme, kesinti, yeniden bağlanma ve devam | Paralel işler ve önceden açık soketler testten geçiyor |
| 5. Windows paket ve performans | Kurulum/güncelleme/kurtarma, ölçümler, lisanslar | Temiz cihaz ve kesinti testleri tamam; gerçek sunucu uyumlu |
| 6. Windows deneme paketi | MSI, sağlama, kullanım ve bilinen sınırlar | Kullanıcı denemesi; yayın ayrı dağıtım adımı |
| 7. Android uygulaması | VpnService + bütün native oturumlar + APK | Fiziksel cihaz ve sistem engelleme senaryoları doğrulanıyor |
| 8. Sonraki geliştirmeler | Yalnız Nuvio kapsamı, sağlayıcı port yönlendirmesi, arka plan indirme, diğer masaüstü sistemleri | Her özellik için ayrı sızıntı ve yaşam döngüsü kapısı |

Süre tahmini, birinci aşamadaki WFP/MSI prototipinin sonucundan sonra yapılır. Güvenlik prototipi başarısızsa arayüzü bitirmiş olmak yayın gerekçesi değildir. Gerçek sağlayıcı dosyası veya kontrollü test sunucusu, sistem testleri için gereklidir; profil hazırlanırken bunları depoya koymayız. Servis imzası, mimari uyumu, Windows sürümü ve Android cihaz erişimi teslim bağımlılıkları olarak kaydedilir.

Planın önerdiği ilk teslim: Windows için varsayılan kapalı, .conf ile bağlanan, kapsamı açıkça anlatılan tam tünel; açık modda zorunlu kesinti engellemesi; TorrServer dahil oynatma/indirme koruması; güvenli anahtar saklama; kurulum ve kurtarma yolu; kontrollü ağ testlerinden geçmiş deneme MSI'sı. Uygulama kapsamlı yönlendirme ve Android sürümü, çalıştığı doğrulandıktan sonra eklenir.
