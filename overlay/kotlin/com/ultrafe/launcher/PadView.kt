package com.ultrafe.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.ppsspp.ppsspp.NativeApp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Sends our virtual controller to the emulator as "pad 0", the same path a Bluetooth pad uses. */
private object PadNative {
    private val axisIds = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
    private val axisValues = FloatArray(2)

    fun key(code: Int, down: Boolean) {
        try {
            if (down) NativeApp.keyDown(NativeApp.DEVICE_ID_PAD_0, code, false)
            else NativeApp.keyUp(NativeApp.DEVICE_ID_PAD_0, code)
        } catch (t: Throwable) {
            // Never let input plumbing take the game down.
        }
    }

    fun stick(x: Float, y: Float) {
        axisValues[0] = x
        axisValues[1] = y
        try {
            NativeApp.joystickAxis(NativeApp.DEVICE_ID_PAD_0, axisIds, axisValues, 2)
        } catch (t: Throwable) {
            // See above.
        }
    }
}

/**
 * Fighting-game controller drawn over the game: a glowing movement ring on the left, colour-timer
 * buttons (a ring of light around a glass disc) for attack, strong attack, jump and guard on the
 * right, a gem-shaped beam button, and slim dash, select and pause bars on top. Every control sits where [layout] says and presses the game button
 * the layout says, so the editor and the game share this one view. Touches that miss every
 * control fall through to the game.
 *
 * Custom art: put PNG files in assets/ui/icons/ (see the README there). "<id>.png" replaces
 * the symbol on a control, "<id>_full.png" replaces the whole control.
 *
 * With [edit] set it only draws (no input is sent) and marks the selected control.
 */
class PadView(
    context: Context,
    prefs: Prefs,
    private val layout: PadLayout,
    private val edit: Boolean,
    private val onStuckAtMenu: () -> Unit
) : View(context) {

    private companion object {
        const val SHAPE_DISC = 0
        const val SHAPE_PILL = 1
        const val SHAPE_GEM = 2

        const val DIR_UP = 1
        const val DIR_DOWN = 2
        const val DIR_LEFT = 4
        const val DIR_RIGHT = 8

        const val POLL_MS = 500L
        const val STUCK_POLLS = 8

        // Normal sizes in dp (half width / half height), before the per-control size multiplier.
        const val STICK_R = 60f
        const val DPAD_R = 40f
    }

    private class Btn(val id: String, val shape: Int, val baseHw: Float, val baseHh: Float) {
        var cx = 0f
        var cy = 0f
        var hw = 0f
        var hh = 0f
        var shown = true
        var pointer = -1
        var sentKey = 0
        val pressed: Boolean get() = pointer != -1
    }

    private val d = resources.displayMetrics.density
    private var opacity = prefs.touchOpacity.coerceIn(5, 100) / 100f
    private var words = prefs.padStyle == 1
    private var haptics = prefs.haptics && !edit

    private val ui = Handler(Looper.getMainLooper())
    private val btns = ArrayList<Btn>()

    /** Id of the control to mark in the editor, or null. */
    var selected: String? = null
        set(v) {
            field = v
            invalidate()
        }

    // Movement ring
    private var stickShown = true
    private var stickCx = 0f
    private var stickCy = 0f
    private var stickR = 0f
    private var stickPointer = -1
    private var knobX = 0f
    private var knobY = 0f
    private var stickMask = 0

    // Menu pad
    private var dpadShown = true
    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadR = 0f
    private var dpadPointer = -1
    private var dpadMask = 0

    private var dim = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        strokeJoin = Paint.Join.ROUND
    }
    private val sym = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Nk.FONT
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = Color.rgb(120, 205, 255)
        pathEffect = DashPathEffect(floatArrayOf(7f * d, 4f * d), 0f)
    }
    private val rect = RectF()
    private val rect2 = RectF()
    private val path = Path()
    private val glyph = Path()
    private val chev = chevronPath()
    private val burst = starPath(0f, 0f, 1f, 0.5f, 8, -90f)
    private val mtx = Matrix()
    private val dots = DashPathEffect(floatArrayOf(2f * d, 7f * d), 0f)

    private val iconCache = HashMap<String, Bitmap>()
    private val iconMissing = HashSet<String>()


    private var stuckCount = 0
    private val poll = object : Runnable {
        override fun run() {
            val atMenu = try {
                NativeApp.isAtTopLevel()
            } catch (t: Throwable) {
                false
            }
            if (atMenu) stuckCount++ else stuckCount = 0
            // PPSSPP's own game browser is showing: hide the pad so its menu can be used.
            visibility = if (stuckCount >= 2) INVISIBLE else VISIBLE
            if (stuckCount == STUCK_POLLS) onStuckAtMenu()
            ui.postDelayed(this, POLL_MS)
        }
    }

    init {
        btns.add(Btn(PadLayout.ATTACK, SHAPE_DISC, 38f, 38f))
        btns.add(Btn(PadLayout.STRONG, SHAPE_DISC, 29f, 29f))
        btns.add(Btn(PadLayout.JUMP, SHAPE_DISC, 29f, 29f))
        btns.add(Btn(PadLayout.GUARD, SHAPE_DISC, 27f, 27f))
        btns.add(Btn(PadLayout.BEAM, SHAPE_GEM, 25f, 34f))
        btns.add(Btn(PadLayout.DASH, SHAPE_PILL, 44f, 17f))
        btns.add(Btn(PadLayout.SELECT, SHAPE_PILL, 32f, 14f))
        btns.add(Btn(PadLayout.PAUSE, SHAPE_PILL, 32f, 14f))
    }

    /** Re-reads look settings (editor changes them live). */
    fun applyPrefs(prefs: Prefs) {
        opacity = prefs.touchOpacity.coerceIn(5, 100) / 100f
        words = prefs.padStyle == 1
        invalidate()
    }

    /** Call after the layout object was changed. */
    fun refresh() {
        layoutControls(width, height)
        invalidate()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Give the game time to boot before we start asking it where it is.
        if (!edit) ui.postDelayed(poll, 4000L)
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(poll)
        releaseAll()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            releaseAll()
            invalidate()
        }
    }

    // ---------------------------------------------------------------- layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutControls(w, h)
    }

    private fun layoutControls(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        for (b in btns) {
            val e = layout.get(b.id)
            b.cx = e.x * w
            b.cy = e.y * h
            b.hw = b.baseHw * d * e.s
            b.hh = b.baseHh * d * e.s
            b.shown = e.v
        }
        val st = layout.get(PadLayout.STICK)
        stickCx = st.x * w
        stickCy = st.y * h
        stickR = STICK_R * d * st.s
        stickShown = st.v

        val dp = layout.get(PadLayout.DPAD)
        dpadCx = dp.x * w
        dpadCy = dp.y * h
        dpadR = DPAD_R * d * dp.s
        dpadShown = dp.v
    }

    // ---------------------------------------------------------------- editor helpers

    /** The control under this point (hidden ones included), topmost first, or null. */
    fun elementAt(x: Float, y: Float): String? {
        for (b in btns.reversed()) if (inButton(b, x, y)) return b.id
        if (hypot(x - dpadCx, y - dpadCy) <= dpadR * 1.1f) return PadLayout.DPAD
        if (hypot(x - stickCx, y - stickCy) <= stickR * 1.1f) return PadLayout.STICK
        return null
    }

    // ---------------------------------------------------------------- input

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (edit) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val hit = handleDown(e.getPointerId(i), e.getX(i), e.getY(i))
                // false lets the touch fall through to the game underneath.
                return hit || anyActive()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    handleMove(e.getPointerId(i), e.getX(i), e.getY(i))
                }
                return anyActive()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handleUp(e.getPointerId(e.actionIndex))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
                invalidate()
                return false
            }
        }
        return false
    }

    private fun anyActive(): Boolean =
        stickPointer != -1 || dpadPointer != -1 || btns.any { it.pointer != -1 }

    private fun feedback() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun inButton(b: Btn, x: Float, y: Float): Boolean {
        val slop = 6f * d
        return if (b.shape == SHAPE_DISC) {
            hypot(x - b.cx, y - b.cy) <= b.hw + slop
        } else {
            x >= b.cx - b.hw - slop && x <= b.cx + b.hw + slop &&
                y >= b.cy - b.hh - slop && y <= b.cy + b.hh + slop
        }
    }

    private fun handleDown(id: Int, x: Float, y: Float): Boolean {
        for (b in btns) {
            if (b.shown && b.pointer == -1 && inButton(b, x, y)) {
                b.pointer = id
                b.sentKey = PadLayout.KEY_CODES[PadLayout.keyIndex(layout, b.id)]
                PadNative.key(b.sentKey, true)
                feedback()
                invalidate()
                return true
            }
        }
        if (dpadShown && dpadPointer == -1 && hypot(x - dpadCx, y - dpadCy) <= dpadR * 1.3f) {
            dpadPointer = id
            applyDpad(maskFor(x - dpadCx, y - dpadCy, dpadR * 0.3f))
            feedback()
            invalidate()
            return true
        }
        if (stickShown && stickPointer == -1 && hypot(x - stickCx, y - stickCy) <= stickR * 1.4f) {
            stickPointer = id
            updateStick(x, y)
            feedback()
            invalidate()
            return true
        }
        return false
    }

    private fun handleMove(id: Int, x: Float, y: Float) {
        if (id == stickPointer) {
            updateStick(x, y)
            invalidate()
        } else if (id == dpadPointer) {
            applyDpad(maskFor(x - dpadCx, y - dpadCy, dpadR * 0.3f))
            invalidate()
        }
    }

    private fun handleUp(id: Int) {
        for (b in btns) {
            if (b.pointer == id) {
                b.pointer = -1
                PadNative.key(b.sentKey, false)
            }
        }
        if (id == dpadPointer) {
            dpadPointer = -1
            applyDpad(0)
        }
        if (id == stickPointer) {
            stickPointer = -1
            centreStick()
        }
        invalidate()
    }

    private fun releaseAll() {
        if (edit) return
        for (b in btns) {
            if (b.pointer != -1) {
                b.pointer = -1
                PadNative.key(b.sentKey, false)
            }
        }
        if (dpadPointer != -1) {
            dpadPointer = -1
            applyDpad(0)
        }
        if (stickPointer != -1) {
            stickPointer = -1
            centreStick()
        }
    }

    // ---- movement ring

    private fun updateStick(x: Float, y: Float) {
        var dx = (x - stickCx) / stickR
        var dy = (y - stickCy) / stickR
        val len = hypot(dx, dy)
        if (len > 1f) {
            dx /= len
            dy /= len
        }
        knobX = dx
        knobY = dy
        sendStick(dx, dy)
    }

    private fun centreStick() {
        knobX = 0f
        knobY = 0f
        sendStick(0f, 0f)
    }

    /** Analog mode sends the left stick; D-pad mode presses the four directions past a point. */
    private fun sendStick(dx: Float, dy: Float) {
        if (layout.get(PadLayout.STICK).k < 0) {
            PadNative.stick(dx, dy)
        } else {
            val m = maskFor(dx, dy, 0.35f)
            diffKeys(stickMask, m)
            stickMask = m
        }
    }

    // ---- shared four-way helpers

    private fun maskFor(dx: Float, dy: Float, t: Float): Int {
        var mask = 0
        if (dy < -t) mask = mask or DIR_UP
        if (dy > t) mask = mask or DIR_DOWN
        if (dx < -t) mask = mask or DIR_LEFT
        if (dx > t) mask = mask or DIR_RIGHT
        return mask
    }

    private fun diffKeys(old: Int, now: Int) {
        if (old == now) return
        val bits = intArrayOf(DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT)
        val keys = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        )
        for (i in 0 until 4) {
            val was = (old and bits[i]) != 0
            val isNow = (now and bits[i]) != 0
            if (was != isNow) PadNative.key(keys[i], isNow)
        }
    }

    private fun applyDpad(mask: Int) {
        diffKeys(dpadMask, mask)
        dpadMask = mask
    }

    // ---------------------------------------------------------------- icons

    private fun icon(name: String): Bitmap? {
        iconCache[name]?.let { return it }
        if (name in iconMissing) return null
        val bmp = try {
            context.assets.open("ui/icons/$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (bmp == null) iconMissing.add(name) else iconCache[name] = bmp
        return bmp
    }

    private fun drawBitmap(c: Canvas, bmp: Bitmap, l: Float, t: Float, r: Float, b: Float) {
        rect2.set(l, t, r, b)
        bmpPaint.alpha = alpha(1f)
        c.drawBitmap(bmp, null, rect2, bmpPaint)
    }

    // ---------------------------------------------------------------- drawing

    private fun alpha(f: Float): Int = (min(1f, max(0f, opacity * f * dim)) * 255f).toInt()

    override fun onDraw(c: Canvas) {
        dim = if (edit && !stickShown) 0.3f else 1f
        if (stickShown || edit) drawStick(c)
        if (dpadShown || edit) {
            dim = if (edit && !dpadShown) 0.3f else 1f
            drawDpad(c)
        }
        for (b in btns) {
            if (!b.shown && !edit) continue
            dim = if (b.shown) 1f else 0.3f
            drawButton(c, b)
        }
        dim = 1f
        if (edit) drawSelection(c)
    }

    /** Edit-mode outline around a control. */
    private fun drawSelection(c: Canvas) {
        val id = selected ?: return
        val pad = 6f * d
        when (id) {
            PadLayout.STICK -> rect.set(stickCx - stickR - pad, stickCy - stickR - pad, stickCx + stickR + pad, stickCy + stickR + pad)
            PadLayout.DPAD -> rect.set(dpadCx - dpadR - pad, dpadCy - dpadR - pad, dpadCx + dpadR + pad, dpadCy + dpadR + pad)
            else -> {
                val b = btns.firstOrNull { it.id == id } ?: return
                rect.set(b.cx - b.hw - pad, b.cy - b.hh - pad, b.cx + b.hw + pad, b.cy + b.hh + pad)
            }
        }
        c.drawRoundRect(rect, 10f * d, 10f * d, selPaint)
    }

    private fun glassColor(): Int = Color.argb(alpha(0.62f), 8, 12, 22)
    private fun edgeColor(hot: Boolean): Int =
        if (hot) Color.argb(alpha(1f), 255, 130, 140) else Color.argb(alpha(0.65f), 150, 215, 255)

    /** Slim diamond into [path]. */
    private fun gemInto(p: Path, cx: Float, cy: Float, hw: Float, hh: Float) {
        p.reset()
        p.moveTo(cx, cy - hh)
        p.lineTo(cx + hw, cy)
        p.lineTo(cx, cy + hh)
        p.lineTo(cx - hw, cy)
        p.close()
    }

    // ---- movement ring

    private fun drawStick(c: Canvas) {
        val active = stickPointer != -1
        val full = icon("stick_full")
        if (full != null) {
            drawBitmap(c, full, stickCx - stickR, stickCy - stickR, stickCx + stickR, stickCy + stickR)
        } else {
            fill.color = glassColor()
            c.drawCircle(stickCx, stickCy, stickR, fill)
            ring.color = edgeColor(active)
            c.drawCircle(stickCx, stickCy, stickR, ring)
            // Dotted inner ring and twelve light ticks around the edge.
            sym.pathEffect = dots
            sym.strokeWidth = 2.2f * d
            sym.color = Color.argb(alpha(0.5f), 120, 205, 255)
            c.drawCircle(stickCx, stickCy, stickR * 0.72f, sym)
            sym.pathEffect = null
            ring.color = Color.argb(alpha(0.6f), 150, 215, 255)
            for (i in 0 until 12) {
                val a = Math.toRadians(i * 30.0)
                val ca = Math.cos(a).toFloat()
                val sa = Math.sin(a).toFloat()
                val len = if (i % 3 == 0) 0.14f else 0.07f
                c.drawLine(
                    stickCx + ca * stickR * (0.94f - len), stickCy + sa * stickR * (0.94f - len),
                    stickCx + ca * stickR * 0.94f, stickCy + sa * stickR * 0.94f, ring
                )
            }
        }

        val knobR = stickR * 0.4f
        val reach = stickR - knobR * 1.05f
        val kx = stickCx + knobX * reach
        val ky = stickCy + knobY * reach

        // Beam of light from the middle to the knob while pushing.
        if (active && (knobX != 0f || knobY != 0f)) {
            sym.color = Color.argb(alpha(0.75f), 56, 189, 248)
            sym.strokeWidth = knobR * 0.3f
            c.drawLine(stickCx, stickCy, kx, ky, sym)
        }
        val custom = icon("stick")
        if (custom != null) {
            drawBitmap(c, custom, kx - knobR, ky - knobR, kx + knobR, ky + knobR)
            return
        }
        fill.shader = LinearGradient(
            0f, ky - knobR, 0f, ky + knobR,
            if (active) intArrayOf(Color.argb(alpha(1f), 255, 120, 130), Color.argb(alpha(1f), 168, 20, 36))
            else intArrayOf(Color.argb(alpha(0.96f), 238, 244, 252), Color.argb(alpha(0.96f), 140, 156, 182)),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(kx, ky, knobR, fill)
        fill.shader = null
        ring.color = Color.argb(alpha(0.9f), 16, 24, 40)
        c.drawCircle(kx, ky, knobR, ring)
        // The colour-timer gem in the middle of the knob.
        gemInto(path, kx, ky, knobR * 0.32f, knobR * 0.5f)
        fill.color = if (active) Color.argb(alpha(1f), 255, 255, 255) else Color.argb(alpha(1f), 56, 189, 248)
        c.drawPath(path, fill)
    }

    // ---- menu pad

    private fun drawDpad(c: Canvas) {
        val r = dpadR
        val custom = icon("dpad")
        if (custom != null) {
            drawBitmap(c, custom, dpadCx - r, dpadCy - r, dpadCx + r, dpadCy + r)
            return
        }
        fill.color = glassColor()
        c.drawCircle(dpadCx, dpadCy, r, fill)
        ring.color = edgeColor(false)
        c.drawCircle(dpadCx, dpadCy, r, ring)

        val o = r * 0.58f
        val s = r * 0.17f
        arrow(c, dpadCx, dpadCy - o, s, 0, (dpadMask and DIR_UP) != 0)
        arrow(c, dpadCx + o, dpadCy, s, 1, (dpadMask and DIR_RIGHT) != 0)
        arrow(c, dpadCx, dpadCy + o, s, 2, (dpadMask and DIR_DOWN) != 0)
        arrow(c, dpadCx - o, dpadCy, s, 3, (dpadMask and DIR_LEFT) != 0)
    }

    /** dir: 0 up, 1 right, 2 down, 3 left */
    private fun arrow(c: Canvas, cx: Float, cy: Float, s: Float, dir: Int, hot: Boolean) {
        path.reset()
        when (dir) {
            0 -> { path.moveTo(cx, cy - s); path.lineTo(cx - s, cy + s); path.lineTo(cx + s, cy + s) }
            2 -> { path.moveTo(cx, cy + s); path.lineTo(cx - s, cy - s); path.lineTo(cx + s, cy - s) }
            3 -> { path.moveTo(cx - s, cy); path.lineTo(cx + s, cy - s); path.lineTo(cx + s, cy + s) }
            else -> { path.moveTo(cx + s, cy); path.lineTo(cx - s, cy - s); path.lineTo(cx - s, cy + s) }
        }
        path.close()
        fill.color = if (hot) Color.argb(alpha(1f), 120, 210, 255) else Color.argb(alpha(0.85f), 222, 234, 248)
        c.drawPath(path, fill)
    }

    // ---- buttons

    private fun drawButton(c: Canvas, b: Btn) {
        val grow = if (b.pressed) 1.06f else 1f
        val hw = b.hw * grow
        val hh = b.hh * grow
        val cx = b.cx
        val cy = b.cy

        val full = icon(b.id + "_full")
        if (full != null) {
            drawBitmap(c, full, cx - hw, cy - hh, cx + hw, cy + hh)
            return
        }

        // Body: a disc ringed with light for the main buttons, a gem for the beam, a pill for bars.
        when (b.shape) {
            SHAPE_DISC -> {
                val inner = hw * 0.84f
                paintBody(b.pressed, cy - inner, cy + inner)
                c.drawCircle(cx, cy, inner, fill)
                // Three arcs of light with small gaps, like a colour timer.
                ring.color = if (b.pressed) Color.argb(alpha(1f), 255, 255, 255) else Color.argb(alpha(0.95f), 56, 189, 248)
                ring.strokeWidth = max(2f * d, hw * 0.07f)
                ring.strokeCap = Paint.Cap.ROUND
                rect.set(
                    cx - hw + ring.strokeWidth / 2f, cy - hw + ring.strokeWidth / 2f,
                    cx + hw - ring.strokeWidth / 2f, cy + hw - ring.strokeWidth / 2f
                )
                for (i in 0 until 3) c.drawArc(rect, -90f + i * 120f + 12f, 96f, false, ring)
                ring.strokeCap = Paint.Cap.BUTT
                ring.strokeWidth = 1.6f * d
            }
            SHAPE_GEM -> {
                gemInto(path, cx, cy, hw, hh)
                paintBody(b.pressed, cy - hh, cy + hh)
                c.drawPath(path, fill)
                c.drawPath(path, ring)
                gemInto(path, cx, cy, hw * 0.66f, hh * 0.66f)
                ring.color = Color.argb(alpha(if (b.pressed) 0.7f else 0.45f), 150, 215, 255)
                ring.strokeWidth = 1f * d
                c.drawPath(path, ring)
                ring.strokeWidth = 1.6f * d
            }
            else -> {
                paintBody(b.pressed, cy - hh, cy + hh)
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
                c.drawRoundRect(rect, hh, hh, fill)
                c.drawRoundRect(rect, hh, hh, ring)
            }
        }
        fill.shader = null

        // Face
        val custom = icon(b.id)
        if (custom != null) {
            val g = min(hw, hh) * 0.78f
            drawBitmap(c, custom, cx - g, cy - g, cx + g, cy + g)
            return
        }
        val ink = Color.argb(alpha(0.97f), 242, 247, 255)
        val glow = if (b.pressed) Color.argb(alpha(1f), 255, 255, 255) else Color.argb(alpha(1f), 120, 210, 255)
        if (words) {
            val w = wordFor(b.id)
            word(c, cx, cy, w, min(hw * 2f / w.length * 1.15f, hh * 0.9f), ink)
            return
        }
        sym.color = glow
        sym.strokeWidth = max(1.6f * d, min(hw, hh) * 0.09f)
        fill.color = glow
        val u = min(hw, hh)
        when (b.id) {
            PadLayout.ATTACK -> {
                mtx.reset()
                mtx.postScale(u * 0.42f, u * 0.42f)
                mtx.postTranslate(cx, cy - u * 0.16f)
                glyph.reset()
                burst.transform(mtx, glyph)
                c.drawPath(glyph, fill)
                word(c, cx, cy + u * 0.5f, "ATTACK", u * 0.22f, ink)
            }
            PadLayout.STRONG -> {
                bolt(c, cx, cy - u * 0.14f, u * 0.42f)
                word(c, cx, cy + u * 0.52f, "STRONG", u * 0.24f, ink)
            }
            PadLayout.JUMP -> {
                for (k in 0..1) {
                    val y = cy - u * 0.34f + k * u * 0.28f
                    path.reset()
                    path.moveTo(cx - u * 0.36f, y + u * 0.16f)
                    path.lineTo(cx, y - u * 0.14f)
                    path.lineTo(cx + u * 0.36f, y + u * 0.16f)
                    c.drawPath(path, sym)
                }
                word(c, cx, cy + u * 0.5f, "JUMP", u * 0.26f, ink)
            }
            PadLayout.GUARD -> {
                val s = u * 0.34f
                val gy = cy - u * 0.14f
                path.reset()
                path.moveTo(cx - s * 0.85f, gy - s * 0.7f)
                path.lineTo(cx + s * 0.85f, gy - s * 0.7f)
                path.lineTo(cx + s * 0.85f, gy + s * 0.1f)
                path.lineTo(cx, gy + s * 0.95f)
                path.lineTo(cx - s * 0.85f, gy + s * 0.1f)
                path.close()
                c.drawPath(path, sym)
                word(c, cx, cy + u * 0.52f, "GUARD", u * 0.24f, ink)
            }
            PadLayout.BEAM -> {
                // Three rays of light fanning out from a point, and the word underneath.
                val bx = cx - hw * 0.5f
                val by = cy - hh * 0.2f
                for (i in -1..1) {
                    c.drawLine(bx, by + i * hh * 0.07f, cx + hw * 0.5f, by + i * hh * 0.2f, sym)
                }
                c.drawCircle(bx, by, max(1.8f * d, hw * 0.1f), fill)
                word(c, cx, cy + hh * 0.5f, "BEAM", hw * 0.46f, ink)
            }
            PadLayout.DASH -> {
                mtx.reset()
                mtx.postScale(hh * 0.5f, hh * 0.5f)
                mtx.postTranslate(cx - hw * 0.46f, cy)
                glyph.reset()
                chev.transform(mtx, glyph)
                c.drawPath(glyph, fill)
                word(c, cx + hw * 0.2f, cy, "DASH", hh * 0.8f, ink)
            }
            PadLayout.SELECT -> word(c, cx, cy, "SELECT", hh * 0.8f, ink)
            else -> {
                // Pause: two bars.
                val s = hh * 0.5f
                fill.color = ink
                rect2.set(cx - s * 0.8f, cy - s, cx - s * 0.2f, cy + s)
                c.drawRect(rect2, fill)
                rect2.set(cx + s * 0.2f, cy - s, cx + s * 0.8f, cy + s)
                c.drawRect(rect2, fill)
            }
        }
    }

    private fun bolt(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx + s * 0.25f, cy - s)
        path.lineTo(cx - s * 0.65f, cy + s * 0.1f)
        path.lineTo(cx - s * 0.05f, cy + s * 0.1f)
        path.lineTo(cx - s * 0.25f, cy + s)
        path.lineTo(cx + s * 0.65f, cy - s * 0.15f)
        path.lineTo(cx + s * 0.05f, cy - s * 0.15f)
        path.close()
        c.drawPath(path, fill)
    }

    private fun wordFor(id: String): String = when (id) {
        PadLayout.ATTACK -> "ATK"
        PadLayout.STRONG -> "STRONG"
        PadLayout.JUMP -> "JUMP"
        PadLayout.GUARD -> "GUARD"
        PadLayout.BEAM -> "BEAM"
        PadLayout.DASH -> "DASH"
        PadLayout.SELECT -> "SELECT"
        else -> "PAUSE"
    }

    /** Sets up [fill] and [ring] for a glass body, hot red when pressed. */
    private fun paintBody(pressed: Boolean, top: Float, bottom: Float) {
        if (pressed) {
            fill.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.argb(alpha(1f), 255, 112, 124), Color.argb(alpha(1f), 176, 20, 36)),
                null, Shader.TileMode.CLAMP
            )
        } else {
            fill.shader = null
            fill.color = glassColor()
        }
        ring.color = edgeColor(pressed)
    }

    /** Text centred on (x, y); [size] is in pixels. */
    private fun word(c: Canvas, x: Float, y: Float, text: String, size: Float, color: Int) {
        fill.shader = null
        label.textSize = max(size, 7f * d)
        label.color = color
        val fm = label.fontMetrics
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, label)
    }
}
