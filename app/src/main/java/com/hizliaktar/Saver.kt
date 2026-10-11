package com.hizliaktar

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Tamamlanan dosyayı İndirilenler/HizliAktar klasörüne taşır. */
object Saver {
    /** Android 9 ve altı: doğrudan İndirilenler/HizliAktar klasörüne yaz. */
    private fun saveLegacy(ctx: Context, part: File, name: String) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "HizliAktar"
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Klasör oluşturulamadı")
        var target = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        while (target.exists()) {
            target = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        FileInputStream(part).use { i -> FileOutputStream(target).use { o -> i.copyTo(o, 1 shl 18) } }
        MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), null, null)
    }

    fun save(ctx: Context, part: File, rawName: String) {
        val name = rawName.replace('/', '_').replace('\\', '_')
        if (Build.VERSION.SDK_INT < 29) {
            saveLegacy(ctx, part, name)
            return
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val r = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HizliAktar")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("Dosya oluşturulamadı")
        val os = r.openOutputStream(uri) ?: throw IOException("Dosya açılamadı")
        os.use { o -> FileInputStream(part).use { it.copyTo(o, 1 shl 18) } }
        v.clear()
        v.put(MediaStore.Downloads.IS_PENDING, 0)
        r.update(uri, v, null, null)
    }
}
