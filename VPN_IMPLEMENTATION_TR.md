# İsteğe bağlı WireGuard — Windows x64 deneysel uygulaması

Bu çalışma yalnızca `wGodfather/NuvioDesktop` fork'u içindir. Deneysel VPN dalı,
yayımlanmış 0.1.29-alpha kaynakları üzerine güncellenmiştir. Kitaplık İndirilenler
sekmesi, kaynak indirme butonu ve kaynak filtreleme/sıralama değişiklikleri korunur.
Yayımlanmış 0.1.28-alpha ve 0.1.29-alpha paketleri değiştirilmez; VPN test paketi
ayrı geliştirme çıktısıdır ve sürümü `0.1.30-alpha` olarak ayrılmıştır. Genel VPN
yayını için aşağıdaki yayın kontrolleri gerekir. Bu belgenin aşağıdaki Windows
prototip kanıtları geçmiş uygulamayı anlatır. 3 Ekim 07:15 geliştirmesi Android
telefon/tablet, Android TV ve Google TV backend'ini ekler. Güncel sürüm kodu 31,
MSI 1.1.31; mimari, CI sonuçları ve eksik kabul kapıları VPN_VALIDATION_TR.md'dedir.
iOS, macOS ve Linux bağlantı desteği bu kapsamda etkinleştirilmez.

Kaynak değişikliği: https://github.com/wGodfather/NuvioDesktop/pull/1 (taslak).
Uygulama ve test kodu hazırlanmıştır; aşağıdaki yayın kontrolleri devam eder.

## Kullanım

Windows x64 ayarlarında **WireGuard VPN** sayfası bulunur. Varsayılan kapalıdır.
Kapalı kullanım VPN profili, yönetici izni veya VPN aboneliği gerektirmez.
İlk kurulum Windows yönetici onayıyla kendi kontrol servisini kurar. Kullanıcı
P2P'ye izin veren sağlayıcısından aldığı `.conf` dosyasını içe aktarır.
Bu önizleme bir sunucu, ücretsiz VPN hesabı veya sağlayıcı aboneliği sağlamaz.

Önizlemede tek eş ve tam IPv4 tüneli gerekir. Endpoint ve DNS sayısal IP olmalıdır;
alan adlı endpoint, birden çok eş, bölünmüş yönlendirme ve çalıştırılabilir profil
komutları reddedilir. IPv6 tam tünel isteğe bağlıdır; fiziksel IPv6 çıkışı yine
engellenir. VPN tüm bilgisayarın ağını kapsar; yerel ağ erişimi ve başka VPN'lerle
birlikte kullanım bu aşamada desteklenmez. Yerel oynatıcı/motor iletişimi izinlidir.

VPN açılırken önce sistem koruması etkinleşir, aktif indirmeler duraklatılır ve
TorrServer kapanır. İndirme veya torrent oynatma yalnızca güncel eş el sıkışması
ve mevcut güvenlik duvarı kuralları doğrulandığında başlayabilir. **Bağlantıyı kes**
korumayı açık tutar. **VPN'i kapat** torrent motoru gerçekten durduktan sonra
korumayı kaldırır. İndirmeler kendiliğinden korumasız sürdürülmez.
Bağlantı kaybından sonra yeniden deneme gecikmesi artar; kullanıcının isteyerek
kestiği bağlantı kendiliğinden açılmaz. Uygulama başlangıcında otomatik bağlantı
ayrıca isteğe bağlıdır ve varsayılan kapalıdır.

## Anahtarlar ve servis

Özel anahtar genel ayarlara, komut satırına, günlük veya hata metnine yazılmaz.
Profil boyutu en fazla 16 KiB'dır. Profil stdin ve kullanıcıya bağlı yerel pipe
üzerinden SYSTEM servisine gider. Diskte SYSTEM DPAPI ile şifrelenir; profil
klasörüne SYSTEM/yöneticiler erişir. VPN ayarı cihaz genelinde tutulur.
Servis tek Windows kullanıcısına bağlıdır; ikinci kullanıcı kurulumu reddedilir.
Resmî WireGuard 1.1.1 çalıştırılabilirleri değiştirilmez. İndirilen MSI ve
çalıştırılabilirler sabit SHA-256 ve yayıncı imzasıyla doğrulanır. Yardımcı servis
derlenir; imzalanması yayın kontrolü olarak bekler.

Bağımsız WFP kuralları servisin durması veya uygulamanın çökmesinden sonra kalır.
Normal uygulama kapanışı önce indirmeleri/motoru durdurup korumayı kaldırır.
Çökme sonrasında kullanıcı korumayı kaldırmak isterse bütün TorrServer işlemlerini
kapatıp yönetici terminalinden aşağıdakini çalıştırabilir:

```powershell
& "$env:ProgramFiles/NuvioVpn/NuvioVpn.exe" recover
```

VPN yardımcı servisini/profilini kaldırmak için, yine motor kapalıyken:

```powershell
& "$env:ProgramFiles/NuvioVpn/NuvioVpn.exe" uninstall
```

Yalnızca Nuvio'nun servisleri ve kuralları kaldırılır. Başka VPN'ler veya paylaşılan
WireGuard sürücüsü silinmez. Uygulama MSI kaldırma/onarım işlemlerinin bu yardımcı
servisle otomatik bütünleşmesi henüz yayın kontrolüdür; elle kaldırma komutu vardır.

## Doğrulama ve yayın koşulları

Başarılı kontroller (2 Ekim 2026): masaüstünde 17 VPN politika/protokol testi ve
3 torrent bağlantı testi; Android'de 15 ortak VPN politikası ve 14 mevcut torrent
testi; yardımcı serviste 23 profil, çerçeveleme, sahte servis reddi ve DPAPI testi.
Android testleri Windows geliştirmesinin ortak kodunu doğrular, Android VPN
bağlantı desteği anlamına gelmez.
`.github/workflows/vpn-windows-tests.yml` güvenlik duvarı ve servis testlerini
geçici Windows yöneticili makinede çalıştırır. Kullanıcının bilgisayarında internet
kesen testler çalıştırılmaz.

`.github/scripts/test-vpn-demo.ps1` yalnızca geçici CI ortamında taze bir anahtar
üretip WireGuard'ın resmî gösterim sunucusuyla gerçek el sıkışması, SYSTEM profil
çözme, servis pipe'ı ve yeniden başlatma davranışını kontrol eder. Bu gösterim
sunucusu kullanıcıların VPN sağlayıcısı olarak sunulmaz. Kullanıcının özel
anahtarı gerekmez. Gösterim sunucusu erişilemezse test başarısız olur; doğrulanmış
bağlantı iddiasında bulunulmaz.

Geçici Windows makinesinde 6 bağımsız WFP kontrolü ve 16 servis/gerçek eş kontrolü
geçti: normal kullanıcı hesabıyla servis iletişimi, bağlantı öncesi/sırası/sonrası
fiziksel IPv4 çıkışının engellenmesi, şifreli profil, gerçek WireGuard el sıkışması,
servis yeniden başlatma ve koruma kaldırıldıktan sonra internetin geri gelmesi.
Başarılı çalışma: https://github.com/wGodfather/NuvioDesktop/actions/runs/37012387081
Bu testler tam DNS/IPv6/torrent sızıntı testi veya hız testi yerine geçmez.

Önceki 0.1.28-alpha tabanlı Windows test paketi de başarıyla üretildi:
https://github.com/wGodfather/NuvioDesktop/actions/runs/37039846218
Paketin uygulama kaynak commit'i `464defa970b4d01cd3a3facc3513b974276e3e19`.
MSI SHA-256, içindeki üç VPN çalıştırılabilirinin SHA-256 manifesti, lisans/kaynak
dosyalarının bulunması ve paket içinden çıkarılan yardımcının 23 testi doğrulandı.
Paket kurularak çalıştırılmadı; test dosyasıdır ve sürüm yayını değildir.
Bu önceki test paketinde taban sürüm numarası 0.1.28-alpha korunmuştu.

3 Ekim 2026 güncellemesinin tabanı `0.1.29-alpha` etiketi,
`f3e2502ba58052ac3201b7b3c6ca4033278ebf65` commit'idir. Yayımlanmış sürümün
Windows/Android arayüz ve kaynak listeleme değişiklikleri VPN dalına taşınmıştır.
VPN hâlâ yalnızca Windows x64'te ve varsayılan kapalıdır. Bu geliştirme çıktısının
VPN geliştirme paketi `0.1.30-alpha` (sürüm kodu 30) olarak numaralandırılır;
yayımlanmış 0.1.29 paketinde VPN bulunmaz. Ortak sürüm dosyası Android derlemelerinin
numarasını da belirler, ancak bu çalışmada Android VPN paketi üretilmez.

0.1.29 tabanında 59 masaüstü testi geçti: VPN politika/protokol, magnet,
kaynak filtreleme/sıralama, kaynak menüsü, ayrıştırma, video doğrulama ve HLS
indirme kontrolleri. Yerel testte WebView2/MSVC bulunmadığından oynatıcı köprüsü
derleme görevi atlandı; aşağıdaki Windows CI paketlemesinde bu görev de geçti.
Android host ortamında 83 test geçti: yayımlanmış 0.1.29'un 68 kaynak/indirme/
arama/katalog testi ve 15 ortak VPN politika testi. Bu çalışmada yeni Android
APK veya fiziksel cihaz VPN testi yapılmadı; Android bağlantı desteği etkin değildir.

Önceki 0.1.29 taban numarasıyla üretilmiş Windows test paketi:
https://github.com/wGodfather/NuvioDesktop/actions/runs/37080976116
Uygulama kaynak commit'i `7756f8af`; MSI SHA-256:
`e8c241f73025b3552895707b23979fc5362c25faca45e01b8784f94942cab01e`.
İndirilen MSI, paket içindeki üç VPN bileşeni, dört lisans/kaynak dosyası ve
paketten çıkarılan yardımcının 23 testi doğrulandı. MSI bilgisayara kurulmadı.

Yeni geçici Windows bağlantı/güvenlik duvarı kontrolü:
https://github.com/wGodfather/NuvioDesktop/actions/runs/37080978546
23 yardımcı program, 6 WFP ve 16 servis/gerçek eş kontrolü ikinci çalıştırmada
geçti. İlk çalıştırmada 60 saniyede el sıkışması doğrulanamadı; aynı kaynakla
tekrar çalıştırma geçti. İlk hatanın nedeni kesinleşmedi ve bu değişken sonuç
genel sağlayıcı uyumluluğu kanıtı sayılmaz. Doğrulanmamış bağlantı korumalı
olarak raporlanmadı.

## 0.1.30-alpha sürüm numarası ve paket doğrulaması

Kullanıcının isteğiyle VPN paketinin sürümü `0.1.30-alpha`, kodu `30` yapıldı.
Windows testleri ve MSI paketlemesi geçti:
https://github.com/wGodfather/NuvioDesktop/actions/runs/37086247592
Paketin kaynak commit'i `3b350d62`; MSI SHA-256:
`24b1d43143945c12286c24c7497d9d9c74339aaedeb9d52aac4ca58fb268d819`.
İndirilen MSI içindeki uygulama sabitleri `0.1.30-alpha / 30` olarak doğrulandı.
Windows kurulum veritabanındaki sürüm `1.1.30` olarak doğrulandı; mevcut paketleme
işlevi Windows/JDK gereği ilk bileşeni en az 1 yapar. Uygulamada görünen sürüm
`0.1.30-alpha` olarak kalır. Üç VPN ikilisinin manifest sağlama değerleri,
lisans/kaynak dosyaları ve paket yardımcısının 23 testi geçti. MSI kurulmadı;
0.1.29'dan gerçek kurulum yükseltme testi henüz yapılmadı. Bu paket deneysel
geliştirme çıktısıdır; GitHub'da genel sürüm yayımlanmadı.

Taslak PR'daki upstream'den kalan şablon kontrolü, bağlantılı bir talep/issue
olmadığı için başarısızdır. Fork sahibinin uygulama isteği bu sohbetten gelir;
GitHub üzerinde verilmiş bir issue onayı varmış gibi gösterilmez.

Yayımdan önce tamamlanacaklar:

- P2P'yi destekleyen gerçek sağlayıcıyla çıkış IP'si, torrent yükleme/indirme,
  DNS/IPv6 sızıntısı ve hız karşılaştırması.
- Fiziksel ağ değişimi, uyku/uyanma, uygulama/servis çökmesi, Windows yeniden
  başlatma ve eski soketlerin koruma açılırken yeniden denetlenmesi.
- VPN yardımcı servisinin imzası ve MSI kurulum/yükseltme/kaldırma yaşam döngüsü.
- İkinci kullanıcı, başka VPN, Windows sürümleri ve fiziksel bilgisayarda
  yönetici olmayan kullanıcı kabul testleri. CI'da normal kullanıcı hesabı geçti.
- Fiziksel bilgisayarda ayar ekranı ve oynatıcı/indirme kabul testleri.

Bu kontroller tamamlanmadan deneysel işaret kaldırılmaz, dal birleştirilmez ve
genel sürüm yayımlanmaz. VPN virüs taraması veya mutlak anonimlik sağlamaz.

## Lisans ve kaynaklar

Nuvio yardımcı kodu GPL-3.0-or-later. WireGuard Windows istemcisi MIT, `wg.exe`
GPL-2.0, resmî gömülü WireGuardNT ikilisi kendi dağıtım koşulları altındadır.
Lisans metinleri ve sürüme bağlı kaynak/derleme bağlantıları
`composeApp/src/desktopMain/native/vpn/WireGuard-SOURCES.txt` ile paketlenir.
