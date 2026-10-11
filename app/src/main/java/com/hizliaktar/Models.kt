package com.hizliaktar

import android.net.Uri
import android.os.Handler
import android.os.Looper

data class SendFile(val uri: Uri, val name: String, val size: Long)

data class FileRow(val name: String, val size: Long, val done: Long)

enum class Phase { IDLE, STARTING, READY, CONNECTING, TRANSFERRING, RECONNECTING, DONE, ERROR }

data class UiState(
    val sender: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val files: List<FileRow> = emptyList(),
    val speedBps: Long = 0,
    val qrPayload: String? = null,
    val band: String = ""
) {
    val total: Long get() = files.sumOf { it.size }
    val done: Long get() = files.sumOf { it.done }
    val percent: Int
        get() = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
}

/** Servis ile arayüz arasındaki basit köprü. */
object Bus {
    @Volatile
    var state = UiState()
        private set

    @Volatile
    var listener: ((UiState) -> Unit)? = null

    @Volatile
    var pendingSend: List<SendFile> = emptyList()

    @Volatile
    var pendingLink: String? = null

    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun update(f: (UiState) -> UiState) {
        val s = f(state)
        state = s
        main.post { listener?.invoke(s) }
    }

    fun reset() {
        update { UiState() }
    }
}

/** QR içinde taşınan bağlantı bilgisi. */
data class Link(
    val ssid: String?,
    val pass: String?,
    val hosts: List<String>,
    val port: Int,
    val token: String
) {
    fun encode(): String {
        val b = Uri.Builder().scheme("hizli").authority("c")
        if (ssid != null && pass != null) {
            b.appendQueryParameter("s", ssid)
            b.appendQueryParameter("p", pass)
        }
        b.appendQueryParameter("h", hosts.joinToString(","))
        b.appendQueryParameter("t", port.toString())
        b.appendQueryParameter("k", token)
        return b.build().toString()
    }

    companion object {
        fun parse(text: String): Link? {
            return try {
                val u = Uri.parse(text.trim())
                if (u.scheme != "hizli") return null
                val hosts = (u.getQueryParameter("h") ?: "").split(",").filter { it.isNotBlank() }
                val port = u.getQueryParameter("t")?.toInt() ?: return null
                val token = u.getQueryParameter("k") ?: return null
                if (hosts.isEmpty()) return null
                Link(u.getQueryParameter("s"), u.getQueryParameter("p"), hosts, port, token)
            } catch (e: Exception) {
                null
            }
        }
    }
}
