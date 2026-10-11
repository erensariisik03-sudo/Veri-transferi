# Hızlı Aktar

Telefondan telefona QR ile dosya aktarımı. IP/port/PIN yok.

- Gönderen: dosyaları seçer (sınırsız), "Bağlantıyı başlat" der, QR çıkar.
- Alıcı: "Al" → QR tara. Dosyalar İndirilenler/HizliAktar klasörüne iner.
- Wi-Fi Direct grubu önce 5 GHz, olmazsa 2.4 GHz, o da olmazsa otomatik bandla kurulur.
- Aynı Wi-Fi/hotspot'taki cihazlar için yerel ağ adresi de QR'a girer (yedek yol).
- Bağlantı koparsa alıcı ağa yeniden katılır ve dosyaya kaldığı byte'tan devam eder.
- Yüzde, hız (MB/sn) ve dosya başına ilerleme gösterilir.

## Derleme

1. Bu klasörün içeriğini bir GitHub reposuna yükle (kökte `settings.gradle.kts` olmalı).
2. Actions → "APK Derle" otomatik çalışır (push'ta) veya "Run workflow".
3. Bitince Artifacts → `HizliAktar-apk` içinden `app-debug.apk` indir, kur.

Gereksinim: Android 10 (API 29) ve üzeri.
