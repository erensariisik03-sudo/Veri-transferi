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

/** Tamamlanan dosyayı İndirilenler/HizliAktar klasörüne taşır; konumu döndürür. */
object Saver {

    private const val PUBLIC_LOCATION = "İndirilenler/HizliAktar"

    private fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        while (target.exists()) {
            target = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return target
    }

    private fun copyInto(part: File, dir: File, name: String): File {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Klasör oluşturulamadı")
        val target = uniqueTarget(dir, name)
        FileInputStream(part).use { i -> FileOutputStream(target).use { o -> i.copyTo(o, 1 shl 18) } }
        return target
    }

    /**
     * Android 9 ve altı: İndirilenler/HizliAktar'a yaz. Depolama izni yoksa dosya
     * kaybolmasın diye uygulamanın kendi klasörüne kaydeder.
     */
    private fun saveLegacy(ctx: Context, part: File, name: String): String {
        try {
            val pub = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "HizliAktar"
            )
            val t = copyInto(part, pub, name)
            MediaScannerConnection.scanFile(ctx, arrayOf(t.absolutePath), null, null)
            return PUBLIC_LOCATION
        } catch (e: Exception) {
            val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            val t = copyInto(part, File(base, "HizliAktar"), name)
            return t.parentFile?.absolutePath + " (depolama izni yok)"
        }
    }

    fun save(ctx: Context, part: File, rawName: String): String {
        val name = rawName.replace('/', '_').replace('\\', '_')
        if (Build.VERSION.SDK_INT < 29) return saveLegacy(ctx, part, name)
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
        return PUBLIC_LOCATION
    }
}
