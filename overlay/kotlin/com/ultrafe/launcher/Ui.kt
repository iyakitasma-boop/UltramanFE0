package com.ultrafe.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Palette. The screens are built in code so the overlay stays a handful of files. */
object Ni {
    val BG = Color.parseColor("#050807")
    val SURFACE = Color.parseColor("#0E1320")
    val SURFACE_ALT = Color.parseColor("#161D2C")
    val ACCENT = Color.parseColor("#E8283A")
    val ACCENT_DARK = Color.parseColor("#B01524")
    val TEXT = Color.parseColor("#F2F4FA")
    val TEXT_DIM = Color.parseColor("#9AA1B8")
    val OK = Color.parseColor("#3DDC97")
    val WARN = Color.parseColor("#FFB020")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun shape(color: Int, radiusPx: Float): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = radiusPx
    return d
}

fun ripple(base: Drawable): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)

fun Context.label(text: CharSequence, sp: Float = 15f, color: Int = Ni.TEXT, bold: Boolean = false): TextView {
    val tv = TextView(this)
    tv.text = text
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    tv.setTextColor(color)
    if (bold) tv.typeface = Typeface.DEFAULT_BOLD
    return tv
}

fun Context.vbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    return l
}

fun Context.hbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER_VERTICAL
    return l
}

/** Adds [child] full-width with margins given in dp. */
fun LinearLayout.addv(child: View, top: Int = 0, bottom: Int = 0) {
    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
    lp.setMargins(0, context.dp(top), 0, context.dp(bottom))
    addView(child, lp)
}

fun Context.card(): LinearLayout {
    val c = vbox()
    c.setPadding(dp(18), dp(16), dp(18), dp(16))
    c.background = shape(Ni.SURFACE, dp(20).toFloat())
    return c
}

fun Context.sectionTitle(t: String): TextView {
    val tv = label(t.uppercase(), 12f, Ni.ACCENT, true)
    tv.letterSpacing = 0.12f
    return tv
}

fun Context.button(text: String, primary: Boolean, onClick: () -> Unit): TextView {
    val b = label(text, 15f, if (primary) Color.WHITE else Ni.TEXT, true)
    b.gravity = Gravity.CENTER
    b.setPadding(dp(18), dp(14), dp(18), dp(14))
    val base = shape(if (primary) Ni.ACCENT else Ni.SURFACE_ALT, dp(16).toFloat())
    b.background = ripple(base)
    b.isClickable = true
    b.isFocusable = true
    b.setOnClickListener { onClick() }
    return b
}

fun Context.pill(text: String, color: Int): TextView {
    val p = label(text, 11f, color, true)
    p.setPadding(dp(10), dp(4), dp(10), dp(4))
    p.background = shape((color and 0x00FFFFFF) or 0x26000000, dp(20).toFloat())
    return p
}

fun Context.switchRow(
    title: String,
    sub: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
): View {
    val row = hbox()
    row.setPadding(0, dp(8), 0, dp(8))

    val texts = vbox()
    texts.addView(label(title, 15f))
    if (sub != null) {
        val s = label(sub, 12f, Ni.TEXT_DIM)
        s.setPadding(0, dp(2), dp(12), 0)
        texts.addView(s)
    }
    row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    val sw = SwitchCompat(this)
    sw.isChecked = checked
    sw.isEnabled = enabled
    sw.thumbTintList = ColorStateList(states, intArrayOf(Ni.ACCENT, Ni.TEXT_DIM))
    sw.trackTintList = ColorStateList(states, intArrayOf(Ni.ACCENT_DARK, Ni.SURFACE_ALT))
    sw.setOnCheckedChangeListener { _, v -> onChange(v) }
    row.addView(sw)
    return row
}

fun Context.sliderRow(
    title: String,
    lo: Int,
    hi: Int,
    value: Int,
    fmt: (Int) -> String,
    onChange: (Int) -> Unit
): View {
    val box = vbox()
    box.setPadding(0, dp(8), 0, dp(4))

    val head = hbox()
    head.addView(label(title, 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
    val valueLabel = label(fmt(value), 14f, Ni.ACCENT, true)
    head.addView(valueLabel)
    box.addView(head)

    val sb = SeekBar(this)
    sb.max = hi - lo
    sb.progress = value - lo
    sb.progressTintList = ColorStateList.valueOf(Ni.ACCENT)
    sb.thumbTintList = ColorStateList.valueOf(Ni.ACCENT)
    sb.progressBackgroundTintList = ColorStateList.valueOf(Ni.SURFACE_ALT)
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
            val v = progress + lo
            valueLabel.text = fmt(v)
            if (fromUser) onChange(v)
        }

        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    box.addView(sb, LinearLayout.LayoutParams(MATCH, WRAP))
    return box
}

/** Segmented choice grid. [selected] may be -1 for "none highlighted". */
fun Context.chips(options: List<String>, selected: Int, columns: Int, onSelect: (Int) -> Unit): View {
    val box = vbox()
    var i = 0
    while (i < options.size) {
        val row = hbox()
        for (c in 0 until columns) {
            val idx = i + c
            if (idx >= options.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                continue
            }
            val on = idx == selected
            val chip = label(options[idx], 13f, if (on) Color.WHITE else Ni.TEXT_DIM, on)
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(4), dp(11), dp(4), dp(11))
            chip.background = ripple(shape(if (on) Ni.ACCENT else Ni.SURFACE_ALT, dp(12).toFloat()))
            chip.isClickable = true
            chip.isFocusable = true
            chip.setOnClickListener { onSelect(idx) }
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            row.addView(chip, lp)
        }
        box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        i += columns
    }
    return box
}

/** Keeps content clear of the status bar, navigation bar and display cutout (edge-to-edge). */
fun View.applyInsetsPadding() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(this)
}
