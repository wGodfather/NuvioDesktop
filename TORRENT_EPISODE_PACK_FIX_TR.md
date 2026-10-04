# Torrent sezon paketinde doğru bölüm seçimi

4 Ekim 2026. Test edilen uygulama kaynak commit'i:
`2d408e99f80318e5c726d5c0f140b3b41ab2f3fd`.

## Sorun ve düzeltme

Suits S02E08 ve S02E09 için aynı sezon torrentinin listelenmesi normaldir.
Hata, seçilen bölüm kimliği torrent motoruna taşınmadığı için aynı dosyanın
seçilmesiydi. Masaüstünde yanlış veya eksik eklenti dosya bilgisi en büyük
videoya düşebiliyordu; Android'de native motor bölüm bağlamını almıyordu.
Oynatıcı da aynı hash/dosya bilgisiyle bölüm değiştiğinde önceki akışı
yeniden kullanabiliyordu.

Oynatma ve indirme artık sezon/bölüm bilgisini ortak dosya seçicisine taşır.
Seçici torrent içindeki gerçek dosya listesini değerlendirir ve dosyanın
orijinal indeksini kullanır. Bölüm adı, eski veya yanlış eklenti indeksinden
önceliklidir. `S02E08`, `2x08` ve sezon klasörü içindeki numaralı bölüm
adları desteklenir. Örnek videolar/trailer dosyaları ayıklanır; dizi adındaki
"Trailer" gibi sözcükler bölüm dosyasının elenmesine yol açmaz.

İstenen bölüm güvenilir biçimde belirlenemiyorsa başka bölümün en büyük
dosyası açılmaz; açıklayıcı hata gösterilir. Birden fazla bölümün tek video
dosyasına birleştirildiği kaynaklar, bölüm başlangıç bilgisi olmadan otomatik
seçilmez. Bir bölümün birden fazla sürümü varsa açık dosya kimliği gerekir.
Etiketsiz tek video ve açık eklenti dosya bilgisi desteği korunur; bu durumda
bölümün içerikten doğrulandığı iddia edilmez.

Masaüstü kaynak doğrulaması aynı seçiciyi kullanır; mevcut başlık/içerik
doğrulaması korunur. Oynatıcı bölüm değişince akışı yeniden çözer ve eski
bölümün geç gelen sonucunu uygulamaz. İndirmelerin yanına dosya kimliği
kaydedilir: kimliği eksik veya farklı yarım torrent dosyası baştan başlar;
aynı dosyaya ait yarım indirme devam eder. Tamamlanmış videolar değiştirilmez.
**Önceden yanlış tamamlanan bölüm yeniden indirilmelidir.**

## Doğrulama

Testler dışarıdan Suits videosu indirmez. Yerel olarak üretilen küçük MP4
videolar, bölüm adları verilmiş bir torrent paketine eklenir. Pakette README
indeks 0, E08 indeks 1, E09 indeks 2'dir. Her iki isteğe de bilerek eski
E08 adı/indeksi verilerek bölüm kimliğinin bunu düzelttiği sınanır.
İki dosyanın SHA-256 değerleri farklıdır. Akışın ve tamamlanan indirmelerin
baytları beklenen dosya hash'leriyle karşılaştırılır.

| Dosya | Beklenen SHA-256 |
| --- | --- |
| E08 | `ccee0635d5a9b1b116f9c7156cec7d3de6a618a604dac4a91a688e34ba19fc4c` |
| E09 | `08ebebd4c055d89e8d2fb137264c9755b40e6760b3d7e1819e45b23d8975b03d` |

- [Windows CI](https://github.com/wGodfather/NuvioDesktop/actions/runs/37213117088):
  başarılı; 100 test, 0 hata, 0 başarısızlık, 0 atlanan test. Paketlenmiş
  TorrServer ile E08/E09 oynatma akışı ve eşzamanlı indirme testi geçti.
  MSI oluşturma, yayımlanmış 0.1.29'dan yükseltme, onarım, hata enjekte
  edilmiş rollback ve kaldırma kontrolleri geçti.
- [Android CI](https://github.com/wGodfather/NuvioDesktop/actions/runs/37213118986):
  başarılı; son kaynak üzerinde 110 host testi ve API 36 x86_64 emülatöründe
  4 cihaz testi geçti. Hata, başarısızlık veya atlanan test yok. Native motorla
  E08/E09 akış/indirme testi, mevcut arama ve Kitaplık/indirme kontrolleri,
  imzalı dört ABI APK ve yayımlanmış 0.1.29'dan yükseltme kontrolü geçti.
  Son raporlar `build/torrent-android-qa-2d` altında korunur.
- Yerel Android API 36 x86_64 emülatöründe native motor ile aynı paket testi
  geçti (1 instrumentation testi, 6,726 saniye). Bu yerel APK, son başlık
  filtresi düzenlemesinden önceki kaynakla derlenmiştir; son kaynak için
  yukarıdaki CI sonucu esas alınacaktır. Yerel emülatör ve fixture sunucusu
  testten sonra kapatıldı.
- Son ortak seçici ve yarım indirme kimliği için bağımsız Kotlin/JUnit
  çalıştırmasında 12 test geçti. Android host testleri ve yeni instrumentation
  sınıfının yerel derlemesi de geçti; nihai toplamlar CI raporunda tutulur.

Bu kanıtlar dosya seçimini, native torrent aktarımını ve indirme sonucunu
doğrular. Tam video decoder testi, fiziksel telefon/TV testi veya gerçek
sağlayıcının bütün sezon paketleri için doğrulama değildir.

## Paketler ve yayın durumu

Bu düzeltmenin paket sürümü mevcut deneysel `0.1.30-alpha` olarak kaldı:
Android kodu 31, Windows MSI sürümü 1.1.31. Eski alpha 31 / MSI 1.1.31 üzerine
otomatik yükseltme sağlandığı iddia edilmez. Stable hedefin artırılmış iç
sürümü ve kabul kapıları önceki VPN planında tanımlıdır.

Windows test paketi ve raporları `build/torrent-packages-2d/windows` ve
`build/torrent-windows-qa-2d` altında korunur. Windows MSI SHA-256:
`2ee53f0d44a85549bf6b8ff4203692378e3a2a646497acab5dad9ec968c60858`.
İndirilen MSI'nın hash'i CI yan dosyasıyla yerelde eşleşti.

Son kaynakla üretilen imzalı Android APK'ları (arm64-v8a, armeabi-v7a, x86,
x86_64), hash ve imza raporları `build/torrent-packages-2d/android` altında
korunur. Beş paketin yerel hash doğrulama özeti
`build/torrent-packages-2d/verified-sha256.json` dosyasındadır; hepsi CI hash
dosyalarıyla eşleşti. Android APK imza/zipalign/native paket kontrolleri CI'da
geçti. APK'lar kullanıcının bilgisayarına veya fiziksel cihazına kurulmadı.

Windows MSI/yardımcı hâlâ güvenilir kod imzasına sahip değildir. Önceki VPN
çalışmasının fiziksel cihaz, sağlayıcı, uzun süreli yaşam döngüsü ve güvenilir
Windows imzası kabul kapıları bu bölüm düzeltmesiyle tamamlanmış sayılmaz.
`VPN_VALIDATION_TR.md` kendi tarihindeki kaynak için geçerlidir.

Bu çalışma mevcut izole worktree'de yapıldı. Ana `turkiye` dalı, kullanıcının
kurulu uygulaması ve önceki taslak Release değiştirilmedi. Bu rapor public
stable yayın veya otomatik uygulama güncellemesi anlamına gelmez.

Kaynak ve bu rapor fork'un
[`codex/torrent-pack-episode-selection`](https://github.com/wGodfather/NuvioDesktop/tree/codex/torrent-pack-episode-selection)
dalında tutulur. Dal mevcut VPN geliştirme kaynağı `7420b2cb` üzerine kuruludur;
VPN değişikliklerini geri almaz. Uygulama/paket kaynak commit'i yukarıda
belirtilen `2d408e99`'dur; sonraki rapor commit'i uygulama dosyalarını değiştirmez.
