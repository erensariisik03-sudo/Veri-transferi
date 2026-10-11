package com.hizliaktar

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator

class PoolActivity : Activity() {

    private val reqPick = 31
    private val reqPerm = 32

    private class Row(val label: TextView, val action: Button, val owner: String, val index: Int, val mine: Boolean)

    private lateinit var intro: TextView
    private lateinit var btnNew: Button
    private lateinit var btnScan: Button
    private lateinit var btnCode: Button
    private lateinit var codeText: TextView
    private lateinit var btnQr: Button
    private lateinit var qrView: ImageView
    private lateinit var btnAdd: Button
    private lateinit var btnLeave: Button
    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    private val rows = ArrayList<Row>()
    private var sig = ""
    private var shownKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)
        col.addView(Ui.text(this, "Ortak Havuz", 28f, Ui.TEXT, true))
        intro = Ui.text(
            this,
            "Aynı Wi-Fi/ağdaki cihazlar (telefon, tablet, bilgisayar) birbirinin paylaştığı dosyaları görür " +
                "ve dokunup indirir. Bir kez eşleşmen yeter. Yalnızca aynı ağda çalışır.",
            14f, Ui.MUTED
        )
        col.addView(intro)

        btnNew = Ui.button(this, "Yeni havuz oluştur") { createPool() }
        btnScan = Ui.button(this, "Havuza katıl (QR tara)", false) { scan() }
        btnCode = Ui.button(this, "Havuza katıl (kod yaz)", false) { askCode() }
        col.addView(btnNew)
        col.addView(btnScan)
        col.addView(btnCode)

        codeText = Ui.text(this, "", 22f, Ui.ACCENT, true)
        col.addView(codeText)
        btnQr = Ui.button(this, "Başka cihaz için QR göster / gizle", false) {
            Ui.show(qrView, qrView.visibility != android.view.View.VISIBLE)
        }
        col.addView(btnQr)
        qrView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            val size = Ui.dp(this@PoolActivity, 240)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                topMargin = Ui.dp(this@PoolActivity, 8)
            }
        }
        col.addView(qrView)

        btnAdd = Ui.button(this, "Dosya ekle (paylaş)") { pick() }
        col.addView(btnAdd)
        status = Ui.text(this, "", 14f, Ui.MUTED)
        col.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        btnLeave = Ui.button(this, "Havuzdan çık", false) { leave() }
        col.addView(btnLeave)
    }

    override fun onResume() {
        super.onResume()
        if (PoolKey.load(this) != null && PoolBus.engine == null) PoolService.start(this)
        PoolBus.listener = { render(it) }
        render(PoolBus.state)
    }

    override fun onPause() {
        PoolBus.listener = null
        super.onPause()
    }

    // ------------------------------------------------------------- eşleşme

    private fun joinKey(key: String) {
        PoolKey.save(this, key)
        shownKey = null
        PoolService.start(this)
        render(PoolBus.state)
    }

    private fun createPool() {
        askNotifPermission()
        joinKey(PoolKey.create())
    }

    private fun askNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), reqPerm)
        }
    }

    private fun scan() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), reqPerm + 1)
            return
        }
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Havuz QR'ını okut")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqPerm + 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) scan()
            else Toast.makeText(this, "QR için kamera izni gerekli.", Toast.LENGTH_LONG).show()
        }
    }

    private fun askCode() {
        val et = EditText(this)
        et.hint = "XXXXX-XXXXX"
        AlertDialog.Builder(this)
            .setTitle("Havuz kodu")
            .setView(et)
            .setPositiveButton("Katıl") { _, _ ->
                val key = PoolKey.parse(et.text.toString())
                if (key == null) Toast.makeText(this, "Geçersiz kod.", Toast.LENGTH_LONG).show()
                else joinKey(key)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun leave() {
        PoolKey.save(this, null)
        PoolService.stop(this)
        shownKey = null
        sig = ""
        list.removeAllViews()
        rows.clear()
        render(PoolState())
    }

    // ----------------------------------------------------------- dosya ekle

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, reqPick)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        val scanned = r?.contents
        if (scanned != null) {
            val key = PoolKey.parse(scanned)
            if (key == null) Toast.makeText(this, "Bu QR bir havuz kodu değil.", Toast.LENGTH_LONG).show()
            else joinKey(key)
            return
        }
        if (requestCode != reqPick || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        val files = ArrayList<SendFile>()
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            Util.describe(this, u)?.let { files.add(it) }
        }
        val e = PoolBus.engine
        if (e == null) Toast.makeText(this, "Havuz henüz hazır değil, birkaç saniye sonra dene.", Toast.LENGTH_LONG).show()
        else e.addFiles(files)
    }

    // -------------------------------------------------------------- çizim

    private fun small(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun rebuild(s: PoolState) {
        list.removeAllViews()
        rows.clear()
        fun header(t: String) = list.addView(Ui.text(this, t, 15f, Ui.ACCENT, true).apply {
            setPadding(0, Ui.dp(this@PoolActivity, 14), 0, Ui.dp(this@PoolActivity, 2))
        })

        fun addRow(f: PoolFile, mine: Boolean) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = Ui.text(this, f.name + "\n" + Util.fmtBytes(f.size), 14f, Ui.TEXT)
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val btn = small(if (mine) "Kaldır" else "İndir") {
                val e = PoolBus.engine
                if (e != null) {
                    if (mine) e.removeFile(f.index) else e.download(f.owner, f.index)
                }
            }
            line.addView(label)
            line.addView(btn)
            list.addView(line)
            rows.add(Row(label, btn, f.owner, f.index, mine))
        }
        if (s.mine.isNotEmpty()) {
            header("Benim paylaştıklarım (${s.mine.size})")
            s.mine.forEach { addRow(it, true) }
        }
        for (d in s.devices) {
            header("${d.name}  (${d.files.size} dosya)")
            d.files.forEach { addRow(it, false) }
        }
    }

    private fun render(s: PoolState) {
        val key = PoolKey.load(this)
        val joined = key != null
        Ui.show(btnNew, !joined)
        Ui.show(btnScan, !joined)
        Ui.show(btnCode, !joined)
        Ui.show(codeText, joined)
        Ui.show(btnQr, joined)
        Ui.show(btnAdd, joined)
        Ui.show(btnLeave, joined)
        Ui.show(status, joined)
        if (!joined) {
            Ui.show(qrView, false)
            return
        }
        if (key != shownKey) {
            shownKey = key
            codeText.text = "Kod: " + PoolKey.pretty(key!!)
            qrView.setImageBitmap(Util.qr(PoolKey.link(key), 600))
            Ui.show(qrView, true)
        }
        val n = s.devices.size
        val sp = if (s.speedBps > 0) " · " + Util.fmtSpeed(s.speedBps) else ""
        status.text = when {
            !s.running -> "Havuz açılıyor…"
            n == 0 -> "Havuz açık · başka cihaz görünmüyor (aynı ağda mısınız?)$sp"
            else -> "Havuz açık · $n cihaz bağlı$sp"
        } + (if (s.message.isNotEmpty()) " · " + s.message else "")

        val newSig = buildString {
            s.mine.forEach { append("m").append(it.index).append(';') }
            s.devices.forEach { d ->
                append(d.id).append(':')
                d.files.forEach { append(it.index).append(',') }
                append(';')
            }
        }
        if (newSig != sig) {
            sig = newSig
            rebuild(s)
        }
        for (r in rows) {
            if (r.mine) continue
            val st = s.dl[r.owner + ":" + r.index]
            when (st?.status) {
                null -> {
                    r.action.text = "İndir"
                    r.action.isEnabled = true
                }
                "downloading" -> {
                    val p = if (st.size > 0) (st.done * 100 / st.size).toInt() else 100
                    r.action.text = "%$p"
                    r.action.isEnabled = false
                }
                "done" -> {
                    r.action.text = "✓ İndirildi"
                    r.action.isEnabled = false
                }
                else -> {
                    r.action.text = "Tekrar dene"
                    r.action.isEnabled = true
                }
            }
        }
    }
}
