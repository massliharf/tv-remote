# LG TV Kumanda (Android)

LG **42LB652V** (2014, webOS 1.0) ve diğer LG webOS TV'ler ile **Xiaomi Mi Box / Mi TV Stick**
gibi Android TV / Google TV cihazları için Android kumanda uygulaması. Birden fazla cihaz
kaydedilebilir; üstteki çubuktan tek dokunuşla aralarında geçilir.

## Kurulum

1. GitHub'da **Actions → Build APK** sayfasından son çalışmayı açın.
2. En alttaki **LG-TV-Kumanda-apk** dosyasını indirin, zip'ten çıkan `.apk`'yı telefona kurun
   (bilinmeyen kaynaklardan yüklemeye izin vermeniz gerekir).

## İlk bağlantı

1. Telefon ve TV aynı Wi-Fi ağında olmalı.
2. TV'de **Ayarlar → Ağ → LG Connect Apps** açık olmalı (varsayılan olarak açıktır).
3. Uygulamada sağ üstteki **Bağlan**'a basın; TV otomatik bulunur (bulunamazsa IP adresini elle girin).
4. TV ekranında eşleştirme isteği çıkar. Kumanda olmadığı için **TV'nin alt-orta kısmındaki
   joystick tuşu** ile *Evet*'i seçip basın. Bu yalnızca bir kez gerekir.

## Xiaomi TV Box (Android TV) ekleme

1. Uygulamada üstteki cihaz etiketine dokunun → **Yeni cihaz ekle**. Kutu listede "Android TV" olarak çıkar
   (çıkmazsa IP adresini yazıp **Android TV**'ye basın).
2. Kutunun ekranında 6 haneli bir kod çıkar; kodu uygulamaya yazıp **Eşleştir**'e basın. Bu yalnızca bir kez gerekir.

## Özellikler

- Güç (kapatma), ses, sessiz, kanal, yön tuşları + OK, Geri, Ana Sayfa, Çıkış, Ayarlar, Q.Menü
- Kaynak (HDMI vb.) seçimi, uygulama listesi ve başlatma
- Rakam tuşları, renkli tuşlar, medya tuşları
- **Touchpad**: Magic Remote gibi imleç hareketi, tıklama ve kaydırma
- **Klavye**: TV'deki arama kutularına telefondan yazı gönderme
- **IR modu**: Telefonda kızılötesi verici varsa (Xiaomi/Redmi/POCO vb.) tuşlar IR ile gönderilir.
  Bu model Wi-Fi üzerinden **açılamadığı** için TV'yi açmanın tek yolu IR (veya TV'deki joystick tuşu).

## Teknik

- LG: webOS SSAP protokolü, `ws://<tv-ip>:3000`
- Android TV: Android TV Remote v2 protokolü (Google TV uygulamasıyla aynı), TLS, port 6466/6467
- Eşleştirme manifesti [lgtv2](https://github.com/hobbyquaker/lgtv2) projesinden alınmıştır (MIT).
- Yerelde derleme: `./gradlew assembleRelease` (Android SDK gerekir).
