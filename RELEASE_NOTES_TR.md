# Nuvio Türkiye 0.1.27-alpha

- Film/dizi araması ve Türkçe tür filtreleri düzeltildi; kataloglara sayfalama eklendi.
- GitHub üzerindeki Nuvio Türkiye eklentisinin statik arama adreslerinden kaynaklanan HTTP 404 giderildi.
- Torrent ve HLS indirmeleri, kaynak yenileme ve kaldığı yerden devam etme iyileştirildi.
- Torrent akışındaki kodlanmış magnet başlığından kaynaklanan HTTP 500 düzeltildi.
- Windows kaynakları gerçek dosya boyutu, video çözünürlüğü ve içerik eşleşmesiyle doğrulanıyor.
- Doğrulanan kaynaklar boyuta göre büyükten küçüğe; eşitlikte çözünürlük ve kaliteye göre sıralanıyor.
- Güncelleme kontrolü wGodfather/NuvioDesktop sürümlerini kullanıyor.

Bu ilk fork sürümü Windows için hazırlanmıştır. 51 hedefli test geçti;
film/dizi araması, beş katalog, tür filtresi, devam sayfası ve bölüm bilgileri
canlı servis üzerinde ayrıca doğrulandı.

Hesap senkronizasyonu ve Trakt entegrasyonu için hizmet yapılandırması gerekir.
Katalog, yerel ayarlar, kaynak eklentileri ve indirmeler yerel/misafir kullanımında çalışır.
Önceki kullanıcı ayarları ve indirmeler aynı Nuvio veri klasöründe tutulur.
Kurulumu devam eden indirmeler tamamlandıktan sonra yapın.
