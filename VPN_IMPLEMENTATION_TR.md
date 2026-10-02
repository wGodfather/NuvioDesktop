# İsteğe bağlı WireGuard — Windows x64 deneysel uygulaması

Bu çalışma yalnızca `wGodfather/NuvioDesktop` fork'u içindir. Yayımlanmış
0.1.28-alpha paketleri değişmez. Sonraki uygulama sürümü, yayın kontrolleri
tamamlandıktan sonra 0.1.29-alpha olarak hazırlanacaktır. Android, Android TV,
iOS, macOS ve Linux için bağlantı desteği bu değişiklikte etkinleştirilmez.

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

Yerel testler VPN açık/kapalı politikası, kesinti, iptal, motor başlatma kilidi,
profil doğrulaması, yerel protokol ve DPAPI şifreleme davranışını kapsar.
`.github/workflows/vpn-windows-tests.yml` güvenlik duvarı ve servis testlerini
geçici Windows yöneticili makinede çalıştırır. Kullanıcının bilgisayarında internet
kesen testler çalıştırılmaz.

`.github/scripts/test-vpn-demo.ps1` yalnızca geçici CI ortamında taze bir anahtar
üretip WireGuard'ın resmî gösterim sunucusuyla gerçek el sıkışması, SYSTEM profil
çözme, servis pipe'ı ve yeniden başlatma davranışını kontrol eder. Bu gösterim
sunucusu kullanıcıların VPN sağlayıcısı olarak sunulmaz. Kullanıcının özel
anahtarı gerekmez. Gösterim sunucusu erişilemezse test başarısız olur; doğrulanmış
bağlantı iddiasında bulunulmaz.

Yayımdan önce tamamlanacaklar:

- P2P'yi destekleyen gerçek sağlayıcıyla çıkış IP'si, torrent yükleme/indirme,
  DNS/IPv6 sızıntısı ve hız karşılaştırması.
- Fiziksel ağ değişimi, uyku/uyanma, uygulama/servis çökmesi, Windows yeniden
  başlatma ve eski soketlerin koruma açılırken yeniden denetlenmesi.
- VPN yardımcı servisinin imzası ve MSI kurulum/yükseltme/kaldırma yaşam döngüsü.
- Yönetici olmayan kullanıcı, ikinci kullanıcı, başka VPN ve Windows sürümleri.
- Fiziksel bilgisayarda ayar ekranı ve oynatıcı/indirme kabul testleri.

Bu kontroller tamamlanmadan deneysel işaret kaldırılmaz, dal birleştirilmez ve
genel sürüm yayımlanmaz. VPN virüs taraması veya mutlak anonimlik sağlamaz.

## Lisans ve kaynaklar

Nuvio yardımcı kodu GPL-3.0-or-later. WireGuard Windows istemcisi MIT, `wg.exe`
GPL-2.0, resmî gömülü WireGuardNT ikilisi kendi dağıtım koşulları altındadır.
Lisans metinleri ve sürüme bağlı kaynak/derleme bağlantıları
`composeApp/src/desktopMain/native/vpn/WireGuard-SOURCES.txt` ile paketlenir.
