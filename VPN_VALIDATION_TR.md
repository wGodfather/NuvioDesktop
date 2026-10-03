# 0.1.30 çok platformlu VPN doğrulaması

3 Ekim 2026 07:15 Europe/Istanbul başlangıcı. Çalışma izole VPN worktree'sindedir.
Bu belge sürüm kabul raporudur; **kararlı yayın kabulü henüz verilmemiştir**.

## Sürüm ve kapsam

- Android telefon/tablet, Android TV/Google TV; Windows x64. iOS/macOS/Linux için
  bu aşamada VPN desteği açılmaz.
- İnceleme build'i: `0.1.30-alpha`, Android/uygulama kodu `31`, Windows MSI iç
  sürümü `1.1.31`. Önceki deneysel paket `30 / 1.1.30` idi. Kararlı `0.1.30`
  çıkarılırken kod `32`, MSI `1.1.32` olmalı; görünen uygulama sürümü `0.1.30` kalır.
- VPN varsayılan kapalıdır. Sağlayıcı profili gerekir; virüs taraması yapmaz.
- Android'in normal kullanım alt sınırı değişmedi. Korumalı VPN için Android 10+
  gerekir. Sistem always-on/lockdown ayarı diğer uygulamalar için isteğe bağlı
  ek korumadır; Nuvio'nun kendi trafiği ayrı süreç ve açık Network bağıyla korunur.

## Uygulanan koruma

Resmî `com.wireguard.android:tunnel:1.0.20260102` ayrı `:nuvio_vpn` sürecinde
çalışır. Android lockdown VPN uygulamasının UID'sini muaf tutar; tek başına
Nuvio'nun kendi torrent soketlerini koruduğuna dair kanıt değildir. Ana süreç
VPN Network/netId'sine açıkça bağlanır. VPN ağı kaybolunca bu bağ kaldırılmaz;
fiziksel ağa otomatik dönüş yapılmaz. WireGuard'ın dış soketleri farklı süreçte
olduğu için bu bağdan etkilenmez. Mevcut oynatma/indirme JNI oturumları ve HTTP
işleri ağ politikası değişmeden önce kapatılır; her create/addMagnet ayrı kapıdan
geçer. Worker/JobService'nin fiziksel socketFactory/DNS seçimi VPN açıkken kullanılmaz.

Profil AES-GCM + Android Keystore ile noBackup alanındadır. Dosya boyutu,
tek peer, tam tünel, sayısal endpoint/DNS, alan tekrarları ve sıfır anahtarlar
kontrol edilir. TV'de özel anahtar alanları maskelenmiş elle profil girişi vardır.
WireGuard, Go ve Go çalışma zamanı lisansları APK'ya dahil edilir.

Windows MSI'ya deferred SYSTEM hazırlama/yenileme/kaldırma, rollback ve commit
eylemleri eklenmiştir. Yeni kurulum VPN'i kendiliğinden açmaz. Bakım sırasında
önce torrentlerin durduğu doğrulanır, etkin ağ koruması tutulur; şifreli profil ve
SID korunur. Rollback yedeği standart kullanıcıya okunabilir ACL verilmeden
saklanır. Paylaşılan WireGuard sürücüsü ve diğer VPN servisleri kaldırılmaz.

## Mevcut kanıt

| Kontrol | Sonuç | Sınır |
|---|---|---|
| Android kaynak derlemesi | Geçti | Cihaz/sızıntı kanıtı değil |
| Android host regresyonları | 52 test geçti | JNI ağı gerçek cihazda kullanılmadı |
| Windows native helper | 23 test geçti | Yerel PC'de servis/firewall kurulmadı |
| MSI action/Binary ekleme ve paket runtime hash'leri | Geçti | Kurulum/rollback henüz CI'de çalıştırılmadı |
| Telefon/Android TV/Google TV emülatörleri | İlk tur başarısız | IPC Message geri dönüşüm yarışı düzeltildi; tekrar bekleniyor |
| Kontrollü peer, DNS/IPv6 ve fiziksel ağ kaçışı | Bekliyor | Host testleri bu kapıyı kapatmaz |
| 60 dakika/10 ağ değişimi, hız ölçümü | Bekliyor | Ölçüm yapılmadan hız iddiası yok |
| Windows gerçek MSI lifecycle | Bekliyor | Önceki 1.1.30 paketinden yükseltme CI testi |
| Windows güvenilir imza | Erişim yok | Sertifika/imzalama hizmeti soruldu; uydurma imza yok |
| Fiziksel telefon/TV ve Windows cihazları | Erişim yok | Kullanıcı cihaz olmadığını bildirdi |

İlk yerel Robolectric çalıştırmasında 9 test Windows'un Türkçe sistem dilindeki
Conscrypt `wındows` kitaplık adı hatası yüzünden çalışmadı. JVM test dili en/US
olarak sabitlendiğinde 52 test geçti; test kabul şartları gevşetilmedi.

Kaynak `23fcd0e1` ile Android CI `37099393638` dört emülatörde çalıştı. Servis
Handler'ının coroutine içinde geri dönüştürülmüş Message nesnesini okuması
replyTo değerini kaybettiriyor ve servisi çökertiyordu. Komut/id/replyTo artık
Handler dönerken kopyalanıyor. SERVICE_LOST durumunda torrent kapısı açılmadı.
Windows CI `37099393921` 86 testten mevcut eşzamanlı scraper testinde zaman
aşımına uğradı; aynı kaynakla bir tekrar başlatıldı. Native izolasyon CI
`37099395664` geçti. Başarısız testler geçti diye raporlanmaz.

## Yayın kapısı

CI için geliştirme dalına kaynak aktarılabilir. Testi bitmemiş bu kaynak `turkiye`
dalına kararlı sürüm olarak birleştirilmez. Bütün cihaz/sızıntı/yaşam döngüsü,
imza ve yükseltme kapıları geçmeden public stable Release yayımlanmaz. Eksik
bağımlılıklar varsa kaynak, deneysel paketler ve bu somut rapor korunur.

## Birincil kaynaklar

- https://developer.android.com/develop/connectivity/vpn
- https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network)
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/connectivity/Vpn.java
- https://git.zx2c4.com/wireguard-android/
- https://learn.microsoft.com/en-us/windows/win32/msi/rollback-custom-actions
