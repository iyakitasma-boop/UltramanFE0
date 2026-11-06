package com.ultrafe.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Ultraman palette: hero red, silver, colour-timer blue on deep navy-black. */
object Nk {
    val BG = Color.parseColor("#050807")
    val PANEL = Color.argb(214, 7, 10, 18)
    val CHIP = Color.argb(238, 16, 22, 36)
    val ACCENT = Color.parseColor("#E8283A")
    val ACCENT_HI = Color.parseColor("#FF6B73")
    val ACCENT_LO = Color.parseColor("#B01524")
    val SILVER = Color.parseColor("#D0D8E6")
    val BLUE = Color.parseColor("#38BDF8")   // colour-timer blue, used for energy glows
    val TEXT = Color.parseColor("#F4F6FA")
    val DIM = Color.parseColor("#9AA1B0")
    val OK = Color.parseColor("#3DDC97")
    val WARN = Color.parseColor("#FFB020")
    val RED = Color.parseColor("#E5384A")

    val FONT: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val FONT_REG: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
}

fun Context.nText(text: CharSequence, sp: Float, color: Int, bold: Boolean = false): TextView {
    val tv = TextView(this)
    tv.text = text
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    tv.setTextColor(color)
    tv.typeface = if (bold) Nk.FONT else Nk.FONT_REG
    return tv
}

/** Forward-leaning double chevron (>>), unit size, centred on 0,0. Used for tap effect and badges. */
fun chevronPath(): Path {
    val p = Path()
    for (k in 0..1) {
        val x0 = -0.95f + k * 0.9f
        p.moveTo(x0, -0.9f)
        p.lineTo(x0 + 0.55f, -0.9f)
        p.lineTo(x0 + 1.05f, 0f)
        p.lineTo(x0 + 0.55f, 0.9f)
        p.lineTo(x0, 0.9f)
        p.lineTo(x0 + 0.5f, 0f)
        p.close()
    }
    return p
}

/** Slim diamond (a colour-timer gem), unit height, pointy top and bottom, centred on 0,0. */
fun hexPath(radius: Float = 1f): Path {
    val p = Path()
    p.moveTo(0f, -radius)
    p.lineTo(radius * 0.72f, 0f)
    p.lineTo(0f, radius)
    p.lineTo(-radius * 0.72f, 0f)
    p.close()
    return p
}

/** Star with [points] tips (kept for small sparkle shapes). */
fun starPath(
    cx: Float, cy: Float, outer: Float, inner: Float,
    points: Int = 4, startDeg: Float = -90f
): Path {
    val p = Path()
    val n = points * 2
    for (i in 0 until n) {
        val r = if (i % 2 == 0) outer else inner
        val a = Math.toRadians(startDeg + i * 360.0 / n)
        val x = cx + (r * Math.cos(a)).toFloat()
        val y = cy + (r * Math.sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

// ====================================================================================
// Tap effect: every touch sends out an energy ring and a spinning diamond (a colour-timer spark).
// ====================================================================================

class FxLayout(context: Context) : FrameLayout(context) {
    private class Tap(val x: Float, val y: Float, val t0: Long, val dir: Float)

    private val taps = ArrayList<Tap>()
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hex = hexPath()

    init {
        setWillNotDraw(false)
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            taps.add(Tap(e.getX(i), e.getY(i), SystemClock.uptimeMillis(), 1f))
            if (taps.size > 6) taps.removeAt(0)
            postInvalidateOnAnimation()
        }
        return super.dispatchTouchEvent(e)
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (taps.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val iter = taps.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            val p = (now - t.t0) / 620f
            if (p >= 1f) {
                iter.remove()
                continue
            }
            drawTap(c, t, p)
        }
        if (taps.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawTap(c: Canvas, t: Tap, p: Float) {
        val e = 1f - (1f - p) * (1f - p)

        ring.strokeWidth = (3f * (1f - p) + 0.8f) * d
        ring.color = Color.argb(((1f - p) * 200f).toInt(), 255, 95, 105)
        c.drawCircle(t.x, t.y, (10f + 54f * e) * d, ring)

        if (p > 0.15f) {
            val q = (p - 0.15f) / 0.85f
            ring.strokeWidth = 1.2f * d
            ring.color = Color.argb(((1f - q) * 110f).toInt(), 225, 230, 240)
            c.drawCircle(t.x, t.y, (6f + 38f * q) * d, ring)
        }

        val s = (6f + 12f * e) * d
        c.save()
        c.translate(t.x, t.y)
        c.rotate(60f * e)
        c.scale(s, s)
        fill.color = Color.argb(((1f - p) * 235f).toInt(), 245, 248, 255)
        ring.strokeWidth = 0.16f
        ring.color = fill.color
        c.drawPath(hex, ring)
        c.restore()
    }
}

// ====================================================================================
// Backdrop: your background if you add one, otherwise a built-in animated night sky.
// ====================================================================================

/** Rising blue energy sparks, a soft glow and a huge slowly turning colour-timer diamond. */
class EmberView(context: Context) : View(context) {
    private class P(var x: Float, var y: Float, val v: Float, val r: Float, val ph: Float, val gold: Boolean)

    private val d = resources.displayMetrics.density
    private val ps = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var glow: RadialGradient? = null
    private val hexUnit = hexPath()
    private val hexTmp = Path()
    private val hexM = Matrix()
    private var last = 0L
    private var angle = 0f

    private fun rnd(): Float = Math.random().toFloat()

    private fun spawn(w: Int, h: Int, anywhere: Boolean): P =
        P(
            rnd() * w,
            if (anywhere) rnd() * h else h + 12f * d,
            (14f + rnd() * 26f) * d,
            (1.2f + rnd() * 2.4f) * d,
            rnd() * 6.28f,
            rnd() < 0.3f
        )

    private fun hexOuter(r: Float): Path {
        hexM.reset()
        hexM.setScale(r, r)
        hexTmp.reset()
        hexUnit.transform(hexM, hexTmp)
        return hexTmp
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        ps.clear()
        for (i in 0 until 26) ps.add(spawn(w, h, true))
        glow = RadialGradient(
            w * 0.3f, h * 0.55f, max(w, h) * 0.6f,
            intArrayOf(Color.argb(78, 236, 40, 58), Color.argb(0, 236, 40, 58)),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else min(0.05f, (now - last) / 1000f)
        last = now
        angle = (angle + dt * 5f) % 360f

        paint.shader = glow
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // Big belt buckle: nested hexagons turning slowly in opposite directions.
        val rr = h * 0.66f
        c.save()
        c.translate(w * 0.8f, h * 0.52f)
        c.rotate(angle)
        wheel.strokeWidth = rr * 0.035f
        wheel.color = Color.argb(20, 255, 255, 255)
        c.drawPath(hexOuter(rr), wheel)
        c.rotate(-2f * angle)
        wheel.strokeWidth = rr * 0.05f
        wheel.color = Color.argb(26, 232, 40, 58)
        c.drawPath(hexOuter(rr * 0.74f), wheel)
        c.rotate(2f * angle)
        wheel.strokeWidth = rr * 0.02f
        wheel.color = Color.argb(16, 255, 255, 255)
        c.drawPath(hexOuter(rr * 0.5f), wheel)
        c.restore()

        for (i in ps.indices) {
            val p = ps[i]
            p.y -= p.v * 6f * dt
            p.x += sin((p.ph + now / 900.0)).toFloat() * 8f * d * dt
            if (p.y < -80f * d) {
                ps[i] = spawn(w, h, false)
                continue
            }
            val a = (60f + 150f * (0.5f + 0.5f * sin(p.ph.toDouble()).toFloat())).toInt()
            streak.color = if (p.gold) Color.argb(a, 225, 230, 240) else Color.argb(a, 70, 190, 255)
            streak.strokeWidth = p.r * 0.7f
            c.drawLine(p.x, p.y, p.x, p.y + p.v * 2.4f, streak)
        }
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
        postInvalidateOnAnimation()
    }
}

/** Reads an image from the APK's assets, shrunk to about the screen size. Null if missing. */
fun decodeAssetBitmap(ctx: Context, path: String): Bitmap? {
    return try {
        val dm = ctx.resources.displayMetrics
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= dm.widthPixels &&
            bounds.outHeight / (sample * 2) >= dm.heightPixels
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (t: Throwable) {
        null
    }
}

/** Names of the files in the APK's assets/ui folder. */
fun uiAssets(ctx: Context): List<String> =
    try {
        ctx.assets.list("ui")?.toList() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

private fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

/** Soft red glow, drawn once. */
private class GlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        paint.shader = RadialGradient(
            w * 0.3f, h * 0.6f, max(w, h) * 0.65f,
            intArrayOf(Color.argb(70, 236, 40, 58), Color.argb(0, 236, 40, 58)),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/**
 * Background of the launcher: a still picture, never a video. Put your own in the APK's
 * assets/ui folder as background.jpg (or .png / .webp). With none, a plain carbon gradient
 * with a soft glow is used.
 */
class BackdropView(context: Context) : FrameLayout(context) {
    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#0A1020"), Color.parseColor("#070A12"), Nk.BG)
        )

        val still = uiAssets(context).firstOrNull {
            it.startsWith("background.", true) && ext(it) in listOf("jpg", "jpeg", "png", "webp")
        }
        val bmp = if (still != null) decodeAssetBitmap(context, "ui/$still") else null
        if (bmp != null) {
            val iv = ImageView(context)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(bmp)
            addView(iv, LayoutParams(MATCH, MATCH))
        } else {
            addView(GlowView(context), LayoutParams(MATCH, MATCH))
        }

        // Keeps text readable over any picture.
        val scrim = View(context)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(150, 5, 6, 9), Color.argb(70, 5, 6, 9), Color.argb(120, 5, 6, 9))
        )
        addView(scrim, LayoutParams(MATCH, MATCH))
    }
}

/** A muted, looping video centre-cropped to fill its box. */
class LoopVideoView(context: Context, private val path: String) : FrameLayout(context) {
    private val texture = TextureView(context)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private var fixedSize = false
    private var wantPlaying = false

    init {
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                start(st)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                applyCrop()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stop()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        addView(texture, LayoutParams(MATCH, MATCH))
    }

    private fun start(st: SurfaceTexture) {
        stop()
        try {
            val mp = MediaPlayer()
            val afd = context.assets.openFd(path)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            // Real picture size as it is shown: phone clips are often stored sideways with a
            // "rotate 90" note, and the player's own size report can ignore that note.
            fixedSize = false
            try {
                val mr = MediaMetadataRetriever()
                mr.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                val w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                mr.release()
                if (w > 0 && h > 0) {
                    val sideways = rot == 90 || rot == 270
                    videoW = if (sideways) h else w
                    videoH = if (sideways) w else h
                    fixedSize = true
                }
            } catch (t: Throwable) {
                // Falls back to the size the player reports.
            }
            afd.close()
            val s = Surface(st)
            surface = s
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (!fixedSize) {
                    videoW = w
                    videoH = h
                }
                applyCrop()
            }
            mp.setOnPreparedListener { p -> if (wantPlaying) p.start() }
            mp.setOnErrorListener { _, _, _ -> true }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            stop()
        }
    }

    private fun stop() {
        try {
            player?.release()
        } catch (t: Throwable) {
            // Already gone.
        }
        player = null
        try {
            surface?.release()
        } catch (t: Throwable) {
            // Already gone.
        }
        surface = null
    }

    private fun applyCrop() {
        if (videoW == 0 || videoH == 0 || width == 0 || height == 0) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale = max(vw / videoW, vh / videoH)
        val m = Matrix()
        m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f)
        texture.setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        applyCrop()
    }

    fun resume() {
        wantPlaying = true
        try {
            player?.start()
        } catch (t: Throwable) {
            // Not prepared yet; the prepared listener starts it.
        }
    }

    fun pause() {
        wantPlaying = false
        try {
            player?.pause()
        } catch (t: Throwable) {
            // Nothing to pause.
        }
    }

    fun release() {
        wantPlaying = false
        stop()
    }
}

/**
 * The animated picture area of the poster panel. In order of preference it shows:
 *   assets/ui/poster.mp4            looping muted video (your animation)
 *   assets/ui/poster.gif / .webp    animated image
 *   assets/ui/poster.png / .jpg     still image
 *   the game's own cover art, slowly drifting, with rising embers
 */
class StageView(context: Context, cover: Bitmap?) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private var video: LoopVideoView? = null
    private var animated: AnimatedImageDrawable? = null
    private val drift = ArrayList<android.animation.ObjectAnimator>()

    init {
        val r = 6f * d
        setBackgroundColor(Color.BLACK)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, r)
            }
        }

        val names = uiAssets(context)
        val mp4 = names.firstOrNull { it.startsWith("poster.", true) && ext(it) == "mp4" }
        val anim = names.firstOrNull { it.startsWith("poster.", true) && ext(it) in listOf("gif", "webp") }
        val still = names.firstOrNull { it.startsWith("poster.", true) && ext(it) in listOf("png", "jpg", "jpeg") }

        var custom = false
        if (mp4 != null) {
            val v = LoopVideoView(context, "ui/$mp4")
            video = v
            addView(v, LayoutParams(MATCH, MATCH))
            custom = true
        } else if (anim != null && Build.VERSION.SDK_INT >= 28) {
            try {
                val src = ImageDecoder.createSource(context.assets, "ui/$anim")
                val dr = ImageDecoder.decodeDrawable(src)
                val iv = ImageView(context)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageDrawable(dr)
                addView(iv, LayoutParams(MATCH, MATCH))
                if (dr is AnimatedImageDrawable) {
                    dr.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    animated = dr
                }
                custom = true
            } catch (t: Throwable) {
                // Falls through to the next option.
            }
        }
        if (!custom && still != null) {
            val bmp = decodeAssetBitmap(context, "ui/$still")
            if (bmp != null) {
                val iv = ImageView(context)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageBitmap(bmp)
                addView(iv, LayoutParams(MATCH, MATCH))
                custom = true
            }
        }

        if (!custom) {
            addView(GlowView(context), LayoutParams(MATCH, MATCH))
            if (cover != null) {
                val iv = ImageView(context)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageBitmap(cover)
                addView(iv, LayoutParams(MATCH, MATCH))
                for (prop in listOf(View.SCALE_X, View.SCALE_Y)) {
                    val a = android.animation.ObjectAnimator.ofFloat(iv, prop, 1.0f, 1.14f)
                    a.duration = 9000
                    a.repeatCount = android.animation.ObjectAnimator.INFINITE
                    a.repeatMode = android.animation.ObjectAnimator.REVERSE
                    drift.add(a)
                }
            }
            addView(EmberView(context), LayoutParams(MATCH, MATCH))
        }

        // Soft fade at the bottom and a red frame on top of whatever is playing.
        val fade = View(context)
        fade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(0, 5, 6, 14), Color.argb(150, 5, 6, 14))
        )
        addView(fade, LayoutParams(MATCH, MATCH))

        val frame = GradientDrawable()
        frame.setColor(Color.TRANSPARENT)
        frame.setStroke((1.5f * d).toInt().coerceAtLeast(1), Nk.ACCENT)
        frame.cornerRadius = r
        foreground = frame
    }

    fun resume() {
        video?.resume()
        animated?.start()
        for (a in drift) if (!a.isStarted) a.start()
    }

    fun pause() {
        video?.pause()
        animated?.stop()
        for (a in drift) a.cancel()
    }

    fun release() {
        pause()
        video?.release()
    }
}

// ====================================================================================
// Panels, chips, toggle, play button
// ====================================================================================

class DiamondView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        path.reset()
        path.moveTo(w / 2f, 0f)
        path.lineTo(w.toFloat(), h / 2f)
        path.lineTo(w / 2f, h.toFloat())
        path.lineTo(0f, h / 2f)
        path.close()
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Nk.SILVER, Nk.ACCENT, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawPath(path, paint)
    }
}

/**
 * Dark panel with cut corners and orange corner brackets. Pass a [title] for the small
 * diamond-and-caps header, and an optional [action] view (such as a toggle) at its right end.
 */
class NTile(context: Context, title: String?, action: View? = null) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Nk.PANEL
    }
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        color = Color.argb(70, 255, 95, 105)
    }
    private val brk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = Nk.ACCENT
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val body = Path()
    private val corners = Path()

    /** Clear body, so a picture or video behind the tile shows through. */
    var glass = false
        set(v) {
            field = v
            fillP.color = if (v) Color.TRANSPARENT else Nk.PANEL
            invalidate()
        }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding((13 * d).toInt(), (10 * d).toInt(), (13 * d).toInt(), (11 * d).toInt())

        if (title != null) {
            val head = LinearLayout(context)
            head.orientation = HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL

            val dlp = LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt())
            dlp.rightMargin = (7 * d).toInt()
            head.addView(DiamondView(context), dlp)

            val tv = context.nText(title.uppercase(), 11f, Nk.ACCENT, true)
            tv.letterSpacing = 0.14f
            head.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (action != null) {
                head.addView(
                    action,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            val hlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            hlp.bottomMargin = (8 * d).toInt()
            addView(head, hlp)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val cut = 10f * d
        val half = edgeP.strokeWidth / 2f
        val wf = w.toFloat()
        val hf = h.toFloat()

        body.reset()
        body.moveTo(cut, half)
        body.lineTo(wf - half, half)
        body.lineTo(wf - half, hf - cut)
        body.lineTo(wf - cut, hf - half)
        body.lineTo(half, hf - half)
        body.lineTo(half, cut)
        body.close()

        val len = 14f * d
        corners.reset()
        // top-left and bottom-right follow the diagonal cut
        corners.moveTo(half, cut + len * 0.7f)
        corners.lineTo(half, cut)
        corners.lineTo(cut, half)
        corners.lineTo(cut + len * 0.7f, half)
        corners.moveTo(wf - half, hf - cut - len * 0.7f)
        corners.lineTo(wf - half, hf - cut)
        corners.lineTo(wf - cut, hf - half)
        corners.lineTo(wf - cut - len * 0.7f, hf - half)
        // top-right and bottom-left are square
        corners.moveTo(wf - len, half)
        corners.lineTo(wf - half, half)
        corners.lineTo(wf - half, len)
        corners.moveTo(half, hf - len)
        corners.lineTo(half, hf - half)
        corners.lineTo(len, hf - half)
    }

    override fun onDraw(c: Canvas) {
        c.drawPath(body, fillP)
        c.drawPath(body, edgeP)
        c.drawPath(corners, brk)
        super.onDraw(c)
    }
}

/** Slanted option button. [sub] is an optional small caption under the label. */
class NChip(context: Context, private val text: String, private val sub: String?) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Nk.FONT
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12.5f, resources.displayMetrics)
    }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Nk.FONT_REG
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
    }
    private val path = Path()
    private val argb = ArgbEvaluator()
    private var grad: LinearGradient? = null
    private var amount = 0f
    private var anim: ValueAnimator? = null

    var chosen = false
        private set

    init {
        isClickable = true
        isFocusable = true
    }

    fun setChosen(v: Boolean, animated: Boolean) {
        chosen = v
        anim?.cancel()
        val to = if (v) 1f else 0f
        if (!animated) {
            amount = to
            invalidate()
            return
        }
        val a = ValueAnimator.ofFloat(amount, to)
        a.duration = 230
        a.addUpdateListener {
            amount = it.animatedValue as Float
            invalidate()
        }
        anim = a
        a.start()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val want = (tp.measureText(text) + 30f * d).toInt()
        val h = ((if (sub != null) 41 else 33) * d).toInt()
        setMeasuredDimension(resolveSize(want, widthSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        grad = LinearGradient(0f, 0f, 0f, h.toFloat(), Nk.ACCENT_HI, Nk.ACCENT_LO, Shader.TileMode.CLAMP)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.94f else 1f
        animate().scaleX(s).scaleY(s).setDuration(110).start()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val k = 5f * d
        path.reset()
        path.moveTo(k, 0f)
        path.lineTo(w, 0f)
        path.lineTo(w - k, h)
        path.lineTo(0f, h)
        path.close()

        if (amount > 0.01f) {
            glow.color = Color.argb((80 * amount).toInt(), 236, 40, 58)
            c.save()
            c.scale(1.06f, 1.2f, w / 2f, h / 2f)
            c.drawPath(path, glow)
            c.restore()
        }

        fill.shader = null
        fill.color = Nk.CHIP
        c.drawPath(path, fill)
        if (amount > 0.01f) {
            fill.shader = grad
            fill.alpha = (255 * amount).toInt()
            c.drawPath(path, fill)
            fill.shader = null
        }

        line.color = argb.evaluate(amount, Color.argb(48, 255, 255, 255), Nk.SILVER) as Int
        c.drawPath(path, line)

        val textColor = argb.evaluate(amount, Color.parseColor("#C4C9DE"), Color.WHITE) as Int
        tp.color = textColor
        val cx = w / 2f + k * 0.3f
        if (sub != null) {
            val fm = tp.fontMetrics
            c.drawText(text, cx, h * 0.45f - (fm.ascent + fm.descent) / 2f, tp)
            sp.color = argb.evaluate(amount, Nk.DIM, Color.argb(235, 255, 244, 224)) as Int
            c.drawText(sub, cx, h * 0.84f, sp)
        } else {
            val fm = tp.fontMetrics
            c.drawText(text, cx, h / 2f - (fm.ascent + fm.descent) / 2f, tp)
        }
    }
}

/** A row of equally wide chips where exactly one is selected. */
fun Context.chipRow(
    labels: List<String>,
    subs: List<String?>?,
    selected: Int,
    onSelect: (Int) -> Unit
): LinearLayout {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    val chips = ArrayList<NChip>()
    for (i in labels.indices) {
        val chip = NChip(this, labels[i], subs?.getOrNull(i))
        chip.setChosen(i == selected, false)
        chip.setOnClickListener {
            for (j in chips.indices) chips[j].setChosen(j == i, true)
            onSelect(i)
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(2), 0, dp(2), 0)
        row.addView(chip, lp)
        chips.add(chip)
    }
    return row
}

/** Pill switch whose thumb is a little colour-timer diamond that spins when you flip it. */
class NToggle(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val argb = ArgbEvaluator()
    private val hexThumb = hexPath()
    private val rect = android.graphics.RectF()
    private var amount = 0f
    private var anim: ValueAnimator? = null

    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { setChecked(!checked, true, true) }
    }

    fun setChecked(v: Boolean, animated: Boolean, notify: Boolean = false) {
        checked = v
        anim?.cancel()
        val to = if (v) 1f else 0f
        if (!animated) {
            amount = to
            invalidate()
        } else {
            val a = ValueAnimator.ofFloat(amount, to)
            a.duration = 300
            a.interpolator = OvershootInterpolator(1.4f)
            a.addUpdateListener {
                amount = it.animatedValue as Float
                invalidate()
            }
            anim = a
            a.start()
        }
        if (notify) onChange?.invoke(v)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension((50 * d).toInt(), (28 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val a = amount.coerceIn(0f, 1f)
        rect.set(1f * d, 1f * d, w - 1f * d, h - 1f * d)

        track.color = argb.evaluate(a, Color.argb(235, 24, 26, 32), Nk.ACCENT_LO) as Int
        c.drawRoundRect(rect, h / 2f, h / 2f, track)
        edge.color = argb.evaluate(a, Color.argb(60, 255, 255, 255), Nk.SILVER) as Int
        c.drawRoundRect(rect, h / 2f, h / 2f, edge)

        val r = h * 0.36f
        val cx = h / 2f + (w - h) * amount
        val cy = h / 2f
        thumb.color = argb.evaluate(a, Nk.DIM, Color.WHITE) as Int
        c.save()
        c.translate(cx, cy)
        c.rotate(amount * 360f)
        thumb.style = Paint.Style.FILL
        c.save()
        c.scale(r * 1.3f, r * 1.3f)
        c.drawPath(hexThumb, thumb)
        c.restore()
        thumb.style = Paint.Style.FILL
        c.restore()
    }
}

/** A labelled switch row (title, small caption, toggle) for dialogs. */
fun Context.toggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(0, dp(7), 0, dp(7))

    val texts = LinearLayout(this)
    texts.orientation = LinearLayout.VERTICAL
    texts.addView(nText(title, 14f, Nk.TEXT, true))
    if (sub != null) texts.addView(nText(sub, 11f, Nk.DIM))
    row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

    val t = NToggle(this)
    t.setChecked(checked, false)
    t.onChange = onChange
    row.addView(t)
    return row
}

/** Big hexagon PLAY button: pulsing outline, travelling shine, silver trim. */
class PlayButton(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Nk.FONT
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        letterSpacing = 0.2f
    }
    private val outer = Path()
    private val inner = Path()
    private var phase = 0f
    private var sheen: LinearGradient? = null
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    var label: String = "FIGHT"
        set(v) {
            field = v
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
    }

    private fun hex(p: Path, l: Float, t: Float, r: Float, b: Float, tip: Float) {
        val mid = (t + b) / 2f
        p.reset()
        p.moveTo(l, mid)
        p.lineTo(l + tip, t)
        p.lineTo(r - tip, t)
        p.lineTo(r, mid)
        p.lineTo(r - tip, b)
        p.lineTo(l + tip, b)
        p.close()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(resolveSize(0, widthSpec), (56 * d).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val tip = hf * 0.42f
        hex(outer, 1f * d, 1f * d, wf - 1f * d, hf - 1f * d, tip)
        val pad = 6f * d
        hex(inner, pad, pad, wf - pad, hf - pad, tip - pad * 0.4f)
        fillP.shader = LinearGradient(
            0f, 0f, wf, 0f,
            intArrayOf(Color.parseColor("#FF5A64"), Nk.ACCENT, Color.parseColor("#8E0F1C")),
            null, Shader.TileMode.CLAMP
        )
        sheen = LinearGradient(
            0f, 0f, 36f * d, 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.97f else 1f
        animate().scaleX(s).scaleY(s).setDuration(110).start()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pulse = ((sin(phase * 2.0 * PI) + 1.0) / 2.0).toFloat()

        strokeP.strokeWidth = 1.4f * d
        strokeP.color = Color.argb((60 + 140 * pulse).toInt(), 255, 100, 110)
        c.drawPath(outer, strokeP)

        c.drawPath(inner, fillP)

        c.save()
        c.clipPath(inner)
        // soft top highlight
        sheenP.shader = null
        sheenP.color = Color.argb(46, 255, 255, 255)
        c.drawRect(0f, 0f, w, h * 0.5f, sheenP)
        // travelling shine, once per cycle
        val sweep = phase / 0.5f
        if (sweep in 0f..1f) {
            val x = -40f * d + (w + 80f * d) * sweep
            c.save()
            c.translate(x, 0f)
            c.skew(-0.35f, 0f)
            sheenP.shader = sheen
            c.drawRect(0f, 0f, 36f * d, h, sheenP)
            sheenP.shader = null
            c.restore()
        }
        c.restore()

        strokeP.strokeWidth = 2f * d
        strokeP.color = Nk.SILVER
        c.drawPath(inner, strokeP)

        textP.textSize = h * (if (label.length > 6) 0.27f else 0.38f)
        textP.setShadowLayer(4f * d, 0f, 2f * d, Color.argb(120, 80, 0, 0))
        val fm = textP.fontMetrics
        c.drawText(label, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, textP)
    }
}


// ====================================================================================
// Shared helpers for dialogs and buttons (used by the launcher and the layout editor)
// ====================================================================================

fun Context.nButton(text: String, primary: Boolean, onClick: () -> Unit): NChip {
    val c = NChip(this, text, null)
    c.setChosen(primary, false)
    c.setOnClickListener { onClick() }
    return c
}

/** Themed pop-up panel. The last button is drawn as the main one. */
fun Context.nPanel(
    title: String,
    body: View,
    buttons: List<Pair<String, () -> Unit>>,
    cancelable: Boolean = true
): Dialog {
    val dlg = Dialog(this)
    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

    val tile = NTile(this, title, null)
    tile.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
    if (buttons.isNotEmpty()) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        for (i in buttons.indices) {
            val b = buttons[i]
            val btn = nButton(b.first, i == buttons.size - 1) {
                dlg.dismiss()
                b.second()
            }
            val blp = LinearLayout.LayoutParams(WRAP, WRAP)
            blp.leftMargin = dp(6)
            btn.minimumWidth = dp(84)
            row.addView(btn, blp)
        }
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.topMargin = dp(12)
        tile.addView(row, rlp)
    }

    val scroll = ScrollView(this)
    scroll.isVerticalScrollBarEnabled = false
    scroll.addView(tile, FrameLayout.LayoutParams(MATCH, WRAP))
    dlg.setContentView(scroll)
    dlg.setCancelable(cancelable)
    dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    val width = min((resources.displayMetrics.widthPixels * 0.9f).toInt(), dp(460))
    dlg.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    dlg.show()
    return dlg
}
