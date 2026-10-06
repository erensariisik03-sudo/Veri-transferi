# LAN Dosya Transferi (APK)

QR ile eşleşen, aynı ağdaki cihazlar arası dosya aktarımı. Flask sunucusu + Kivy arayüzü.

## Kullanım
- **Gönderen:** "Dosya Gönder" > klasörlerde gezin, dosyaya dokunun > ekranda QR çıkar. Alıcı indirirken yüzde ilerleme görünür.
- **Alan:** "Dosya Al" > QR'ı okutun > dosya Download klasörüne iner (yüzde ilerleme gösterilir).
- QR'daki bağlantı tek seferlik rastgele bir anahtar içerir; PIN/IP yazmaya gerek yok.
  QR'ı herhangi bir telefon kamerası okutursa tarayıcıdan da indirir.

## APK nasıl alınır
GitHub'a yükle > Actions > Build APK > Artifacts > `lan-dosya-transferi-apk`.

## İzinler
Depolama, "Tüm dosyalara erişim" ve kamera (QR tarama) izinleri istenir.
