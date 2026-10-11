package com.hizliaktar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat

/** Ortak havuzu arka planda açık tutar (UDP keşif + dosya sunucusu). */
class PoolService : Service() {

    companion object {
        const val ACTION_START = "ha.pool.start"
        const val ACTION_STOP = "ha.pool.stop"
        private const val CH = "pool"
        private const val NOTIF_ID = 2

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PoolService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, PoolService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var engine: PoolEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Ortak havuz", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground("Ortak havuz açılıyor…")
        val key = PoolKey.load(this)
        if (key == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine?.key != key) {
            shutdown()
            begin(key)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun begin(key: String) {
        acquireLocks()
        bindWifi()
        val e = PoolEngine(this, key, PoolKey.deviceId(this), Build.MODEL ?: "Android")
        engine = e
        PoolBus.engine = e
        e.start()
        main.post(object : Runnable {
            override fun run() {
                if (engine == null) return
                val n = PoolBus.state.devices.size
                notify(if (n == 0) "Ortak havuz açık · cihaz bekleniyor" else "Ortak havuz açık · $n cihaz")
                main.postDelayed(this, 5000)
            }
        })
    }

    private fun shutdown() {
        main.removeCallbacksAndMessages(null)
        engine?.stop()
        engine = null
        PoolBus.engine = null
        PoolBus.set(PoolState())
        netCb?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (e: Exception) {
            }
        }
        netCb = null
        try {
            getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
        } catch (e: Exception) {
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        wifiLock = null
        multicastLock = null
    }

    /** Wi-Fi'ı varsayılan ağ yap: internetsiz Wi-Fi'da trafik mobil veriye kaçmasın. */
    private fun bindWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
            }

            override fun onLost(network: Network) {
                cm.bindProcessToNetwork(null)
            }
        }
        try {
            cm.registerNetworkCallback(req, cb)
            netCb = cb
        } catch (e: Exception) {
        }
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hizliaktar:pool").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "hizliaktar:poolwifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        // Ekran kapalıyken UDP yayınlarını alabilmek için.
        multicastLock = wm.createMulticastLock("hizliaktar:mc").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, PoolActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Hızlı Aktar")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun goForeground(text: String) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
