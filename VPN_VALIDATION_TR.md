# Çok platformlu VPN doğrulama raporu — 3 Ekim 2026

**Kararlı yayın kabulü verilmedi.** Testler izole VPN worktree'si ve geçici CI
ortamlarında yürütüldü. Kullanıcının günlük PC'sine VPN/servis/firewall kurulmadı.

## Kaynak ve sürüm

Fork: wGodfather/NuvioDesktop; dal: codex/optional-wireguard-vpn;
[taslak PR #1](https://github.com/wGodfather/NuvioDesktop/pull/1).
Taban: yayımlanmış 0.1.29-alpha, f3e2502ba58052ac3201b7b3c6ca4033278ebf65.
Yayımlanmış dosyalar değiştirilmedi; Kitaplık/indirme/kaynak/arama değişiklikleri korundu.
Deneysel aday 0.1.30-alpha / 31, MSI 1.1.31. Paketleme kaynak commit'i
23f4316c04fd2fbfef4f42b09518f66d5a9d6bff. Son kontrollü peer testi b5bebfe0
kaynağında çalışır; bu fark yalnız tools/vpn-peer test aracındadır, uygulama
kodunda fark yoktur. Paket kaynakları ve doğrulama kaynakları manifestte ayrı yazılır.
Kararlı hedef 0.1.30 / 32, MSI 1.1.32; henüz bu numaralara geçilmedi.

Android 10+ telefon/tablet, Android TV/Google TV ve Windows x64 VPN'i kapsamda.
Android VPN kapalı normal kullanım alt sınırı Android 7 / API 24 olarak korunur.
iOS/macOS/Linux VPN'i etkin değil. ARM APK derlenmesi fiziksel ARM testi değildir.
VPN isteğe bağlı, varsayılan kapalı; sağlayıcı profili gerekir. Virüs taraması yapmaz.

## Uygulanan koruma

Android resmî WireGuard tunnel:1.0.20260102 backend'i ayrı :nuvio_vpn sürecindedir.
Ana süreç VPN Network/netId'sine bağlanır; ağ kaybolunca fiziksel ağa geri
çevrilmez. Yeni VPN callback'i ve profil adresiyle eşleşen yerel UDP kaynak adresi
doğrulanmadan handshake tek başına torrent izni vermez. Oynatma/arka plan indirme
için ayrı JNI oturumları takip edilir. Geçişten önce indirme işleri ve iki motor
kapatılır. Worker/JobService fiziksel socketFactory/DNS seçimini VPN açıkken kullanmaz.
Android lockdown VPN uygulamasının UID'sini muaf tutabildiğinden kendi trafiğimizin
kanıtı sayılmaz. Profil Android Keystore/AES-GCM ile noBackup alanında saklanır.
TV launcher, kumanda odağı, maskeli özel anahtar/PSK ve kaydırılabilir form vardır.

Windows sahibi doğrulanmış SYSTEM broker, SYSTEM DPAPI ve kalıcı IPv4/IPv6 WFP
filtreleri kullanır. MSI SYSTEM bakım işlemleri yükseltme/onarım/rollback boyunca
profil/SID/korumayı korur. Yalnız sahip olunan kaynaklar kaldırılır; paylaşılan
WireGuard sürücüsü silinmez. Resmî WireGuard ikilileri hash/yayıncı imzasıyla doğrulanır.
NuvioVpn.exe ve MSI **imzasız deneysel çıktıdır**; güvenilir yayıncı imzası değildir.

Windows kapalı test ağında public-IP/DNS sorguları native motorun soğuk açılışını
yaklaşık 37 saniyeye uzattı. VPN açık başlangıç 60 saniye ile sınırlandı;
VPN kapalı mevcut 15 saniye sınırı korundu. Bu ölçüm aktarım hızı kabulü değildir.

## Çalıştırılan kontroller

| Kontrol | Doğrulanmış sonuç | Sınır |
|---|---|---|
| Android politika/parser/kapanış | 77ef106c: 53 host testi geçti | JNI ağı ayrıca test edildi |
| Telefon API 29/35 | 77ef106c: her cihazda 4 test geçti | x86_64 emülatör |
| Android TV / Google TV API 34 | 77ef106c: her cihazda 4 test geçti | Sanal TV |
| Google TV API 36 / 16 KB | 77ef106c: 4 test geçti | Sanal x86_64 16 KB |
| Android API 24 | 23f4316c: 4 kontrol geçti; açıklama görünür, VPN anahtarı pasif ve profil formu yok | Bu cihazda gerçek VPN açılmadı |
| Windows broker/WFP/peer | b5bebfe0: 23 helper, 6 WFP ve gerçek şifreli native torrent kontrolleri geçti | Geçici Windows runner |
| Windows MSI | 23f4316c: 86 regresyon, MSVC/WebView2 köprüsü, yükseltme/onarım/rollback/kaldırma geçti | Uyku/reboot/imza ayrıca gerekli |
| Android imzalı paketleme | a3209cf1: 100 host regresyonu, dört ABI, R8, lisans/native SHA, 64-bit 16 KB ELF ve zipalign geçti | ABI üretimi fiziksel cihaz kabulü değil |
| Android 0.1.29 yükseltme | a3209cf1: aynı fork sertifikası, yerinde yükseltme, test tercih hash'i ve release açılışı geçti | Bütün gerçek kullanıcı verileri test edilmedi |
| Android VPN kapalı regresyon | a3209cf1: 3 cihaz testi geçti | API 36 emülatörde arama/kaynak/torrent/Kitaplık İndirilenler |

Android gerçek peer testi iki ayrı native motorun küçük yasal fixture'ı oynatıcıya
veri sunma ve dosyaya indirme yollarından aktardığını SHA-256 ile doğrular.
Windows iki eşzamanlı tüketiciyi aynı gerçek TorrServer motorundan doğrular.
Tracker/peer tünel üzerinden ulaşılır. Fixture testi **video decoder ile 60 dakika
film oynatma kabulü değildir**. Android API 29/35 ve üç TV imajında 10 hold/reconnect,
çalışan motor sırasında ayrı VPN servis PID'sinin çökertilmesi, izin iptali,
yeni native işi reddetme ve fiziksel host HTTP erişiminin kapalı kalması geçti.
Bunlar 10 gerçek Wi-Fi/hücresel ağ değişimi değildir. Windows 10 hold/reconnect,
10 boşta broker yeniden başlatma, ani broker ölümü, yeniden bağlantı ve açık
kapatma/temizleme geçti. Tünel IPv4/IPv6 kaynak IP, sentetik DNS ve fiziksel IPv4
bağlama denemeleri doğrulandı. Tam fiziksel IPv6 pcap veya mevcut her soketin
yeniden denetlenmesi bunlarla kanıtlanmaz.

MSI 0.1.29 ve önceki deneysel 1.1.30'dan yükseltmeyi test etti. Hata enjeksiyonunda
ayrı ProductCode/PackageCode ve yüksek **test** sürümü 1.1.32 kullanıldı. Eski ürün
kaldırıldıktan sonra deferred action gerçekten başarısız oldu; rollback eski JAR
hash'ini, şifreli profili, broker ve korumayı geri getirdi. Bu kararlı paket değildir.
Kaldırmada uygulama veri işareti korundu, sadece VPN kaynakları temizlendi.

## CI kanıtı

- [Android/TV 77ef106c](https://github.com/wGodfather/NuvioDesktop/actions/runs/37106064969)
- [Altı Android/TV ortamı geçti — 23f4316c](https://github.com/wGodfather/NuvioDesktop/actions/runs/37115315253)
- [Son Android/TV b5bebfe0](https://github.com/wGodfather/NuvioDesktop/actions/runs/37115531810)
- [Windows peer 77ef106c](https://github.com/wGodfather/NuvioDesktop/actions/runs/37106067889)
- [Son Windows peer b5bebfe0](https://github.com/wGodfather/NuvioDesktop/actions/runs/37115534357)
- [Windows paket/MSI a918d876](https://github.com/wGodfather/NuvioDesktop/actions/runs/37105637491)
- [Son Windows paket/MSI 23f4316c](https://github.com/wGodfather/NuvioDesktop/actions/runs/37115315259)
- [Android paket/yükseltme a3209cf1](https://github.com/wGodfather/NuvioDesktop/actions/runs/37104666323)
- [Son Android paket/yükseltme 23f4316c](https://github.com/wGodfather/NuvioDesktop/actions/runs/37115317032)

Artifacts JUnit sonuçlarını, canlı fingerprint'i, boş profil/sistem izin ekran
görüntülerini, peer metriklerini, native envanter ve MSI loglarını korur.
Özel anahtar/profil dosyası/full bugreport rapora eklenmez.

## Düzeltilen hatalar

Önceki başarısız testler başarı sayılmadı. Android Handler'da Message geri dönüşüm
yarışı, AGP 9.2.0 RecordTag hatası (9.2.1 yaması), VPN callback/rota doğrulaması,
TV form sınırı ve sistem izin butonlarının gerçek yerleşimine göre kumanda testi
düzeltildi. TV emulator cihaz profili/GPU hataları gerçek TV profili ve yazılım
GPU ile giderildi. API 24 testi tekil metin yerine destek durumunu, görünür
açıklamayı ve kapalı kontrolleri doğrular.

Windows idle pipe kapanışı/broker hazırlık yarışı, MSI action sırası, Upgrade
tablosu anahtarları, COM handle'ları ve fault PackageCode transaction commit'i
düzeltildi. Native TorrServer MSE kullandığından kontrollü peer'e upstream
MSE decoder eklendi; yalnız bilinen test info-hash'i kabul edilir. Araç ürün
paketinde bulunmaz. Public demo peer'de aralıklı handshake zaman aşımının kökü
kesinleşmedi; kontrollü peer başarısı genel sağlayıcı uyumluluğu kabulü değildir.

e0583f57'de API 24 testine eklenen seçici mevcut Compose test API'sinde olmadığı
için instrumentation derlemesi kaldı. 23f4316c'de SemanticsProperties.ToggleableState
kullanıldı; yerel instrumentation derlemesi geçti. Başarısız matris başarı sayılmaz;
eski derleme hatasını taşıyan Android paket workflow'u iptal edildi.
23f4316c Windows peer turunda sabit 51820 test portu runner tarafından reddedildi.
b5bebfe0 test sunucusunda işletim sisteminin boş UDP portunu seçmesi ve gerçek
portu istemciye bildirmesi eklendi; yerel 3 Go testi ve tam Windows peer CI geçti.
Bu ürün kodu değişikliği değildir.

## Kararlı yayını engelleyen eksikler

1. Fiziksel telefon/tablet/TV ve temiz Windows test makinesi yok (kullanıcı bildirdi).
   OEM uyku/ekran kapalı, reboot, Worker/JobService yeniden başlatma, gerçek ağ
   geçişi ve tam kumandalı kaynak/indirme/oynatıcı kabulü cihaz/laboratuvar erişimi gerektirir.
2. Windows kod imzalama sertifikası/hizmeti yok (kullanıcı doğruladı). Yardımcı ve
   MSI için güvenilir zaman damgalı yayıncı imzası gerekir. Kendinden imzalı
   sertifika bu kapıyı geçmez; ücretli hizmet satın alınmadı.
3. P2P destekleyen sağlayıcı/kontrollü uzak peer, fiziksel torrent/DNS/IPv6 pcap,
   mevcut TCP/UDP soketleri, peer/endpoint kaybı, ikinci kullanıcı/başka VPN ve
   platform sürümü matrisi tamamlanmadı. CI peer'i public Internet'e çıkmaz.
4. Üç kapalı/açık hız ölçümü, CPU/RAM/pil, 60 dakika eşzamanlı gerçek video/indirme
   ve 10 gerçek ağ değişimi yapılmadı. vpnSoakSeconds varsayılan sıfırdır;
   isteğe bağlı HTTP probe döngüsü uzun native oynatma kabulü değildir.
   Ölçülmüş hız yüzdesi/25 Mbps kabulü iddia edilmez.
5. APK native SHA/lisans envanteri ve Windows runtime manifesti vardır;
   bütün uygulama bağımlılıklarını kapsayan nihai SBOM ve imzalı stable paketinden
   son yükseltme/dağıtım kabulü henüz tamamlanmadı.
6. Upstream'den kalan PR linked-issue kuralı başarısız. Sohbetten verilen fork
   sahibi izni sahte issue bağlantısına çevrilmedi; PR taslakta kalır.

Kaynak CI için geliştirme dalına pushlanabilir. Bu eksikler tamamlanmadan turkiye
dalına kararlı entegrasyon, public 0.1.30 etiketi veya stable Release yapılmaz.
Kaynağı belirtilmiş deneysel paketler ve Release taslağı korunabilir; taslak
genel Releases listesinde yayımlanmış sürüm değildir.

## Birincil kaynaklar

- [Android VPN](https://developer.android.com/develop/connectivity/vpn)
- [bindProcessToNetwork](https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network))
- [AOSP VPN UID/lockdown](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/connectivity/Vpn.java)
- [AGP 9.2.1 RecordTag yaması](https://developer.android.com/build/releases/agp-9-2-0-release-notes#agp-9-2-1)
- [MSI rollback](https://learn.microsoft.com/en-us/windows/win32/msi/rollback-custom-actions)
