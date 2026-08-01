# IoniqScope — ekran ve menü envanteri

Hyundai Ioniq 6 için bir Android uygulaması: Türkiye şarj istasyonu haritası + OBD-II
gösterge paneli. Arayüz tamamen Türkçe. Koyu tema varsayılan.

Bu dosya, tasarım üzerinde çalışırken (Claude Design vb.) elde bulunması için
uygulamanın *bugünkü* yapısını yazar. Kaynak koddan çıkarıldı, tahmin yok.

---

## Kalıcı çerçeve

Her ekranda görünen iki şey var.

**Üst çubuk** — solda ekranın adı. Sağda iki simge:
- **Bluetooth** + üstünde durum noktası: yeşil bağlı, amber bağlanıyor (yanıp söner),
  kırmızı başarısız, gri boşta. Basınca → *Bağlan* ekranı.
- **Dişli** → *Ayarlar*.

Detay ekranlarında (Bağlan, Ayarlar, Konsol, 12V, Sefer detayı) solda geri oku çıkar.

**Alt gezinme (3 sekme, 60dp + sistem çubuğu payı)**
1. **Şarj** — harita (açılış ekranı)
2. **Seferler**
3. **OBD**

---

## 1. Şarj

Uygulamanın ana ekranı. İki görünümü var: harita ve liste.

### 1a. Harita görünümü

Tam ekran harita (CARTO Positron altlığı — beyaza yakın zemin, açık gri yollar).

**Üzerinde duran katmanlar:**

- **Sol üst — rota öneri paneli.** En yakın 5 istasyona rota. Her satır:
  - renk noktası (o rotanın rengi)
  - marka adı — *Shell Recharge*
  - alt satır: `180 kW · Etiler`
  - sağda: `450 m · 0 dk` ve altında fiyat `~14-16 ₺`
  - satıra basınca harici harita uygulamasında yol tarifi açılır
- **Uyarı bantları** (duruma göre, panelin üstünde): konum izni yok / konum kapalı /
  konum alınamadı / *"Yalnızca yaklaşık konum izni verilmiş"* + **"Kesin konumu aç"**
  düğmesi / istasyon listesi boş
- **Arama alanı** (arama açıkken üstte): isim, marka, adres. Sonuca basınca haritada
  o noktaya gider.

**İstasyon işaretleri:**
- Nokta = bir istasyon. Rengi markası, boyutu DC ise biraz daha büyük.
- Altında etiket: `Trugo 120 kW`
- Yakın olanlar tek noktada birleşir, altında sayı (`2`, `3`)
- Rota hedeflerinde **damla biçimli pin**, rotanın renginde, ucu tam istasyonda
- Mavi nokta = kullanıcı konumu
- Rota çizgileri ince, üzerlerinde yön okları

**Sağ alt — dikey düğme yığını (5 düğme):**
1. **Ara** (büyüteç)
2. **Konumum** (hedef simgesi) — açıkken dolu; harita seni takip eder
3. **AC/DC** (şimşek) — açıkken sadece DC gösterir, **varsayılan açık**
4. **Şarj ağı** (huni) — marka filtresi, seçim varsa dolu
5. **Liste** (satırlar) — liste görünümüne geçer

**Sol alt** — harita telif metni (`© OpenStreetMap contributors © CARTO`)

**İstasyona basınca — alt kart:**
- İstasyon adı
- `8 şarj noktası kayıtlı`
- `Shell Recharge · 180 kW · DC` + soket tipleri + adres
- `~13,50-15,99 ₺/kWh · değişken · Shell Recharge tarifesi, 25.07.2026`
- *"İşletmecinin yayımladığı fiyat. Üyelik, kampanya ve lokasyona göre değişebilir."*
- **Yol tarifi** düğmesi, kapatma

### 1b. Liste görünümü

Başlık satırı: **harita düğmesi** · `271 yer` · **"En yakın / En ucuz"** sıralama
düğmesi · **huni** (marka filtresi) · **şimşek** (AC/DC)

Kart listesi, her kart:
- İstasyon adı
- `EPSIS · 30 kW · DC` + soket tipleri + adres
- `4.0 km` · `2 şarj noktası` · `~10,50 ₺/kWh · ucuz`
  (ucuz yeşil, pahalı kırmızı, ortalama/değişken gri)

### 1c. Şarj ağı seçimi (diyalog)

- Başlık **Şarj ağı**, altında `Hepsi gösteriliyor.` ya da `3 ağ seçili — harita ve
  rota önerileri yalnızca bunlara bakıyor.`
- Liste: renk noktası · marka adı · istasyon sayısı · onay kutusu
- **En çok istasyonu olandan aza sıralı**: ZES 1518, Trugo 1192, Voltrun 1051,
  WAT Mobilite 887, Eşarj 720, Otopriz 480… (toplam 613 marka)
- Altta **Hepsi** (temizle) ve **Uygula**

---

## 2. Seferler

- Bağlı değilse uyarı bandı: *"Sefer kaydına başlamadan önce adaptöre bağlan."*
- **Kaydı başlat / Kaydı durdur**
- **CSV olarak dışa aktar**
- **Kayıtlı seferler** listesi — boşsa *"Henüz kayıtlı sefer yok."*
- Sefer satırına basınca → **Sefer detayı**

### Sefer detayı
- Süre, mesafe, ortalama hız, örnek sayısı
- Hız grafiği (yoksa *"Bu sefer için hız verisi kaydedilmemiş."*)
- **Bu seferdeki 12V** — voltaj aralığı
- **Seferi sil**

---

## 3. OBD

Üstte üç sekmeli satır. Üçü de adaptör bağlı değilken uyarı bandı + **Bağlan**
gösterir. Seçilen sekme hatırlanır.

### 3a. Gösterge
Canlı veri. Büyük hız göstergesi + kart halinde ölçüler.
Gösterilecek ölçüler Ayarlar'dan seçilir. Mevcutlar: **Hız, Devir, Dış sıcaklık,
Modül voltajı (12V), Soğutma sıvısı, Şarj (SoC)**.

### 3b. Performans
- Uyarı: **Yalnızca pist** — *"Yalnızca kapalı yol veya pistte kullan… ölçümü başlat,
  sonra gözünü yola çevir."*
- Kronometre: `READY / RUNNING / DONE`, geçen saniye, anlık hız, maksimum hız, mesafe
- `0-100 km/h` sonucu, en iyi derece
- **Kronometreyi sıfırla** — *"Kalkış kendiliğinden algılanır — dur ve bas, yeter."*
- **Ölçüm geçmişi** listesi, tek tek silinebilir

### 3c. Arıza
- **12V akü** kartı — son voltaj, **Eğilim →** ile 12V ekranına
- **Kodları oku** / **Kodları sil**
- **Muayene kontrolü** — hazırlık monitörleri: Tekleme, Yakıt sistemi, Bileşenler,
  Katalizör, Isıtmalı katalizör, Buharlaşma sistemi, İkincil hava sistemi,
  Klima soğutucusu, Oksijen sensörü, Oksijen sensörü ısıtıcısı, EGR sistemi
  (`Hazır görünüyor` / `Hazır değil`)
- **Komut konsolu** düğmesi
- **Kayıtlı kodlar** listesi — boşsa *"Araç, kayıtlı hiçbir arıza kodu bildirmedi."*

---

## Detay ekranları (geri oklu)

### Bağlan
- Durum kartı: Bağlı değil / Bağlanıyor / Bağlı / Başarısız
- **Adaptör tara** / **Taramayı durdur** — `Taranıyor…`
- **Eşleşmiş adaptörleri listele**
- **Bulunan adaptörler** — cihaz adı + `· OBD adaptörüne benziyor` ipucu
- Hata halleri: Bluetooth kapalı / donanım yok / izin yok → **Uygulama ayarlarını aç**

### 12V akü
- Durum rozeti: **SAĞLIKLI / DÜŞÜK / KRİTİK / VERİ YOK**
- Son voltaj, açıklama (*"Dinlenmede 12,0 V altında"*, *"Seviye iyi ama düşüyor"*)
- **Oturum başı eğilimi**: `12 oturumda haftada -0,015 V`
- Zaman grafiği (en az iki oturum gerekir)
- **12V geçmişini sil**

### Komut konsolu
- Komut girişi (`örn. 220101`) + **Send**
- Hazır komutlar: `0100`, `0902`, `220101`, `220105`, `ATSH 7DF`, `ATSH 7E4`
- Yanıt kaydı, **Kaydı kopyala** / **Yanıtı kopyala** / **Clear**
- **Yayına dön (normal sorgulamayı geri getirir)**

### Ayarlar
Bölüm bölüm:
1. **Güncellemeler** — yüklü sürüm, güncelleme kartı + **İndir**, `latest.json` adresi,
   **Adresi kaydet** / **Şimdi kontrol et**, *Açılışta kontrol et* anahtarı
2. **Birimler** — km/h veya mph
3. **Adaptör** — *Açılışta bağlan*
4. **Otomasyon** — *Seferleri otomatik kaydet*
5. **Şarj istasyonları** — *Sadece DC*, minimum güç, kaynak senkronizasyonu,
   TomTom API anahtarı
6. **Gösterge PID'leri** — göstergede hangi ölçüler görünsün
7. **Sorgu aralığı** — OBD sorgu sıklığı
8. **Ioniq 6 batarya verisi** — *"Bilerek eklenmedi"* açıklaması (SoC/SOH üreticiye
   özel UDS istekleri gerektiriyor)
9. **Gizlilik** — hiçbir araç verisi cihazdan çıkmıyor

---

## Renkler (logodan)

| rol | renk |
|---|---|
| Birincil (turkuaz) | `#22C1D6` — canlı veri, birincil eylem, seçili düğme |
| İkincil (yeşil) | `#6DBE4B` — seçili sekme, ucuz fiyat, olumlu durum |
| Uyarı (amber) | `#FFB74D` |
| Hata (kırmızı) | `#FF6B6B` — pahalı fiyat, arıza |
| Zeminler | `#0A161D` · `#10222C` · `#1A303C` |
| Metin | `#E6F2F5`, soluk `#93AAB4` |
| Kenarlık | `#35525E` |

Marka renkleri ayrı bir palet (en çok istasyonu olan 6 marka birbirinden en uzak
tonlarda: ZES yeşil, Trugo lacivert, Voltrun turuncu, WAT camgöbeği, Eşarj kırmızı,
Otopriz mor).

---

## Bilinçli olarak *olmayan* şeyler

Tasarım yaparken bunları eklememek gerekiyor:

- **Hesap, giriş, kayıt yok.** Uygulama hiçbir yere veri göndermiyor.
- **Reklam, analitik, çökme raporu yok.**
- **Şarj başlatma / ödeme yok.** Uygulama istasyonu gösterir, şarjı başlatmaz.
- **Anlık doluluk yok.** Bir istasyonun şu an boş mu dolu mu olduğu bilinmiyor.
- **İstasyon bazlı fiyat yok.** Fiyatlar marka bazlı ve "işletmecinin yayımladığı
  fiyat" olarak etiketli.
