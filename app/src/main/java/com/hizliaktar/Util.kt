package com.hizliaktar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

object Util {
    fun fmtBytes(b: Long): String {
        val v = b.toDouble()
        return when {
            v >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", v / (1L shl 30))
            v >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", v / (1L shl 20))
            v >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", v / (1L shl 10))
            else -> "$b B"
        }
    }

    fun fmtSpeed(bps: Long): String = fmtBytes(bps) + "/sn"

    fun qr(text: String, size: Int = 720): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun describe(ctx: Context, uri: Uri): SendFile? {
        var name = "dosya"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            if (size < 0) {
                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            }
        } catch (e: Exception) {
            return null
        }
        return if (size < 0) null else SendFile(uri, name, size)
    }

    /** Cihazdaki tüm yerel IPv4 adresleri (wlan, hotspot, wifi-direct). */
    fun localIps(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) out.add(a.hostAddress!!)
                }
            }
        } catch (e: Exception) {
        }
        return out
    }
}
