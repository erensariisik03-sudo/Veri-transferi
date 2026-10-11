package com.hizliaktar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat

class TransferService : Service() {

    companion object {
        const val ACTION_SEND = "ha.send"
        const val ACTION_RECEIVE = "ha.receive"
        const val ACTION_STOP = "ha.stop"
        private const val CH = "transfer"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, action: String) {
            val i = Intent(ctx, TransferService::class.java).setAction(action)
            ctx.startForegroundService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: FileServer? = null
    private var wifi: WifiDirectHost? = null
    private var hotspot: HotspotHost? = null
    private var receiver: Receiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var reached = LongArray(0)
    private var lastDone = 0L
    private var speed = 0.0
    private var ticking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Aktarım", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cleanup()
                Bus.reset()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SEND -> {
                goForeground("Hazırlanıyor…", -1)
                startSend()
            }
            ACTION_RECEIVE -> {
                goForeground("Bağlanılıyor…", -1)
                startReceive()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ------------------------------------------------------------ gönderen

    private fun startSend() {
        cleanup()
        val files = Bus.pendingSend
        if (files.isEmpty()) return fatal("Önce dosya seç.")
        Bus.update {
            UiState(
                sender = true, phase = Phase.STARTING, message = "Hazırlanıyor…",
                files = files.map { FileRow(it.name, it.size, 0) }
            )
        }
        acquireLocks()
        startTicker()
        reached = LongArray(files.size)
        val token = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        val srv = FileServer(this, { it == token }, { files }, { i, b -> onSent(i, b) }, { onClient() })
        val port = try {
            srv.start()
        } catch (e: Exception) {
            return fatal("Sunucu başlatılamadı: ${e.message}")
        }
        server = srv
        val host = WifiDirectHost(this)
        wifi = host
        host.start(
            onReady = {
                val hosts = (listOf("192.168.49.1") + Util.localIps()).distinct()
                val link = Link(host.ssid, host.pass, hosts, port, token)
                Bus.update {
                    it.copy(
                        phase = Phase.READY,
                        message = "Alıcıda \"Al\" → QR'ı okut. Bağlantı: ${host.band}",
                        qrPayload = link.encode(), band = host.band
                    )
                }
            },
            onFail = { why -> startHotspot(port, token, why) }
        )
    }

    /** Wi-Fi Direct olmadı: yerel hotspot dene, o da olmazsa ortak ağa güven. */
    private fun startHotspot(port: Int, token: String, why: String) {
        wifi?.stop()
        wifi = null
        Bus.update { it.copy(message = "Wi-Fi Direct olmadı, hotspot deneniyor…") }
        val hs = HotspotHost(this)
        hotspot = hs
        hs.start(
            onReady = {
                // Arayüzün IP alması birkaç saniye sürebilir.
                main.postDelayed({
                    val ips = Util.localIps()
                    val hosts = if (ips.isEmpty()) listOf("192.168.43.1") else ips
                    val link = Link(hs.ssid, hs.pass, hosts, port, token)
                    Bus.update {
                        it.copy(
                            phase = Phase.READY,
                            message = "Alıcıda \"Al\" → QR'ı okut. Bağlantı: Hotspot",
                            qrPayload = link.encode(), band = "Hotspot"
                        )
                    }
                }, 1500)
            },
            onFail = { why2 ->
                val hosts = Util.localIps()
                if (hosts.isEmpty()) {
                    fatal("$why $why2")
                } else {
                    val link = Link(null, null, hosts, port, token)
                    Bus.update {
                        it.copy(
                            phase = Phase.READY,
                            message = "Kendi ağı açılamadı. İki cihaz aynı Wi-Fi'daysa QR yine çalışır.",
                            qrPayload = link.encode(), band = "Yerel ağ"
                        )
                    }
                }
            }
        )
    }

    private fun onSent(i: Int, bytes: Long) {
        synchronized(this) {
            if (bytes > reached[i]) reached[i] = bytes
        }
        Bus.update { s ->
            val rows = s.files.mapIndexed { j, r -> if (j == i) r.copy(done = reached[i]) else r }
            val all = rows.all { it.done >= it.size }
            s.copy(
                files = rows,
                phase = if (all) Phase.DONE else Phase.TRANSFERRING,
                message = if (all) "Gönderim tamamlandı" else "Gönderiliyor"
            )
        }
    }

    private fun onClient() {
        if (Bus.state.phase == Phase.READY) {
            Bus.update { it.copy(phase = Phase.CONNECTING, message = "Alıcı bağlandı") }
        }
    }

    // --------------------------------------------------------------- alıcı

    private fun startReceive() {
        cleanup()
        val link = Bus.pendingLink?.let { Link.parse(it) }
            ?: return fatal("Geçersiz QR kodu.")
        Bus.update { UiState(sender = false, phase = Phase.CONNECTING, message = "Bağlanılıyor…") }
        acquireLocks()
        startTicker()
        val r = Receiver(this, link)
        receiver = r
        Thread({
            r.run()
            main.post {
                if (receiver === r) {
                    cleanup()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }, "receiver").start()
    }

    // ---------------------------------------------------------------- ortak

    private fun fatal(msg: String) {
        cleanup()
        Bus.update { it.copy(phase = Phase.ERROR, message = msg, speedBps = 0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        ticking = false
        main.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        wifi?.stop()
        wifi = null
        hotspot?.stop()
        hotspot = null
        receiver?.close()
        receiver = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hizliaktar:wl").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "hizliaktar:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Saniyede bir hız hesapla, bildirimi güncelle. */
    private fun startTicker() {
        lastDone = 0
        speed = 0.0
        ticking = true
        main.post(object : Runnable {
            override fun run() {
                if (!ticking) return
                val s = Bus.state
                val done = s.done
                val delta = (done - lastDone).coerceAtLeast(0)
                lastDone = done
                val active = s.phase == Phase.TRANSFERRING
                speed = if (active) speed * 0.5 + delta * 0.5 else 0.0
                val sp = speed.toLong()
                if (sp != s.speedBps) Bus.update { it.copy(speedBps = sp) }
                if (s.phase == Phase.TRANSFERRING || s.phase == Phase.RECONNECTING) {
                    notify("%" + s.percent + " · " + Util.fmtSpeed(sp), s.percent)
                }
                main.postDelayed(this, 1000)
            }
        })
    }

    private fun buildNotification(text: String, pct: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Hızlı Aktar")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
        if (pct >= 0) b.setProgress(100, pct, false)
        return b.build()
    }

    private fun goForeground(text: String, pct: Int) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(text, pct),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun notify(text: String, pct: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, pct))
    }
}
