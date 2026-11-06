package com.ultrafe.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Drag-and-drop editor for the on-screen controls. It draws the real pad (same view the game
 * uses), so what you arrange here is exactly what you get in game.
 *
 *  - drag a control to move it
 *  - pinch on it, or use the Size slider, to resize it
 *  - the switch hides a control you don't want
 *
 * Changes are saved as you go to pad_layout.json. Export writes the same file out, so a layout
 * you like can be put into assets/ui/layout.json and become the default for everyone.
 */
class LayoutEditorActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var layout: PadLayout
    private lateinit var root: FrameLayout
    private lateinit var pad: PadView
    private lateinit var fps: FpsPanel
    private lateinit var surface: EditSurface

    private var selected: String? = null
    private var sheet: View? = null
    private var snap = true
    private lateinit var bar: View
    private lateinit var menuChip: View

    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) doExport(uri)
    }
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) doImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        layout = PadLayout.load(this)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        pad = PadView(this, prefs, layout, true) {}
        root.addView(pad, FrameLayout.LayoutParams(MATCH, MATCH))

        fps = FpsPanel(this, layout, true, false)
        root.addView(fps, FrameLayout.LayoutParams(WRAP, WRAP))

        surface = EditSurface(this)
        root.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))

        bar = buildToolbar()
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // Small "Menu" chip that brings the toolbar back after "Hide bar", so the buttons at the
        // top of the screen (L, R and the FPS panel) can be reached and edited.
        menuChip = nButton("☰  Menu", false) { setBar(true) }
        menuChip.visibility = View.GONE
        root.addView(menuChip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(4)
        })

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeFps() }
        setContentView(root)
        applyAll()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onPause() {
        PadLayout.save(this, layout)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        PadLayout.save(this, layout)
        finish()
    }

    // ------------------------------------------------------------------ toolbar

    private fun buildToolbar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(10), dp(6), dp(10), dp(14))
        bar.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(235, 6, 7, 10), Color.argb(0, 6, 7, 10))
        )
        bar.applyInsetsPadding()

        bar.addView(nButton("‹  Done", true) {
            PadLayout.save(this, layout)
            finish()
        }, lp(WRAP))

        bar.addView(nButton("Hide bar", false) { setBar(false) }, lp(WRAP, dp(6)))

        val title = nText("EDIT CONTROLS", 13f, Nk.TEXT, true)
        title.letterSpacing = 0.14f
        title.setPadding(dp(12), 0, 0, 0)
        bar.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))

        val snapLabel = nText("Snap", 12f, Nk.DIM)
        snapLabel.setPadding(0, 0, dp(6), 0)
        bar.addView(snapLabel)
        val snapToggle = NToggle(this)
        snapToggle.setChecked(snap, false)
        snapToggle.onChange = { snap = it }
        bar.addView(snapToggle, lp(WRAP, dp(10)))

        bar.addView(nButton("Look", false) { showLook() }, lp(WRAP, dp(6)))
        bar.addView(nButton("Load", false) { importPicker.launch(arrayOf("*/*")) }, lp(WRAP, dp(6)))
        bar.addView(nButton("Save file", false) { exportPicker.launch("layout.json") }, lp(WRAP, dp(6)))
        bar.addView(nButton("Reset", false) { confirmReset() }, lp(WRAP, dp(6)))
        return bar
    }

    private fun setBar(open: Boolean) {
        bar.visibility = if (open) View.VISIBLE else View.GONE
        menuChip.visibility = if (open) View.GONE else View.VISIBLE
    }

    private fun lp(w: Int, left: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, WRAP)
        p.leftMargin = left
        return p
    }

    // ------------------------------------------------------------------ editing

    private fun applyAll() {
        pad.refresh()
        fps.dimmed = !layout.get(PadLayout.FPS).v
        fps.refresh()
        root.post { placeFps() }
    }

    private fun placeFps() {
        if (root.width > 0) fps.place(root.width, root.height)
    }

    private fun select(id: String?) {
        selected = id
        pad.selected = if (id == PadLayout.FPS) null else id
        surface.invalidate()
        showSheet(id)
    }

    private fun showSheet(id: String?) {
        sheet?.let { root.removeView(it) }
        sheet = null
        if (id == null) return
        val e = layout.get(id)

        val toggle = NToggle(this)
        toggle.setChecked(e.v, false)
        toggle.onChange = {
            e.v = it
            applyAll()
            PadLayout.save(this, layout)
        }
        val tile = NTile(this, PadLayout.NAMES[id] ?: id, toggle)
        tile.addView(
            sliderRow("Size", 40, 250, (e.s * 100).roundToInt(), { "$it%" }) { v ->
                e.s = (v / 100f).coerceIn(PadLayout.MIN_SCALE, PadLayout.MAX_SCALE)
                applyAll()
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        addSendsSection(tile, id, e)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        row.addView(nButton("Reset item", false) {
            val d = PadLayout.shipped(this).get(id)
            e.x = d.x
            e.y = d.y
            e.s = d.s
            e.v = d.v
            e.c = d.c
            e.k = d.k
            applyAll()
            PadLayout.save(this, layout)
            showSheet(id)
        })
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.topMargin = dp(6)
        tile.addView(row, rlp)
        tile.isClickable = true   // keeps touches on the sheet from reaching the editor surface

        val flp = FrameLayout.LayoutParams(dp(320), WRAP, Gravity.CENTER)
        root.addView(tile, flp)
        sheet = tile
    }

    /** "Sends" chips: which game button this control presses (steering: stick or D-pad). */
    private fun addSendsSection(tile: LinearLayout, id: String, e: Elem) {
        if (id == PadLayout.FPS || id == PadLayout.DPAD) return
        val head = nText(if (id == PadLayout.STICK) "Moves with" else "Presses", 11f, Nk.DIM)
        val hl = LinearLayout.LayoutParams(MATCH, WRAP)
        hl.topMargin = dp(8)
        hl.bottomMargin = dp(3)
        tile.addView(head, hl)

        if (id == PadLayout.STICK) {
            tile.addView(chipRow(listOf("Analog stick", "D-pad"), null, if (e.k >= 0) 1 else 0) {
                e.k = if (it == 1) 8 else -1
                PadLayout.save(this, layout)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            return
        }
        val cur = PadLayout.keyIndex(layout, id)
        val names = PadLayout.KEY_NAMES
        val half = 6
        fun rowFor(from: Int, to: Int): LinearLayout =
            chipRow(names.subList(from, to), null, if (cur in from until to) cur - from else -1) { i ->
                e.k = from + i
                PadLayout.save(this, layout)
                showSheet(id)
            }
        tile.addView(rowFor(0, half), LinearLayout.LayoutParams(MATCH, WRAP))
        val l2 = LinearLayout.LayoutParams(MATCH, WRAP)
        l2.topMargin = dp(3)
        tile.addView(rowFor(half, names.size), l2)
    }

    private fun confirmReset() {
        nPanel(
            "Reset layout?",
            nText(
                "Every control goes back to the default layout and buttons shipped with the app.",
                13f, Nk.TEXT
            ),
            listOf(
                Pair("Cancel") {},
                Pair("Reset") {
                    PadLayout.reset(this)
                    layout.copyFrom(PadLayout.shipped(this))
                    select(null)
                    applyAll()
                }
            )
        )
    }

    private fun showLook() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(toggleRow("On-screen controls", "Show the touch pad in game", prefs.touchControls) {
            prefs.touchControls = it
        })
        box.addView(toggleRow("Vibration", "Light tap on every control", prefs.haptics) { prefs.haptics = it })
        box.addView(nText("Control labels", 13f, Nk.TEXT, true), LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
        })
        box.addView(chipRow(listOf("Icons", "Words"), null, prefs.padStyle.coerceIn(0, 1)) {
            prefs.padStyle = it
            pad.applyPrefs(prefs)
        })
        box.addView(sliderRow("Opacity", 20, 100, prefs.touchOpacity, { "$it%" }) {
            prefs.touchOpacity = it
            pad.applyPrefs(prefs)
        })
        box.addView(sliderRow("Stick dead zone", 0, 40, prefs.deadzone, { "$it%" }) { prefs.deadzone = it })
        box.addView(sliderRow("Stick sensitivity", 60, 160, prefs.sensitivity, { "$it%" }) { prefs.sensitivity = it })
        nPanel("Look and feel", box, listOf(Pair("Done") { pad.applyPrefs(prefs) }))
    }

    // ------------------------------------------------------------------ files

    private fun doExport(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(layout.toJson().toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Layout saved. Put it in assets/ui/layout.json to make it the default.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun doImport(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val l = PadLayout.defaults()
            if (!l.applyJson(text)) {
                Toast.makeText(this, "That isn't a layout file.", Toast.LENGTH_LONG).show()
                return
            }
            layout.copyFrom(l)
            PadLayout.save(this, layout)
            select(null)
            applyAll()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't read that file.", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ touch surface

    /** Transparent layer above the pad: draws the guide and turns touches into moves and resizes. */
    private inner class EditSurface(context: Context) : View(context) {
        private val dens = resources.displayMetrics.density
        private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * dens
            color = Color.argb(70, 255, 255, 255)
            pathEffect = DashPathEffect(floatArrayOf(10f * dens, 6f * dens), 0f)
        }
        private val guideText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(48, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            typeface = Nk.FONT
            textSize = 11f * dens
            letterSpacing = 0.3f
        }
        private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * dens
            color = Color.rgb(255, 95, 105)
            pathEffect = DashPathEffect(floatArrayOf(7f * dens, 4f * dens), 0f)
        }
        private val rect = RectF()

        private var lastX = 0f
        private var lastY = 0f
        private var rawX = 0f
        private var rawY = 0f
        private var dragging = false
        private var moved = false
        private var downHit: String? = null

        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                val id = selected ?: return false
                val e = layout.get(id)
                e.s = (e.s * det.scaleFactor).coerceIn(PadLayout.MIN_SCALE, PadLayout.MAX_SCALE)
                applyAll()
                return true
            }

            override fun onScaleEnd(det: ScaleGestureDetector) {
                PadLayout.save(this@LayoutEditorActivity, layout)
                showSheet(selected)
            }
        })

        private fun fpsBounds(): RectF {
            val l = fps.left + fps.translationX
            val t = fps.top + fps.translationY
            rect.set(l, t, l + fps.width, t + fps.height)
            return rect
        }

        private fun hit(x: Float, y: Float): String? {
            if (fpsBounds().contains(x, y)) return PadLayout.FPS
            return pad.elementAt(x, y)
        }

        override fun onDraw(c: Canvas) {
            // 16:9 game area, as the picture appears with "16:9" screen mode.
            val w = width.toFloat()
            val h = height.toFloat()
            val gw = min(w, h * 16f / 9f)
            val gh = gw * 9f / 16f
            rect.set((w - gw) / 2f, (h - gh) / 2f, (w + gw) / 2f, (h + gh) / 2f)
            c.drawRoundRect(rect, 6f * dens, 6f * dens, guide)
            c.drawText("GAME SCREEN", w / 2f, h / 2f + 4f * dens, guideText)

            if (selected == PadLayout.FPS) {
                val b = fpsBounds()
                rect.set(b.left - 6f * dens, b.top - 6f * dens, b.right + 6f * dens, b.bottom + 6f * dens)
                c.drawRoundRect(rect, 10f * dens, 10f * dens, sel)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            scaler.onTouchEvent(ev)
            if (scaler.isInProgress) {
                dragging = false
                return true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.x
                    lastY = ev.y
                    moved = false
                    downHit = hit(ev.x, ev.y)
                    val id = downHit
                    dragging = id != null
                    if (id != null) {
                        if (id != selected) select(id)
                        val e = layout.get(id)
                        rawX = e.x
                        rawY = e.y
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val id = selected
                    if (dragging && id != null && ev.pointerCount == 1 && width > 0 && height > 0) {
                        val dx = ev.x - lastX
                        val dy = ev.y - lastY
                        if (abs(dx) + abs(dy) > 0.5f) moved = true
                        lastX = ev.x
                        lastY = ev.y
                        rawX = (rawX + dx / width).coerceIn(0.02f, 0.98f)
                        rawY = (rawY + dy / height).coerceIn(0.03f, 0.97f)
                        val e = layout.get(id)
                        e.x = if (snap) (rawX * 100f).roundToInt() / 100f else rawX
                        e.y = if (snap) (rawY * 100f).roundToInt() / 100f else rawY
                        applyAll()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging && moved) PadLayout.save(this@LayoutEditorActivity, layout)
                    if (!moved && downHit == null) select(null)
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }
}
