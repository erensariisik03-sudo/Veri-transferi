package com.hizliaktar

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Wi-Fi Direct kurulamazsa yedek: internetsiz, yalnızca yerel bir hotspot açar
 * (Android'in LocalOnlyHotspot özelliği). Ad/şifreyi sistem verir.
 */
@SuppressLint("MissingPermission")
class HotspotHost(private val ctx: Context) {

    var ssid: String? = null
        private set
    var pass: String? = null
        private set

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Suppress("DEPRECATION")
    fun start(onReady: () -> Unit, onFail: (String) -> Unit) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = r.softApConfiguration
                        ssid = c.ssid
                        pass = c.passphrase
                    } else {
                        val c = r.wifiConfiguration
                        ssid = c?.SSID?.trim('"')
                        pass = c?.preSharedKey?.trim('"')
                    }
                    if (ssid != null && pass != null) onReady()
                    else onFail("Hotspot bilgisi okunamadı.")
                }

                override fun onStopped() {}

                override fun onFailed(reason: Int) {
                    onFail("Hotspot açılamadı (kod $reason). Konum açık mı?")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            onFail("Hotspot açılamadı: ${e.message}")
        }
    }

    fun stop() {
        try {
            reservation?.close()
        } catch (e: Exception) {
        }
        reservation = null
    }
}
