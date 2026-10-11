package com.hizliaktar

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest

data class PoolFile(val owner: String, val index: Int, val name: String, val size: Long)

data class PoolDevice(val id: String, val name: String, val ip: String, val files: List<PoolFile>)

/** status: "downloading" | "done" | "error" */
data class DlState(val done: Long, val size: Long, val status: String, val note: String = "")

data class PoolState(
    val running: Boolean = false,
    val message: String = "",
    val mine: List<PoolFile> = emptyList(),
    val devices: List<PoolDevice> = emptyList(),
    val dl: Map<String, DlState> = emptyMap(),
    val speedBps: Long = 0
)

/** Havuz servisi ile ekran arasındaki köprü. */
object PoolBus {
    @Volatile
    var state = PoolState()
        private set

    @Volatile
    var listener: ((PoolState) -> Unit)? = null

    @Volatile
    var engine: PoolEngine? = null

    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun set(s: PoolState) {
        state = s
        main.post { listener?.invoke(s) }
    }
}

/** Havuz anahtarı: bir kez eşleşince cihazda saklanır. Windows uygulamasıyla aynı kurallar. */
object PoolKey {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PREFS = "hizli"

    fun create(): String = (1..10).map { ALPHABET.random() }.joinToString("")

    private fun normalize(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    fun pretty(k: String) = k.substring(0, 5) + "-" + k.substring(5)

    fun link(k: String) = "hizli://pool?k=$k"

    /** QR/bağlantı ya da elle yazılan koddan anahtarı çıkarır; geçersizse null. */
    fun parse(text: String): String? {
        val t = text.trim()
        if (t.startsWith("hizli://pool")) {
            val k = Uri.parse(t).getQueryParameter("k")?.let { normalize(it) }
            return if (k != null && k.length == 10) k else null
        }
        val k = normalize(t)
        return if (k.length == 10 && k.all { it in ALPHABET }) k else null
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun pid(k: String) = sha("pid:$k").substring(0, 16)

    fun tok(k: String) = sha("tok:$k").substring(0, 24)

    fun load(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pool_key", null)

    fun save(ctx: Context, key: String?) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit().putString("pool_key", key)
        // Havuzdan çıkınca paylaşılan dosya listesi de silinir.
        if (key == null) p.all.keys.filter { it.startsWith("pool_files_") }.forEach { e.remove(it) }
        e.apply()
    }

    fun deviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = p.getString("device_id", null)
        if (id == null) {
            id = (1..8).map { "0123456789abcdef".random() }.joinToString("")
            p.edit().putString("device_id", id).apply()
        }
        return id
    }
}
