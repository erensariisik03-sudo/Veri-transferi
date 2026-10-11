package com.hizliaktar

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

/**
 * Gönderen cihazda Wi-Fi Direct grubu açar. Önce 5 GHz dener, olmazsa 2.4 GHz,
 * o da olmazsa otomatik. Grup düşerse aynı ad/şifreyle yeniden kurar.
 */
@SuppressLint("MissingPermission")
class WifiDirectHost(private val ctx: Context) {

    private val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private val handler = Handler(Looper.getMainLooper())

    var ssid: String? = null
        private set
    var pass: String? = null
        private set
    var band: String = ""
        private set

    @Volatile
    private var active = false
    private var bandIndex = 0

    private val bands = listOf(
        WifiP2pConfig.GROUP_OWNER_BAND_5GHZ to "5 GHz",
        WifiP2pConfig.GROUP_OWNER_BAND_2GHZ to "2.4 GHz",
        WifiP2pConfig.GROUP_OWNER_BAND_AUTO to "Otomatik"
    )

    private fun rnd(n: Int): String {
        val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..n).map { chars.random() }.joinToString("")
    }

    fun start(onReady: () -> Unit, onFail: (String) -> Unit) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            onFail("Wi-Fi kapalı. Wi-Fi'yi açıp tekrar dene.")
            return
        }
        channel = mgr.initialize(ctx, Looper.getMainLooper(), null)
        if (channel == null) {
            onFail("Bu cihaz Wi-Fi Direct desteklemiyor.")
            return
        }
        ssid = "DIRECT-HA-" + rnd(4)
        pass = rnd(12)
        active = true
        removeThen { create(0, onReady, onFail) }
    }

    private fun removeThen(next: () -> Unit) {
        val ch = channel ?: return next()
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                handler.postDelayed({ next() }, 300)
            }

            override fun onFailure(reason: Int) {
                handler.postDelayed({ next() }, 300)
            }
        })
    }

    private fun config(i: Int): WifiP2pConfig? = try {
        WifiP2pConfig.Builder()
            .setNetworkName(ssid!!)
            .setPassphrase(pass!!)
            .enablePersistentMode(false)
            .setGroupOperatingBand(bands[i].first)
            .build()
    } catch (e: Exception) {
        null
    }

    private fun create(i: Int, onReady: () -> Unit, onFail: (String) -> Unit) {
        if (!active) return
        if (i >= bands.size) {
            onFail("Wi-Fi Direct grubu kurulamadı. Wi-Fi açık mı, izinler verildi mi?")
            return
        }
        val cfg = config(i)
        val ch = channel
        if (cfg == null || ch == null) {
            create(i + 1, onReady, onFail)
            return
        }
        mgr.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                bandIndex = i
                band = bands[i].second
                onReady()
                scheduleWatchdog()
            }

            override fun onFailure(reason: Int) {
                removeThen { create(i + 1, onReady, onFail) }
            }
        })
    }

    /** Grup sessizce düşerse (ekran kapanması, sistem müdahalesi) tekrar kur. */
    private fun scheduleWatchdog() {
        handler.postDelayed({
            if (!active) return@postDelayed
            val ch = channel ?: return@postDelayed
            mgr.requestGroupInfo(ch) { g ->
                if (g == null && active) {
                    val cfg = config(bandIndex)
                    if (cfg != null) {
                        mgr.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {}
                            override fun onFailure(reason: Int) {}
                        })
                    }
                }
                scheduleWatchdog()
            }
        }, 8000)
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        val ch = channel ?: return
        try {
            mgr.removeGroup(ch, null)
        } catch (e: Exception) {
        }
        try {
            ch.close()
        } catch (e: Exception) {
        }
        channel = null
    }
}
