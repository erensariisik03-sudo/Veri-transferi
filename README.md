# Hızlı Aktar

Telefon, tablet ve bilgisayar arasında dosya aktarımı. Android (APK) ve Windows (EXE).

## Özellikler

**Ortak havuz (aynı ağda, 2 veya daha fazla cihaz)**
- Bir cihazda "Yeni havuz oluştur"; diğerlerinde QR'ı okut ya da 10 haneli kodu bir kez gir. Eşleşme saklanır.
- Bir cihaz dosya eklediğinde diğer tüm cihazlarda listede görünür; isteyen dokunup indirir.
- Cihazlar birbirini UDP yayınıyla otomatik bulur. Yalnızca aynı ağda (aynı Wi-Fi / hotspot) çalışır.

**Gönder / Al (QR ile birebir aktarım)**
- Gönderen dosyaları seçer (sınırsız), QR çıkar, alıcı okutur. IP/PIN yok.
- Öncelik: gönderen kendi ağını açar (Wi-Fi Direct; önce 5 GHz, olmazsa 2.4 GHz; o da olmazsa hotspot).
  Ortak Wi-Fi yedek yoldur.
- Yüzde, hız ve dosya başına ilerleme. Bağlantı koparsa otomatik yeniden bağlanır, kaldığı byte'tan devam eder.

## Derleme

1. Bu klasörün içeriğini bir GitHub reposuna yükle (kökte `settings.gradle.kts` ve `windows/` olmalı).
2. Actions → "APK ve EXE Derle" → "Run workflow" (yalnızca elle tetiklenir).
3. Bitince Artifacts: `HizliAktar-apk` (app-debug.apk) ve `HizliAktar-exe` (HizliAktar.exe).

Gereksinimler: Android 8.0+ / Windows 10+.

## Windows notları

- İlk açılışta Windows Güvenlik Duvarı izin sorar: "Özel ağlar"a izin ver (havuzun çalışması için gerekli).
- PC kendi Wi-Fi ağını açamaz. PC ile aktarım için ortak havuzu kullan (aynı Wi-Fi ya da telefonun hotspot'u).
- Telefondan PC'ye QR ile aktarım: telefonda "Bağlantıyı kopyala", PC'de "Al" sekmesine yapıştır.
- EXE imzasız olduğundan SmartScreen/antivirüs uyarı verebilir ("Yine de çalıştır").

## Geliştirme

- `windows/test_core.py` çekirdeği (protokol, devam ettirme, havuz) yerel ağda sınar: `python windows/test_core.py`
