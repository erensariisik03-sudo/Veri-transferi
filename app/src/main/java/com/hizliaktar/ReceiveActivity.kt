package com.hizliaktar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator

class ReceiveActivity : Activity() {

    private val reqPerm = 21

    private lateinit var info: TextView
    private lateinit var btnScan: Button
    private lateinit var btnStop: Button
    private lateinit var panel: TransferPanel

    private val core get() = listOf(Perms.wifi(), Manifest.permission.CAMERA) + Perms.storage()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)
        col.addView(Ui.text(this, "Al", 28f, Ui.TEXT, true))
        info = Ui.text(
            this,
            "Gönderen telefonda \"Gönder\" ekranındaki QR'ı okut. Wi-Fi açık olsun.",
            15f, Ui.MUTED
        )
        col.addView(info)
        btnScan = Ui.button(this, "QR tara") { scan() }
        col.addView(btnScan)
        panel = TransferPanel(this)
        panel.view.setPadding(0, Ui.dp(this, 16), 0, 0)
        col.addView(panel.view)
        btnStop = Ui.button(this, "İptal", false) {
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

    private fun scan() {
        val miss = Perms.missing(this, Perms.all(core))
        if (miss.isEmpty()) openScanner() else requestPermissions(miss, reqPerm)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqPerm) return
        if (Perms.coreOk(this, core)) openScanner()
        else Toast.makeText(this, "Kamera ve Wi-Fi/Yakındaki cihazlar izni gerekli.", Toast.LENGTH_LONG).show()
    }

    private fun openScanner() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Gönderen ekrandaki QR'ı okut")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data) ?: return
        val text = r.contents ?: return
        if (Link.parse(text) == null) {
            Toast.makeText(this, "Bu QR Hızlı Aktar koduna benzemiyor.", Toast.LENGTH_LONG).show()
            return
        }
        Bus.pendingLink = text
        TransferService.start(this, TransferService.ACTION_RECEIVE)
    }

    private fun render(raw: UiState) {
        val s = if (!raw.sender) raw else UiState()
        val active = s.phase != Phase.IDLE && s.phase != Phase.ERROR && s.phase != Phase.DONE
        Ui.show(btnScan, !active)
        Ui.show(btnStop, active)
        val showPanel = s.phase != Phase.IDLE
        Ui.show(panel.view, showPanel)
        if (showPanel) panel.render(s)
    }
}
