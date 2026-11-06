package com.ultrafe.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.ppsspp.ppsspp.NativeApp
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Live numbers for the panel: the game's real frame rate (from the emulator core), this app's
 * CPU use, and GPU load where the phone lets an app read it.
 */
class StatSampler {
    var fps = -1f          // frames the game really drew in the last second
    var target = 0f        // frame rate the game is aiming for (30 or 60)
    var speed = 0f         // emulation speed in percent
    var cpu = -1           // percent of all CPU cores
    var gpu = -1           // percent, or -1 when the phone doesn't expose it

    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var lastWall = 0L
    private var lastCpu = 0L
    private var gpuPath: String? = null
    private var gpuProbed = false

    fun sample() {
        val st = try {
            NativeApp.heroStats()
        } catch (t: Throwable) {
            null
        }
        if (st != null && st.size >= 3) {
            speed = st[0] / 0.5994f
            target = st[1]
            fps = st[2]
        } else {
            fps = -1f
        }

        val wall = SystemClock.elapsedRealtime()
        val busy = Process.getElapsedCpuTime()
        if (lastWall != 0L && wall > lastWall) {
            val pct = (busy - lastCpu) * 100f / ((wall - lastWall) * cores)
            cpu = pct.toInt().coerceIn(0, 100)
        }
        lastWall = wall
        lastCpu = busy

        gpu = readGpu()
    }

    // GPU load files differ per chip maker. Android often blocks them for apps, so the first
    // readable one is used and the panel shows "--" when none is.
    private val gpuCandidates = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/kernel/ged/hal/gpu_utilization",
        "/sys/module/ged/parameters/gpu_loading",
        "/proc/mtk_mali/utilization",
        "/proc/gpufreq/gpufreq_var_dump",
        "/sys/class/misc/mali0/device/utilisation",
        "/sys/devices/platform/mali/utilization",
        "/sys/devices/platform/13000000.mali/utilization",
        "/sys/class/devfreq/13000000.mali/load",
        "/sys/class/devfreq/mali0/load"
    )

    private fun firstPercent(text: String): Int {
        // "34", "34 %", "34 34 34", "gpu_loading: 34" ...: take the first plain number.
        val m = Regex("""\d+""").find(text) ?: return -1
        val v = m.value.toIntOrNull() ?: return -1
        return if (v in 0..100) v else -1
    }

    private fun readGpu(): Int {
        if (!gpuProbed) {
            gpuProbed = true
            for (p in gpuCandidates) {
                val f = File(p)
                try {
                    if (f.canRead() && firstPercent(f.readText()) >= 0) {
                        gpuPath = p
                        break
                    }
                } catch (e: Exception) {
                    // Not readable on this phone.
                }
            }
        }
        val p = gpuPath ?: return -1
        return try {
            firstPercent(File(p).readText())
        } catch (e: Exception) {
            -1
        }
    }
}

/**
 * Small glass panel with FPS, CPU and GPU. In the game it can be dragged anywhere (the spot is
 * remembered) and a tap shrinks it to just the FPS number. [mock] shows fixed numbers for the
 * layout editor.
 */
class FpsPanel(
    context: Context,
    private val layout: PadLayout,
    private val mock: Boolean,
    private val interactive: Boolean = !mock
) : View(context) {

    private val d = resources.displayMetrics.density
    private val sampler = StatSampler()
    private val ui = Handler(Looper.getMainLooper())

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        color = Color.argb(110, 255, 95, 105)
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Nk.FONT
        textAlign = Paint.Align.CENTER
        color = Nk.ACCENT_HI
        letterSpacing = 0.12f
    }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Nk.FONT
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * d
        color = Color.argb(45, 255, 255, 255)
    }
    private val rect = RectF()

    /** Used by the editor to dim the panel when it is hidden. */
    var dimmed = false
        set(v) {
            field = v
            invalidate()
        }

    private val tick = object : Runnable {
        override fun run() {
            if (!mock) sampler.sample()
            invalidate()
            ui.postDelayed(this, 1000L)
        }
    }

    private val e: Elem get() = layout.get(PadLayout.FPS)
    private val scale: Float get() = e.s
    private val columns: Int get() = if (e.c) 1 else 3

    private fun colW() = 44f * d * scale
    private fun padW() = 9f * d * scale
    private fun panelH() = 36f * d * scale

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = (columns * colW() + padW() * 2f + 4f * d * scale).toInt()
        setMeasuredDimension(w, panelH().toInt())
    }

    /** Puts the panel's centre at its saved spot inside a parent of the given size. */
    fun place(parentW: Int, parentH: Int) {
        val w = measuredWidth.takeIf { it > 0 } ?: (columns * colW() + padW() * 2f).toInt()
        val h = measuredHeight.takeIf { it > 0 } ?: panelH().toInt()
        translationX = e.x * parentW - w / 2f - left
        translationY = e.y * parentH - h / 2f - top
    }

    fun refresh() {
        requestLayout()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ui.post(tick)
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- drag and tap

    private var downX = 0f
    private var downY = 0f
    private var startTx = 0f
    private var startTy = 0f
    private var moved = false
    private var onDragEnd: (() -> Unit)? = null

    /** Called after the player drags or taps the panel in game so the new state can be saved. */
    fun setOnChanged(cb: () -> Unit) {
        onDragEnd = cb
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!interactive) return false
        val parentView = parent as? View ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startTx = translationX
                startTy = translationY
                moved = false
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!moved && (abs(dx) > 6f * d || abs(dy) > 6f * d)) moved = true
                if (moved) {
                    val maxX = parentView.width - measuredWidth - left.toFloat()
                    val maxY = parentView.height - measuredHeight - top.toFloat()
                    translationX = (startTx + dx).coerceIn(-left.toFloat(), max(-left.toFloat(), maxX))
                    translationY = (startTy + dy).coerceIn(-top.toFloat(), max(-top.toFloat(), maxY))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    val pw = max(1, parentView.width)
                    val ph = max(1, parentView.height)
                    e.x = ((left + translationX + measuredWidth / 2f) / pw).coerceIn(0f, 1f)
                    e.y = ((top + translationY + measuredHeight / 2f) / ph).coerceIn(0f, 1f)
                } else {
                    e.c = !e.c
                    requestLayout()
                    parentView.post { place(parentView.width, parentView.height) }
                }
                onDragEnd?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    // ---------------------------------------------------------------- drawing

    private fun fpsColor(): Int {
        if (mock) return Color.WHITE
        val f = sampler.fps
        val t = sampler.target
        if (f < 0f || t <= 0f) return Color.WHITE
        return when {
            f < t * 0.6f -> Color.rgb(255, 90, 95)
            f < t * 0.88f -> Color.rgb(255, 176, 32)
            else -> Color.WHITE
        }
    }

    private fun loadColor(p: Int): Int = when {
        p >= 92 -> Color.rgb(255, 90, 95)
        p >= 80 -> Color.rgb(255, 176, 32)
        else -> Color.WHITE
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val k = scale
        val a = if (dimmed) 0.35f else 1f

        bg.color = Color.argb((190 * a).toInt(), 6, 7, 10)
        edge.alpha = (110 * a).toInt()
        rect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        val r = 10f * d * k
        c.drawRoundRect(rect, r, r, bg)
        c.drawRoundRect(rect, r, r, edge)

        // Orange spine on the left edge.
        accent.color = Nk.ACCENT
        accent.alpha = (255 * a).toInt()
        rect.set(0f, h * 0.22f, 2.4f * d * k, h * 0.78f)
        c.drawRoundRect(rect, 1.2f * d, 1.2f * d, accent)

        lab.textSize = 8f * d * k
        num.textSize = 14f * d * k

        val labY = h * 0.34f
        val numY = h * 0.80f
        var x = padW() + colW() / 2f + 2f * d * k

        val fpsText = if (mock) "60" else if (sampler.fps < 0f) "--" else sampler.fps.toInt().toString()
        column(c, x, labY, numY, "FPS", fpsText, fpsColor(), a)

        if (!e.c) {
            x += colW()
            c.drawLine(x - colW() / 2f, h * 0.24f, x - colW() / 2f, h * 0.76f, line)
            val cpuText = if (mock) "34%" else if (sampler.cpu < 0) "--" else "${sampler.cpu}%"
            column(c, x, labY, numY, "CPU", cpuText, if (mock || sampler.cpu < 0) Color.WHITE else loadColor(sampler.cpu), a)

            x += colW()
            c.drawLine(x - colW() / 2f, h * 0.24f, x - colW() / 2f, h * 0.76f, line)
            val gpuText = if (mock) "41%" else if (sampler.gpu < 0) "--" else "${sampler.gpu}%"
            column(c, x, labY, numY, "GPU", gpuText, if (mock || sampler.gpu < 0) Color.WHITE else loadColor(sampler.gpu), a)
        }
    }

    private fun column(c: Canvas, cx: Float, labY: Float, numY: Float, label: String, value: String, color: Int, a: Float) {
        lab.alpha = (255 * a).toInt()
        val lm = lab.fontMetrics
        c.drawText(label, cx, labY - (lm.ascent + lm.descent) / 2f, lab)
        num.color = color
        num.alpha = (255 * a).toInt()
        val nm = num.fontMetrics
        c.drawText(value, cx, numY - (nm.ascent + nm.descent) / 2f, num)
    }
}
