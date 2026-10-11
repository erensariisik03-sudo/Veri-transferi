package com.hizliaktar

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button

class MainActivity : Activity() {

    private lateinit var resume: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)
        col.addView(Ui.text(this, "Hızlı Aktar", 32f, Ui.TEXT, true))
        col.addView(
            Ui.text(
                this,
                "Telefondan telefona dosya aktar. IP yok, QR okut yeter. " +
                    "Cihazlar destekliyorsa 5 GHz Wi-Fi, yoksa 2.4 GHz kullanılır.",
                15f, Ui.MUTED
            )
        )
        col.addView(Ui.button(this, "Gönder") {
            startActivity(Intent(this, SendActivity::class.java))
        })
        col.addView(Ui.button(this, "Al (QR tara)", false) {
            startActivity(Intent(this, ReceiveActivity::class.java))
        })
        resume = Ui.button(this, "Devam eden aktarımı göster", false) {
            val s = Bus.state
            startActivity(
                Intent(this, if (s.sender) SendActivity::class.java else ReceiveActivity::class.java)
            )
        }
        col.addView(resume)
    }

    override fun onResume() {
        super.onResume()
        val p = Bus.state.phase
        val active = p != Phase.IDLE && p != Phase.ERROR && p != Phase.DONE
        Ui.show(resume, active)
    }
}
