[app]
title = LAN Dosya Transferi
package.name = landosyatransferi
package.domain = org.eren

source.dir = .
source.include_exts = py,png,jpg,kv,atlas

version = 2.0.0

requirements = python3,kivy==2.3.0,flask==2.3.3,werkzeug==2.3.8,jinja2,markupsafe,itsdangerous,click,blinker,segno,android

orientation = portrait
fullscreen = 0

android.permissions = INTERNET,ACCESS_NETWORK_STATE,ACCESS_WIFI_STATE,CAMERA,READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE,MANAGE_EXTERNAL_STORAGE
android.api = 33
android.minapi = 24
android.archs = arm64-v8a, armeabi-v7a
android.accept_sdk_license = True
android.allow_backup = True

# QR tarayici (Google Play Hizmetleri gerektirmez; Huawei dahil her cihazda calisir)
android.enable_androidx = True
android.gradle_dependencies = com.journeyapps:zxing-android-embedded:4.3.0
android.add_compile_options = "sourceCompatibility = 1.8", "targetCompatibility = 1.8"

# Android 10 klasik depolama + duz HTTP (LAN) icin
android.manifest.application_attributes = android:requestLegacyExternalStorage="true", android:usesCleartextTraffic="true"

[buildozer]
log_level = 2
warn_on_root = 1
