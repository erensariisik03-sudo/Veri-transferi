package com.hizliaktar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

object Ui {
    val BG = 0xFF0F172A.toInt()
    val CARD = 0xFF1E293B.toInt()
    val ACCENT = 0xFF22D3EE.toInt()
    val TEXT = 0xFFE2E8F0.toInt()
    val MUTED = 0xFF94A3B8.toInt()
    val ERR = 0xFFF87171.toInt()
    val OK = 0xFF4ADE80.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** Tam ekran kaydırılabilir sütun; içeriği Activity'e koyar. */
    fun screen(a: Activity): LinearLayout {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(a, 20)
            setPadding(p, dp(a, 28), p, p)
        }
        val sv = ScrollView(a).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(col)
        }
        a.setContentView(sv)
        return col
    }

    fun text(ctx: Context, s: String, size: Float = 16f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }

    fun button(ctx: Context, s: String, primary: Boolean = true, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = s
            isAllCaps = false
            textSize = 17f
            setTextColor(if (primary) BG else TEXT)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(if (primary) ACCENT else CARD)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 56)
            )
            lp.topMargin = dp(ctx, 12)
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    fun bar(ctx: Context, max: Int, heightDp: Int): ProgressBar =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = max
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)
            )
        }

    fun show(v: View, on: Boolean) {
        v.visibility = if (on) View.VISIBLE else View.GONE
    }
}

object Perms {
    /** Wi-Fi Direct / ağa katılma için gerekli izin. */
    fun wifi(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    fun all(core: List<String>): Array<String> {
        val l = ArrayList(core)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.toTypedArray()
    }

    fun missing(a: Activity, list: Array<String>): Array<String> =
        list.filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    fun coreOk(a: Activity, core: List<String>): Boolean =
        core.all { a.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

/** Yüzde, hız ve dosya başına ilerleme gösteren ortak panel. */
class TransferPanel(private val ctx: Context) {
    val view = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val status = Ui.text(ctx, "", 15f, Ui.MUTED)
    private val pct = Ui.text(ctx, "%0", 34f, Ui.ACCENT, true)
    private val bar = Ui.bar(ctx, 1000, 10)
    private val sub = Ui.text(ctx, "", 14f, Ui.MUTED)
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val labels = ArrayList<TextView>()
    private val bars = ArrayList<ProgressBar>()

    init {
        view.addView(status)
        view.addView(pct)
        view.addView(bar)
        view.addView(sub)
        val gap = View(ctx)
        gap.layoutParams = LinearLayout.LayoutParams(1, Ui.dp(ctx, 12))
        view.addView(gap)
        view.addView(list)
    }

    fun render(s: UiState) {
        status.text = s.message
        status.setTextColor(
            when (s.phase) {
                Phase.ERROR -> Ui.ERR
                Phase.DONE -> Ui.OK
                else -> Ui.MUTED
            }
        )
        pct.text = "%" + s.percent
        bar.progress = if (s.total > 0) ((s.done * 1000) / s.total).toInt() else 0
        sub.text = Util.fmtBytes(s.done) + " / " + Util.fmtBytes(s.total) +
            "   ·   " + Util.fmtSpeed(s.speedBps)
        if (labels.size != s.files.size) rebuild(s.files.size)
        s.files.forEachIndexed { i, f ->
            val p = if (f.size > 0) ((f.done * 100) / f.size).toInt() else 100
            labels[i].text = f.name + "  ·  %" + p
            bars[i].progress = p * 10
        }
    }

    private fun rebuild(n: Int) {
        list.removeAllViews()
        labels.clear()
        bars.clear()
        for (i in 0 until n) {
            val t = Ui.text(ctx, "", 13f, Ui.TEXT)
            t.maxLines = 1
            t.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            val b = Ui.bar(ctx, 1000, 4)
            labels.add(t)
            bars.add(b)
            list.addView(t)
            list.addView(b)
        }
    }
}
