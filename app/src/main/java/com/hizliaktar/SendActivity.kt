package com.hizliaktar

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SendActivity : Activity() {

    private val reqPick = 11
    private val reqPerm = 12

    private var picked: List<SendFile> = emptyList()
    private var shownQr: String? = null

    private lateinit var info: TextView
    private lateinit var btnPick: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var qrView: ImageView
    private lateinit var qrHint: TextView
    private lateinit var panel: TransferPanel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)
        col.addView(Ui.text(this, "Gönder", 28f, Ui.TEXT, true))
        info = Ui.text(this, "Henüz dosya seçilmedi.", 15f, Ui.MUTED)
        col.addView(info)
        btnPick = Ui.button(this, "Dosya seç (istediğin kadar)", false) { pick() }
        col.addView(btnPick)
        btnStart = Ui.button(this, "Bağlantıyı başlat") { startSend() }
        col.addView(btnStart)

        qrHint = Ui.text(this, "Alıcı telefonda \"Al\" ekranından bu QR'ı okutsun.", 14f, Ui.MUTED)
        col.addView(qrHint)
        qrView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            val size = Ui.dp(this@SendActivity, 280)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { topMargin = Ui.dp(this@SendActivity, 8) }
        }
        col.addView(qrView)

        panel = TransferPanel(this)
        panel.view.setPadding(0, Ui.dp(this, 16), 0, 0)
        col.addView(panel.view)

        btnStop = Ui.button(this, "Durdur", false) {
            startService(Intent(this, TransferService::class.java).setAction(TransferService.ACTION_STOP))
        }
        col.addView(btnStop)
        render(Bus.state)
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { render(it) }
        render(Bus.state)
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    // ----------------------------------------------------------- dosya seç

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
        if (requestCode != reqPick || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        val list = ArrayList<SendFile>()
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            Util.describe(this, u)?.let { list.add(it) }
        }
        picked = list
        val total = list.sumOf { it.size }
        info.text = "${list.size} dosya seçildi · ${Util.fmtBytes(total)}"
    }

    // ------------------------------------------------------------- başlat

    private fun startSend() {
        if (picked.isEmpty()) {
            Toast.makeText(this, "Önce dosya seç.", Toast.LENGTH_SHORT).show()
            return
        }
        val core = listOf(Perms.wifi())
        val miss = Perms.missing(this, Perms.all(core))
        if (miss.isEmpty()) launch() else requestPermissions(miss, reqPerm)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqPerm) return
        if (Perms.coreOk(this, listOf(Perms.wifi()))) launch()
        else Toast.makeText(this, "Wi-Fi/Yakındaki cihazlar izni gerekli.", Toast.LENGTH_LONG).show()
    }

    private fun launch() {
        Bus.pendingSend = picked
        shownQr = null
        TransferService.start(this, TransferService.ACTION_SEND)
    }

    // ------------------------------------------------------------- çizim

    private fun render(raw: UiState) {
        val s = if (raw.sender) raw else UiState()
        val active = s.phase != Phase.IDLE && s.phase != Phase.ERROR
        Ui.show(btnPick, !active)
        Ui.show(btnStart, !active)
        Ui.show(btnStop, active)
        val qr = s.qrPayload
        Ui.show(qrView, active && qr != null)
        Ui.show(qrHint, active && qr != null)
        if (qr != null && qr != shownQr) {
            shownQr = qr
            qrView.setImageBitmap(Util.qr(qr))
        }
        val showPanel = s.files.isNotEmpty() && s.phase != Phase.IDLE
        Ui.show(panel.view, showPanel)
        if (showPanel) panel.render(s)
        if (s.phase == Phase.ERROR && s.files.isEmpty()) {
            info.text = s.message
        }
    }
}
