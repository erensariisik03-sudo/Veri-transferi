package com.hizliaktar

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Ortak havuz: aynı ağdaki cihazlar UDP yayınıyla birbirini bulur, her cihaz
 * paylaştığı dosyaların listesini HTTP ile sunar. Windows uygulamasıyla aynı protokol.
 */
class PoolEngine(
    private val ctx: Context,
    val key: String,
    private val deviceId: String,
    private val deviceName: String
) {
    companion object {
        const val UDP_PORT = 47474
    }

    private class Peer(val id: String, var name: String, var ip: String, var port: Int) {
        var ver = -1
        var fetched = -2
        var fetching = false

        @Volatile
        var last = System.currentTimeMillis()
        var files: List<PoolFile> = emptyList()
    }

    private val pid = PoolKey.pid(key)
    private val tok = PoolKey.tok(key)
    private val lock = Any()
    private val mine = ArrayList<SendFile?>()
    private val peers = LinkedHashMap<String, Peer>()
    private val dl = HashMap<String, DlState>()
    private val totalBytes = AtomicLong(0)
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var version = 0

    @Volatile
    private var running = false

    @Volatile
    private var speed = 0L

    @Volatile
    private var error = ""
    private var server: FileServer? = null
    private var httpPort = 0

    // ------------------------------------------------------------ yaşam döngüsü

    fun start() {
        running = true
        val srv = FileServer(
            ctx, { it == tok }, { synchronized(lock) { ArrayList(mine) } }, { _, _ -> }, { }
        )
        httpPort = srv.start()
        server = srv
        pool.execute { announceLoop() }
        pool.execute { listenLoop() }
        pool.execute { tickLoop() }
        publish()
    }

    fun stop() {
        running = false
        server?.stop()
        server = null
        pool.shutdownNow()
    }

    // ----------------------------------------------------------------- paylaşım

    fun addFiles(list: List<SendFile>) {
        synchronized(lock) {
            mine.addAll(list)
            version++
        }
        publish()
    }

    fun removeFile(index: Int) {
        synchronized(lock) {
            if (index in mine.indices) {
                mine[index] = null
                version++
            }
        }
        publish()
    }

    // ------------------------------------------------------------------- keşif

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }

    private fun broadcastAddrs(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b != null && !out.contains(b)) out.add(b)
                }
            }
        } catch (e: Exception) {
        }
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
        }
        return out
    }

    private fun announceLoop() {
        val sock: DatagramSocket
        try {
            sock = DatagramSocket()
            sock.broadcast = true
        } catch (e: Exception) {
            error = "Yayın soketi açılamadı"
            return
        }
        while (running) {
            try {
                val data = JSONObject().put("a", "hizli").put("p", pid).put("d", deviceId)
                    .put("n", deviceName).put("t", httpPort).put("v", version)
                    .toString().toByteArray(Charsets.UTF_8)
                for (addr in broadcastAddrs()) {
                    try {
                        sock.send(DatagramPacket(data, data.size, addr, UDP_PORT))
                    } catch (e: Exception) {
                    }
                }
                reap()
            } catch (e: Exception) {
            }
            sleep(2000)
        }
        sock.close()
    }

    private fun listenLoop() {
        val sock: DatagramSocket
        try {
            sock = DatagramSocket(null as SocketAddress?)
            sock.reuseAddress = true
            sock.broadcast = true
            sock.bind(InetSocketAddress(UDP_PORT))
            sock.soTimeout = 1000
        } catch (e: Exception) {
            error = "Keşif portu açılamadı"
            publish()
            return
        }
        val buf = ByteArray(4096)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val ip = p.address.hostAddress
                if (ip != null) onPacket(String(p.data, 0, p.length, Charsets.UTF_8), ip)
            } catch (e: SocketTimeoutException) {
            } catch (e: Exception) {
                if (!running) break
            }
        }
        sock.close()
    }

    private fun onPacket(text: String, ip: String) {
        val m = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        if (m.optString("a") != "hizli" || m.optString("p") != pid) return
        val id = m.optString("d")
        if (id.isEmpty() || id == deviceId) return
        val port = m.optInt("t", 0)
        val ver = m.optInt("v", 0)
        if (port <= 0) return
        var target: Peer? = null
        var added = false
        synchronized(lock) {
            var p = peers[id]
            if (p == null) {
                p = Peer(id, m.optString("n", "?"), ip, port)
                peers[id] = p
                added = true
            }
            p.name = m.optString("n", "?")
            p.ip = ip
            p.port = port
            p.ver = ver
            p.last = System.currentTimeMillis()
            if (p.ver != p.fetched && !p.fetching) {
                p.fetching = true
                target = p
            }
        }
        val t = target
        if (t != null) pool.execute { fetchManifest(t, ver) }
        if (added) publish()
    }

    private fun fetchManifest(p: Peer, ver: Int) {
        try {
            val c = URL("http://${p.ip}:${p.port}/manifest?k=$tok").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 4000
            val txt = c.inputStream.bufferedReader().readText()
            c.disconnect()
            val arr = JSONObject(txt).getJSONArray("files")
            val list = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PoolFile(p.id, o.getInt("i"), o.getString("n"), o.getLong("s"))
            }
            synchronized(lock) {
                p.files = list
                p.fetched = ver
            }
        } catch (e: Exception) {
        } finally {
            synchronized(lock) { p.fetching = false }
        }
        publish()
    }

    /** 9 sn haber gelmeyen cihazı listeden düşür. */
    private fun reap() {
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(lock) {
            val it = peers.values.iterator()
            while (it.hasNext()) {
                if (now - it.next().last > 9000) {
                    it.remove()
                    changed = true
                }
            }
        }
        if (changed) publish()
    }

    private fun tickLoop() {
        var last = totalBytes.get()
        var sp = 0.0
        while (running) {
            sleep(1000)
            val now = totalBytes.get()
            sp = sp * 0.5 + (now - last) * 0.5
            last = now
            val v = sp.toLong()
            if (v != speed) {
                speed = v
                publish()
            }
        }
    }

    // ------------------------------------------------------------------ indirme

    fun download(owner: String, index: Int) {
        val k = "$owner:$index"
        val f: PoolFile = synchronized(lock) {
            val pf = peers[owner]?.files?.firstOrNull { it.index == index } ?: return
            if (dl[k]?.status == "downloading") return
            dl[k] = DlState(0, pf.size, "downloading")
            pf
        }
        publish()
        pool.execute { downloadWorker(f, k) }
    }

    private fun setDl(k: String, done: Long, size: Long, status: String) {
        synchronized(lock) { dl[k] = DlState(done, size, status) }
        publish()
    }

    private fun downloadWorker(f: PoolFile, k: String) {
        val dir = ctx.getExternalFilesDir("incoming") ?: ctx.cacheDir
        val part = File(dir, "pool_${pid}_${f.owner}_${f.index}_${f.size}.part")
        var ok = false
        try {
            var stall = 0
            while (running && part.length() < f.size) {
                val before = part.length()
                try {
                    val p = synchronized(lock) { peers[f.owner] } ?: throw IOException("cihaz yok")
                    val c = URL("http://${p.ip}:${p.port}/file/${f.index}?k=$tok")
                        .openConnection() as HttpURLConnection
                    c.connectTimeout = 5000
                    c.readTimeout = 15000
                    if (before > 0) c.setRequestProperty("Range", "bytes=$before-")
                    val code = c.responseCode
                    if (code != 200 && code != 206) throw IOException("HTTP $code")
                    val append = code == 206 && before > 0
                    if (!append && before > 0) part.delete()
                    var got = if (append) before else 0L
                    FileOutputStream(part, append).use { out ->
                        c.inputStream.use { inp ->
                            val buf = ByteArray(1 shl 18)
                            var lastPub = 0L
                            while (running) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                totalBytes.addAndGet(n.toLong())
                                val now = System.nanoTime()
                                if (now - lastPub > 200_000_000L) {
                                    lastPub = now
                                    setDl(k, got, f.size, "downloading")
                                }
                            }
                        }
                    }
                    c.disconnect()
                    if (running && part.length() < f.size) throw IOException("eksik veri")
                } catch (e: IOException) {
                    if (!running) break
                    stall = if (part.length() > before) 1 else stall + 1
                    if (stall > 30) break
                    sleep(minOf(1000L * stall, 5000L))
                }
            }
            if (running && part.length() >= f.size) {
                if (!part.exists()) part.createNewFile()
                Saver.save(ctx, part, f.name)
                part.delete()
                ok = true
            }
        } catch (e: Exception) {
            ok = false
        }
        setDl(k, if (ok) f.size else part.length(), f.size, if (ok) "done" else "error")
    }

    // ------------------------------------------------------------------ görünüm

    private fun publish() {
        val s = synchronized(lock) {
            PoolState(
                running = running,
                message = error,
                mine = mine.mapIndexedNotNull { i, f ->
                    if (f != null) PoolFile(deviceId, i, f.name, f.size) else null
                },
                devices = peers.values.sortedBy { it.name.lowercase() }
                    .map { PoolDevice(it.id, it.name, it.ip, it.files) },
                dl = HashMap(dl),
                speedBps = speed
            )
        }
        PoolBus.set(s)
    }
}
