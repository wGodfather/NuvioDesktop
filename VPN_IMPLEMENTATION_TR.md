# İsteğe bağlı WireGuard — Android, TV ve Windows deneysel uygulaması

Bu geliştirme wGodfather/NuvioDesktop fork'unun yayımlanmış 0.1.29-alpha tabanı
üzerindedir. Kitaplık İndirilenler, kaynak indirme butonu ve kaynak/arama
değişiklikleri korunur. Aday 0.1.30-alpha / 31, MSI 1.1.31'dir.
Kararlı hedef 0.1.30 / 32, MSI 1.1.32; kabul kapıları henüz geçilmedi.
[Güncel doğrulama raporu](VPN_VALIDATION_TR.md) ve
[taslak PR](https://github.com/wGodfather/NuvioDesktop/pull/1) esas alınmalıdır.
Önceki Windows prototipi/code 30 artık güncel uygulama kapsamı değildir.

## Kullanım ve cihazlar

VPN varsayılan kapalıdır; normal kullanım profil, abonelik veya VPN izni istemez.
Android 10+ telefon/tablet, Android TV/Google TV full APK ve Windows x64 destek
kapsamındadır. Android 7–9 normal kullanımı korunur, bu VPN backend'i açılmaz.
iOS/macOS/Linux VPN desteği bu aşamada etkin değildir.

Ayarlar > Ağ ve VPN sayfasından sistem kurulumu/izni tamamlanır ve sağlayıcının
WireGuard .conf profili içe aktarılır. TV'de dosya seçici yoksa maskeli elle profil
girişi kullanılabilir; alanlar kaydırılır. Özel anahtar/PSK günlük, komut satırı
ve genel ayarlara yazılmaz; formun açık anahtarları kalıcı UI state'e kaydedilmez.
Bu özellik ücretsiz sunucu, VPN hesabı veya antivirüs sağlamaz.

En fazla 16 KiB, tek eş ve tam IPv4 tüneli gerekir. IPv6 tam tünel isteğe bağlıdır;
fiziksel IPv6 çıkışı korumalı kullanımda izinli sayılmaz. Endpoint/DNS sayısal IP
olmalıdır. Domain endpoint, çoklu peer, split tunnel, profil komutları ve app
dahil/hariç kuralları bu önizlemede reddedilir. MTU 1280–1500 aralığı doğrulanır.
VPN sistem ağını etkiler; yerel motor/oynatıcı loopback iletişimi izinlidir.

Açma/geçişten önce aktif torrent/HTTP indirme işleri ve motorları durur.
Yeni native create/addMagnet işlemleri ortak izin kapısından geçer.
Handshake ve platform yönlendirme/koruma kanıtı birlikte gerekir.
**Bağlantıyı kes** korumayı açık tutar; **VPN'i kapat** işler gerçekten durunca
korumayı kaldırır. İşler otomatik korumasız sürdürülmez. Kullanıcının isteyerek
kestiği bağlantı kendiliğinden açılmaz. Otomatik bağlantı ayrı, varsayılan kapalıdır.

## Android uygulaması

Resmî WireGuard tunnel kütüphanesi, BIND_VPN_SERVICE korumalı ve dışa kapalı
NuvioWireGuardService aracılığıyla ayrı :nuvio_vpn sürecinde çalışır.
IPC komut/id/replyTo coroutine başlamadan kopyalanır; anahtarlar Intent
extras alanlarına yazılmaz. Profil Android Keystore/AES-GCM ve AtomicFile
ile noBackup alanında şifrelenir.

Ana süreç VPN Network/netId'sine bağlanır; ayrı VPN sürecindeki dış WireGuard
soketleri bu bağdan etkilenmez. Callback ve profile uyan UDP yerel kaynak adresi
doğrulanmadan Connected torrent izni vermez. VPN kaybında netId fiziksel varsayılana
geri çevrilmez. Oynatma ve arka plan indirme motorları ayrı takip edilir;
Workers/JobService VPN açıkken fiziksel Network socketFactory/DNS seçmez.

OS always-on/lockdown kullanıcı için isteğe bağlı ek korumadır. Sistem VPN sahibi
UID'sini muaf tutabildiğinden Nuvio'nun kendi trafiği bu ayarla kanıtlanmaz.
Sistem lockdown kontrolü VPN'in kapatılmasını engellerse uygulama bunu güvenli
hata olarak gösterir; kullanıcı sistem VPN ayarından değiştirir.
İzin iptalinde yeni işler reddedilir. TV launcher/banner ve D-pad izin/profil
testleri bulunur; tam fiziksel TV kabulü doğrulama raporunda açık bağımlılıktır.

## Windows uygulaması ve bakım

İlk kurulum yönetici onayıyla owner SID'ye bağlı SYSTEM broker kurar. İkinci
kullanıcının sahipliği değiştirmesi reddedilir; pipe istemci/servis kimliği
doğrulanır. Profil SYSTEM DPAPI ve kısıtlı ACL altında saklanır.
Resmî WireGuard 1.1.1 runtime hash/imzası sabittir.
Nuvio yardımcı ve MSI şu anda imzasız alpha'dır. Stable paketleme güvenilir
sertifika olmadan hata verir; kendinden imzalı dosya güvenilir sayılmaz.

Kalıcı WFP kuralları servis/app çökmesi sonrasında korumayı tutar. Normal
masaüstü kapanışında motor/indirmeler durdurulduktan sonra koruma kaldırılır.
MSI yükseltme/onarım profil, SID ve korumayı korur; rollback eski uygulama,
yardımcı ve durum yedeğini geri getirir. Kaldırma yalnız Nuvio VPN kaynaklarını
temizler, uygulama verilerini ve başka VPN/paylaşılan sürücüyü korur.

Çökme sonrası ağ kurtarma için önce bütün TorrServer işlemlerini kapatıp
yönetici PowerShell terminalinde şu komut kullanılabilir:

```powershell
& "$env:ProgramFiles/NuvioVpn/NuvioVpn.exe" recover
```

Yardımcı servis/profili elle kaldırma, motor kapalıyken:

```powershell
& "$env:ProgramFiles/NuvioVpn/NuvioVpn.exe" uninstall
```

Günlük PC'de interneti kesen test yapılmadı. Firewall, gerçek peer ve MSI bakım
testleri yalnız disposable GitHub Windows runner'larında çalışır.

## Test, dağıtım ve lisanslar

Son test sonuçları, CI bağlantıları ve eksikler VPN_VALIDATION_TR.md'dedir.
tools/vpn-peer kapalı, sentetik DNS/IP ve küçük yasal torrent fixture'ına hizmet
veren gerçek WireGuard netstack test aracıdır; public Internet'e çıkmaz.
Kullanıcının profili gerekmez; test anahtarları bellekte üretilir ve yazdırılmaz.
Bu aracın başarısı gerçek sağlayıcı/hız/physical pcap kabulünün yerine geçmez.

Android dört ABI APK, aynı fork sertifikası, native hash/lisans ve 64-bit 16 KB
kontrolleriyle paketlenir. Windows MSI iç runtime manifesti ve lisans/kaynak
dosyaları taşır. Bütün uygulama SBOM ve kararlı imzalı paket kabulü ayrıca gereklidir.
Android tunnel Apache-2.0, native wireguard-go MIT, Go runtime BSD-3-Clause;
lisanslar composeApp/src/androidMain/assets/vpn-licenses altında APK'ya girer.
Windows yardımcı GPL-3.0-or-later, WireGuard Windows MIT, wg.exe GPL-2.0;
gömülü resmî WireGuardNT ikilisi kendi dağıtım şartlarını korur.
Kaynak/derleme linkleri composeApp/src/desktopMain/native/vpn/WireGuard-SOURCES.txt
ile paketlenir. CI MSE test aracı anacrolix/torrent v1.61.0 MPL-2.0 kullanır;
tools/vpn-peer/ANACROLIX-MPL-2.0.txt bulunur. Bu araç APK/MSI'ya eklenmez.

Fiziksel cihaz/laboratuvar ve Windows güvenilir imza erişimi yok. Sağlayıcı,
tam sızıntı/uyku/reboot/ağ değişimi, 60 dakika video+indirme ve hız ölçümü
tamamlanmadan alpha işareti kaldırılmaz, stable entegrasyon/public Release yapılmaz.
