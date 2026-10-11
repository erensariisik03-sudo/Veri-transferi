package com.hizliaktar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Çok küçük bir HTTP sunucusu. Range destekler; bağlantı koparsa alıcı
 * kaldığı yerden devam eder.
 */
class FileServer(
    private val ctx: Context,
    private val tokenOk: (String?) -> Boolean,
    private val files: () -> List<SendFile?>,
    private val onProgress: (index: Int, bytesReached: Long) -> Unit,
    private val onClient: () -> Unit
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    fun start(): Int {
        val s = ServerSocket(0)
        server = s
        running = true
        Thread({
            while (running) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }, "accept").start()
        return s.localPort
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
        }
        pool.shutdownNow()
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 20000
            sock.sendBufferSize = 1 shl 20
            sock.tcpNoDelay = true
            val input = sock.getInputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 2) return
            val target = parts[1]
            val path = target.substringBefore("?")
            val query = target.substringAfter("?", "")
            val k = query.split("&").map { it.split("=", limit = 2) }
                .firstOrNull { it[0] == "k" }?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
            val out = BufferedOutputStream(sock.getOutputStream(), 1 shl 16)
            if (!tokenOk(k)) {
                reply(out, 403, "Forbidden", "yetkisiz")
                return
            }
            onClient()
            when {
                path == "/ping" -> reply(out, 200, "OK", "ok")
                path == "/manifest" -> reply(out, 200, "OK", manifest(), "application/json")
                path.startsWith("/file/") -> {
                    val idx = path.removePrefix("/file/").toIntOrNull()
                    val file = if (idx == null) null else files().getOrNull(idx)
                    if (idx == null || file == null) {
                        reply(out, 404, "Not Found", "yok")
                    } else {
                        var range = 0L
                        for (l in lines) {
                            if (l.startsWith("Range:", true)) {
                                val m = Regex("bytes=(\\d+)-").find(l)
                                if (m != null) range = m.groupValues[1].toLong()
                            }
                        }
                        sendFile(out, idx, file, range)
                    }
                }
                else -> reply(out, 404, "Not Found", "yok")
            }
            out.flush()
        } catch (e: Exception) {
            // bağlantı koptu; alıcı yeniden deneyecek
        } finally {
            try {
                sock.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var n = 0
        while (n < 16384) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            n++
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }

    private fun manifest(): String {
        val arr = JSONArray()
        files().forEachIndexed { i, f ->
            if (f != null) arr.put(JSONObject().put("i", i).put("n", f.name).put("s", f.size))
        }
        return JSONObject().put("files", arr).toString()
    }

    private fun reply(out: OutputStream, code: Int, msg: String, body: String, type: String = "text/plain") {
        val b = body.toByteArray(Charsets.UTF_8)
        val h = "HTTP/1.1 $code $msg\r\nContent-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n"
        out.write(h.toByteArray())
        out.write(b)
    }

    private fun sendFile(out: OutputStream, idx: Int, f: SendFile, start: Long) {
        if (start > f.size) {
            reply(out, 416, "Range Not Satisfiable", "aralik")
            return
        }
        val pfd = ctx.contentResolver.openFileDescriptor(f.uri, "r") ?: return
        pfd.use {
            val fis = FileInputStream(it.fileDescriptor)
            fis.channel.position(start)
            val len = f.size - start
            val status = if (start > 0) "206 Partial Content" else "200 OK"
            val sb = StringBuilder("HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\n")
            sb.append("Accept-Ranges: bytes\r\nContent-Length: $len\r\nConnection: close\r\n")
            if (start > 0) sb.append("Content-Range: bytes $start-${f.size - 1}/${f.size}\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray())
            val buf = ByteArray(1 shl 18)
            var sent = start
            var lastReport = 0L
            while (running) {
                val n = fis.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                sent += n
                val now = System.nanoTime()
                if (now - lastReport > 150_000_000L) {
                    lastReport = now
                    onProgress(idx, sent)
                }
            }
            out.flush()
            onProgress(idx, sent)
        }
    }
}
