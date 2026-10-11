package com.hizliaktar

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.media.MediaScannerConnection
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Alıcı: gönderene bağlanır, dosyaları sırayla indirir. Bağlantı koparsa
 * ağı yeniden bulur / yeniden katılır ve kaldığı byte'tan devam eder.
 */
class Receiver(private val ctx: Context, private val link: Link) {

    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var cancelled = false

    @Volatile
    private var net: Network? = null
    private var curHost: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** true: yerel ağ yerine doğrudan (Wi-Fi Direct/hotspot) ağı kullan. */
    @Volatile
    private var preferDirect = false

    @Volatile
    private var lastRtt = 0L

    // ---------------------------------------------------------------- akış

    fun run() {
        // Öncelik: gönderenin kendi açtığı ağ (Wi-Fi Direct/hotspot). Yerel ağ yedek.
        preferDirect = link.ssid != null && link.pass != null
        Bus.update { it.copy(phase = Phase.CONNECTING, message = "Gönderene bağlanılıyor…") }
        var tries = 0
        while (!cancelled && !ensureLink()) {
            tries++
            if (tries >= 6) return fail("Gönderene ulaşılamadı. QR'ı tekrar tara.")
            sleep(1500)
        }
        if (cancelled) return
        val list = loadManifest() ?: return fail("Dosya listesi alınamadı.")
        Bus.update { s ->
            s.copy(files = list.map { FileRow(it.first, it.second, 0) })
        }
        for (i in list.indices) {
            if (!downloadFile(i, list[i].first, list[i].second)) return
        }
        Bus.update { it.copy(phase = Phase.DONE, message = "Tamamlandı. Dosyalar İndirilenler/HizliAktar içinde.", speedBps = 0) }
    }

    fun close() {
        cancelled = true
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
            }
        }
        callback = null
    }

    private fun fail(msg: String) {
        if (!cancelled) Bus.update { it.copy(phase = Phase.ERROR, message = msg, speedBps = 0) }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }

    // ------------------------------------------------------------ manifest

    private fun loadManifest(): List<Pair<String, Long>>? {
        var tries = 0
        while (!cancelled && tries < 30) {
            try {
                val c = open("/manifest", 0)
                val txt = c.inputStream.bufferedReader().readText()
                c.disconnect()
                val arr = JSONObject(txt).getJSONArray("files")
                return (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    o.getString("n") to o.getLong("s")
                }
            } catch (e: Exception) {
                tries++
                sleep(1000)
                ensureLink()
            }
        }
        return null
    }

    // ------------------------------------------------------------- indirme

    private fun downloadFile(i: Int, name: String, size: Long): Boolean {
        val dir = ctx.getExternalFilesDir("incoming") ?: ctx.cacheDir
        val part = File(dir, "${link.token}_$i.part")
        if (part.exists() && part.length() > size) part.delete()
        var stall = 0
        while (!cancelled && part.length() < size) {
            val before = part.length()
            try {
                val c = open("/file/$i", before)
                val code = c.responseCode
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                val append = code == 206 && before > 0
                if (!append && before > 0) part.delete()
                var got = if (append) before else 0L
                Bus.update { it.copy(phase = Phase.TRANSFERRING, message = "Aktarılıyor") }
                FileOutputStream(part, append).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(1 shl 18)
                        var last = 0L
                        while (!cancelled) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val now = System.nanoTime()
                            if (now - last > 150_000_000L) {
                                last = now
                                setDone(i, got)
                            }
                        }
                    }
                }
                c.disconnect()
                setDone(i, part.length())
                if (!cancelled && part.length() < size) throw IOException("eksik veri")
            } catch (e: IOException) {
                if (cancelled) return false
                stall = if (part.length() > before) 1 else stall + 1
                // Yerel ağ üst üste koparsa doğrudan ağa geç.
                if (stall >= 3 && link.ssid != null) preferDirect = true
                if (stall > 40) {
                    fail("Bağlantı kurtarılamadı. QR'ı tekrar tara; kaldığın yerden devam eder.")
                    return false
                }
                Bus.update {
                    it.copy(
                        phase = Phase.RECONNECTING,
                        message = "Bağlantı koptu, yeniden bağlanılıyor… ($stall)",
                        speedBps = 0
                    )
                }
                sleep(minOf(1000L * stall, 5000L))
                ensureLink()
            }
        }
        if (cancelled) return false
        return try {
            if (!part.exists()) part.createNewFile()
            Saver.save(ctx, part, name)
            part.delete()
            setDone(i, size)
            true
        } catch (e: Exception) {
            fail("Kaydedilemedi: ${e.message}")
            false
        }
    }

    private fun setDone(i: Int, done: Long) {
        Bus.update { s ->
            s.copy(files = s.files.mapIndexed { j, r -> if (j == i) r.copy(done = done) else r })
        }
    }

    // ---------------------------------------------------------------- ağ

    private fun open(path: String, offset: Long): HttpURLConnection {
        val url = URL("http://$curHost:${link.port}$path?k=${link.token}")
        val n = net
        val c = (if (n != null) n.openConnection(url) else url.openConnection()) as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 15000
        if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
        return c
    }

    private fun probe(h: String, n: Network?): Boolean {
        return try {
            val t0 = System.nanoTime()
            val url = URL("http://$h:${link.port}/ping?k=${link.token}")
            val c = (if (n != null) n.openConnection(url) else url.openConnection()) as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 1500
            val ok = c.responseCode == 200
            c.disconnect()
            if (ok) lastRtt = (System.nanoTime() - t0) / 1_000_000L
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun tryHosts(n: Network?): Boolean {
        for (h in link.hosts) {
            if (cancelled) return false
            if (probe(h, n)) {
                probe(h, n) // ikinci ölçüm: ilk bağlantı yavaş görünmesin
                curHost = h
                net = n
                return true
            }
        }
        return false
    }

    /** Ortak yerel ağdaki (veya bağlı Wi-Fi ağlarındaki) göndereni ara. */
    private fun findWifi(): Boolean {
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && tryHosts(n)) return true
        }
        return tryHosts(null)
    }

    /**
     * Gönderene giden çalışan bir yol bul. Önce gönderenin kendi ağına (Wi-Fi Direct/hotspot)
     * katılmayı dener; olmazsa ortak yerel ağı kullanır. QR'da ağ bilgisi yoksa yalnızca yerel ağ.
     */
    @Synchronized
    fun ensureLink(): Boolean {
        val h = curHost
        if (h != null && probe(h, net)) return true
        val canDirect = link.ssid != null && link.pass != null
        var searched = false
        var lan = false
        if (!preferDirect || !canDirect) {
            searched = true
            lan = findWifi()
            if (lan && (!canDirect || lastRtt <= 80)) return true
            if (lan) preferDirect = true
        }
        if (canDirect && !cancelled && joinDirect()) return true
        return lan || (!searched && findWifi())
    }

    private fun joinDirect(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val n = joinSpecifier() ?: return false
            return tryHosts(n)
        }
        return joinLegacy() && findWifi()
    }

    /** Android 9 ve altı: klasik Wi-Fi yapılandırması ile gönderenin ağına katıl. */
    @Suppress("DEPRECATION")
    private fun joinLegacy(): Boolean {
        Bus.update { it.copy(phase = Phase.CONNECTING, message = "Gönderenin Wi-Fi ağına bağlanılıyor…") }
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wm.isWifiEnabled) wm.isWifiEnabled = true
            val q = "\"" + link.ssid + "\""
            val conf = WifiConfiguration()
            conf.SSID = q
            conf.preSharedKey = "\"" + link.pass + "\""
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            var id = wm.addNetwork(conf)
            if (id < 0) id = wm.configuredNetworks?.firstOrNull { it.SSID == q }?.networkId ?: -1
            if (id < 0) return false
            wm.disconnect()
            wm.enableNetwork(id, true)
            wm.reconnect()
            val end = System.currentTimeMillis() + 25000
            while (!cancelled && System.currentTimeMillis() < end) {
                val info = wm.connectionInfo
                if (info != null && info.networkId == id &&
                    info.supplicantState == SupplicantState.COMPLETED && info.ipAddress != 0
                ) {
                    sleep(1000)
                    return true
                }
                sleep(500)
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /** Android 10+: sistem onayıyla belirli ağa katıl. */
    private fun joinSpecifier(): Network? {
        Bus.update { it.copy(phase = Phase.CONNECTING, message = "Gönderenin Wi-Fi ağına bağlanılıyor… (onay penceresi çıkarsa Bağlan'a bas)") }
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
            }
        }
        callback = null
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(link.ssid!!)
            .setWpa2Passphrase(link.pass!!)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val latch = CountDownLatch(1)
        var got: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                got = network
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }

            override fun onLost(network: Network) {
                if (net == network) net = null
            }
        }
        callback = cb
        return try {
            cm.requestNetwork(req, cb, 30000)
            latch.await(32, TimeUnit.SECONDS)
            got
        } catch (e: Exception) {
            null
        }
    }
}
