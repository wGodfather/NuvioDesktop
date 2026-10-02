# Nuvio Desktop Türkiye

Bu fork, NuvioMedia/NuvioDesktop **0.1.26-alpha** sürümünün üzerine hazırlanır.
Uygulama düzeltmeleri ve sürümler `turkiye` dalında tutulur. `Dev` dalı orijinal
projenin geliştirme hattını izlemek için korunur. Eklenti/kaynak deposu
`dr-octagon/Nuvio` ayrı kalır.

## Güncelleme hazırlama

1. `turkiye` dalında uygulama değişikliklerini ve testleri tamamlayın.
2. `RELEASE_NOTES_TR.md` dosyasını yenileyin.
3. Son uygulama değişikliği olarak `composeApp/Configuration/DesktopVersion.properties`
   dosyasındaki `VERSION_NAME` ve `VERSION_CODE` değerlerini birlikte artırın.
4. GitHub Actions içindeki **Nuvio Türkiye Windows** işini `turkiye` dalında çalıştırın.
   `create_draft=false` yalnızca test ve kurulum çıktısını üretir.
   `create_draft=true` kurulum dosyasını bir taslak GitHub sürümüne ekler.
5. MSI ve sağlama dosyasını kontrol ettikten sonra Releases sayfasından taslağı yayımlayın.
   Taslak sürümler uygulamanın güncelleme kontrolünde görünmez.

Uygulama kendi fork'undaki `.msi` sürümlerini kontrol eder. Daha yüksek sürüm
yayımlandığında uygulama içinden güncelleme yapılabilir. Kaynak kodunu göndermek
tek başına kurulu uygulamayı güncellemez; kurulum dosyası yayımlanmalıdır.

## Derleme

Windows: JDK 17, MSVC C++ araçları, WebView2 SDK ve Git LFS gereklidir.

```powershell
git clone --branch turkiye https://github.com/wGodfather/NuvioDesktop.git
cd NuvioDesktop
git lfs pull
New-Item -ItemType File -Path local.properties -Force
.\gradlew.bat :composeApp:packageMsi "-Pnuvio.webview2.dir=C:/path/to/Microsoft.Web.WebView2"
```

`local.properties` Git'e gönderilmez. Hesap senkronizasyonu / Trakt hizmetlerini
kullanmak için bu dosyada kendi yapılandırmanızı kullanın. CI'da isteğe bağlı
`NUVIO_DESKTOP_LOCAL_PROPERTIES_BASE64` secret'ı aynı dosyanın base64 içeriğidir.
Secret verilmezse yerel/misafir kullanım için derleme yapılır; NuvioMedia'ya ait
özel servis veya imzalama anahtarları gerekmez. API anahtarlarını, oturumları,
kişisel ayarları ve indirilen medyayı depoya eklemeyin.

İlk sürüm Windows üzerinde test edilmiştir. Yeni sıkı video doğrulaması Windows
için etkinleştirilir; diğer platformların mevcut kaynak davranışı korunur.
Eklenti arama protokolü `docs/tmdb-catalogs.md` dosyasında açıklanmıştır.

## Bileşenler ve lisans

Asıl proje ve bu fork GPL-3.0 lisansını korur; `LICENSE` dosyasına bakın.
Windows video denetleyicisi Git LFS üzerinden taşınan FFmpeg/ffprobe 8.1.1
Gyan full build'dir. Derleme ve lisans bildirimi
`composeApp/src/desktopMain/resources/verification/windows/FFmpeg-LICENSE.txt`
dosyasındadır. Kaynak ve derleme bilgileri:
https://www.gyan.dev/ffmpeg/builds/ ve https://github.com/FFmpeg/FFmpeg.
Paket lisans metinlerini ve sağlama değerini içerir.
