# Nuvio Türkiye — Windows ve Android

Bu fork NuvioMedia/NuvioDesktop 0.1.26-alpha temelinden hazırlanır. Uygulama
ve sürümler `turkiye` dalındadır. `Dev` dalı upstream geliştirme hattını izlemek
üzere korunur. `dr-octagon/Nuvio` kaynak/eklenti deposu ayrı kalır.

## Güncelleme hazırlama

1. `turkiye` dalında uygulama değişikliklerini ve testleri tamamlayın.
2. `RELEASE_NOTES_TR.md` dosyasını yenileyin.
3. Son uygulama değişikliği olarak `composeApp/Configuration/DesktopVersion.properties`
   içindeki `VERSION_NAME` ve `VERSION_CODE` değerlerini birlikte artırın.
4. GitHub Actions içindeki **Nuvio Türkiye Windows** veya **Nuvio Türkiye Android**
   işini `turkiye` dalında çalıştırın. `create_draft=false` test ve paket çıktısı üretir;
   `create_draft=true` paketleri taslak sürüme ekler.
5. MSI/APK ve sağlama dosyalarını kontrol edin. Yayımlama ayrı bir kullanıcı onayı
   gerektirir; bu çalışma yalnızca taslak hazırlamıştır.

Windows ve Android kendi fork'undaki `.msi` / cihaz mimarisine uygun `.apk` sürümlerini
kontrol eder. Taslaklar güncelleme kontrolünde görünmez. Kaynak kodunu göndermek tek
başına kurulu uygulamayı güncellemez; kurulum paketi yayımlanmalıdır.

## Windows derleme

JDK 17, MSVC C++ araçları, WebView2 SDK ve Git LFS gerekir.

```powershell
git clone --branch turkiye https://github.com/wGodfather/NuvioDesktop.git
cd NuvioDesktop
git lfs pull
New-Item -ItemType File -Path local.properties -Force
.\gradlew.bat :composeApp:packageMsi "-Pnuvio.webview2.dir=C:/path/to/Microsoft.Web.WebView2"
```

`local.properties` Git'e gönderilmez. Hesap senkronizasyonu ve Trakt için kendi
hizmet yapılandırmanızı kullanın. Windows CI için isteğe bağlı secret:
`NUVIO_DESKTOP_LOCAL_PROPERTIES_BASE64`. Secret verilmezse yerel/misafir kullanım
paketi hazırlanır; resmî projenin özel hizmet anahtarları kullanılmaz.

## Android derleme ve imza

JDK 17, Android SDK platform 37 ve build-tools 36.0.0 gerekir.

```powershell
.\gradlew.bat :androidApp:assembleFullRelease "-PreleaseMinifyEnabled=false" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=1
```

İmza bilgilerini Git'e gönderilmeyen `local.properties` içinde tutun:
`NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_STORE_PASSWORD`,
`NUVIO_RELEASE_KEY_ALIAS`, `NUVIO_RELEASE_KEY_PASSWORD`.

CI aynı fork imzasını GitHub Secrets üzerinden alır:
`NUVIO_FORK_ANDROID_KEYSTORE_BASE64`, `NUVIO_FORK_ANDROID_STORE_PASSWORD`,
`NUVIO_FORK_ANDROID_KEY_ALIAS`, `NUVIO_FORK_ANDROID_KEY_PASSWORD`.
Anahtarın güvenli yedeğini saklayın; sonraki güncellemeler aynı imzayı gerektirir.
Kaynak küçültme/obfuscation bu paketlerde kapalıdır.

Android full dağıtımı `com.wgodfather.nuvio` kimliğini ve fork'a ait imzayı kullanır;
resmî Nuvio ile yan yana kurulabilir. Debug kimliği `com.wgodfather.nuvio.debug` olur.
Android ve Windows aynı sürüm numarasını kullanır. APK dosyaları arm64-v8a,
armeabi-v7a, x86 ve x86_64 için üretilir. Android 7+ hedeflenir; fiziksel ARM telefon
ve iOS paketi bu çalışmada test edilmedi.

0.1.29-alpha ile Windows ve Android Kitaplığında Kaydedildi/Bulut yanında
İndirilenler sekmesi ve kaynak kartlarının sağında indirme butonu bulunur.
Torrentler en az 5 bildirilen seed, 1080p sınıfı çözünürlük ve seçilen dosyada
1 GiB şartıyla listelenir; bilinmeyen değerler elenir. Diğer kaynaklarda yalnızca
çözünürlük sınırı uygulanır. Hazır doğrudan kaynaklar torrentleri beklemez.
Android çözünürlük/boyut bilgisini eklentiden alır; gerçek medya doğrulaması
Windows'ta kalır. İki platform aynı sıralama ve buton davranışı testlerini kullanır.

Android torrent indirmesi ayrı Nuvio Engine oturumu kullanır. Doğru dosya seçimi,
HTTP range ile devam etme ve iptal sırasında oturumun kapanması test edilir.
Yerel motor adresi Wi-Fi/mobil ağ soketine zorla bağlanmaz ve eklenti kimlik
başlıkları yerel motora gönderilmez. Oynatıcı ile indirme birbirini durdurmaz.
Android CI işi Android 16 sanal cihazında Suits aramasını, tür filtresini,
sayfalamayı, kendi küçük test videosunun gerçek torrent indirmesini ve Kitaplıktaki
İndirilenler sekmesini kontrol eder. Kitaplık ekran görüntüsü QA çıktısına eklenir.
Opt-in cihaz testleri `androidApp/src/androidTest/.../ForkAndroidIntegrationTest.kt`;
yerel test paylaşımı `tools/qa_torrent_seed.py` dosyasındadır.

Android'de HLS çevrimdışı indirme henüz desteklenmez. Oynatma listesi yanlışlıkla
tamamlanmış video olarak kaydedilmek yerine açıklayıcı hata verir. Torrent ve
doğrudan video indirmeleri desteklenir. Sıkı dosya/video/içerik doğrulaması Windows
için etkinleştirilir; Android'in kaynak doğrulama davranışı korunur.
Eklenti arama protokolü `docs/tmdb-catalogs.md` içinde açıklanır.

## Bileşenler ve lisans

Asıl proje ve fork GPL-3.0 lisansını korur; `LICENSE` dosyasına bakın. Windows video
denetleyicisi Git LFS ile taşınan FFmpeg/ffprobe 8.1.1 Gyan full build'dir. Lisans:
`composeApp/src/desktopMain/resources/verification/windows/FFmpeg-LICENSE.txt`.
Kaynak/derleme bilgileri: https://www.gyan.dev/ffmpeg/builds/ ve
https://github.com/FFmpeg/FFmpeg. Paket lisansları ve sağlama değerini içerir.
API anahtarlarını, kişisel ayarları, oturumları ve indirilen medyayı depoya eklemeyin.

Geliştiricilere iletilen raporlar:
- Torrent: https://github.com/NuvioMedia/NuvioDesktop/issues/815
- Arama: https://github.com/NuvioMedia/NuvioDesktop/issues/816
