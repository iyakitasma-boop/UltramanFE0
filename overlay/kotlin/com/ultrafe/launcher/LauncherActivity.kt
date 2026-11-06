package com.ultrafe.launcher

import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.ppsspp.ppsspp.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Landscape home screen: poster and FIGHT on the left, simple setting tiles on the right.
 * Settings are written to the engine's config when Play is pressed, and the game runs in
 * its own process, so what you pick here is always what the game uses.
 */
class LauncherActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var device: DeviceProfile
    private lateinit var backdrop: BackdropView
    private lateinit var holder: FrameLayout

    private val worker = Executors.newSingleThreadExecutor()
    private val animators = ArrayList<ObjectAnimator>()
    private var busy = false
    private var firstBuild = true
    private var saveText: TextView? = null
    private var texText: TextView? = null
    private var stage: StageView? = null

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importIso(uri)
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) importFolder(uri)
    }
    private val saveImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) confirmSaveImport(uri)
    }
    private val saveExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) doSaveExport(uri)
    }
    private val textureImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) confirmModpack(uri)
    }

    // ------------------------------------------------------------------ lifecycle

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
        device = DeviceProfile.detect(this)
        if (!prefs.configured) Defaults.apply(prefs, device)

        backdrop = BackdropView(this)
        holder = FrameLayout(this)
        holder.applyInsetsPadding()

        val fx = FxLayout(this)
        fx.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
        fx.addView(holder, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(fx)

        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        stage?.resume()
        refreshSave()
        refreshTextures()
    }

    override fun onPause() {
        stage?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        for (a in animators) a.cancel()
        animators.clear()
        stage?.release()
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun render() {
        holder.removeAllViews()
        saveText = null
        texText = null
        stage?.release()
        stage = null

        val root = LinearLayout(this)
        root.orientation = LinearLayout.HORIZONTAL
        root.setPadding(dp(18), dp(14), dp(18), dp(14))
        holder.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))

        val poster = posterPanel()
        root.addView(poster, LinearLayout.LayoutParams(0, MATCH, 0.36f))

        val pane = settingsPane()
        val plp = LinearLayout.LayoutParams(0, MATCH, 0.64f)
        plp.leftMargin = dp(14)
        root.addView(pane.first, plp)

        refreshSave()
        refreshTextures()

        if (firstBuild) {
            firstBuild = false
            appear(poster, 0L)
            var i = 1
            for (v in pane.second) {
                appear(v, 70L * i)
                i++
            }
        }
    }

    private fun appear(v: View, delay: Long) {
        v.alpha = 0f
        v.translationY = dp(18).toFloat()
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    private fun lp(top: Int = 0, bottom: Int = 0, w: Int = MATCH, h: Int = WRAP): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.topMargin = dp(top)
        p.bottomMargin = dp(bottom)
        return p
    }

    // ------------------------------------------------------------------ poster panel

    private fun posterPanel(): View {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER

        val tile = NTile(this, null)
        scroll.addView(tile, FrameLayout.LayoutParams(MATCH, MATCH))

        // Logo + names
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val customLogo = uiAssets(this).any { it.equals("custom_logo.txt", ignoreCase = true) }
        val logo = ImageView(this)
        var logoRes = 0
        if (customLogo) {
            // The picture itself (resource ni_logo), looked up by name because it only exists
            // when a logo was added at build time.
            logoRes = resources.getIdentifier("ni_logo", "drawable", packageName)
            if (logoRes == 0) logoRes = resources.getIdentifier("ni_logo", "drawable", "org.ppsspp.ppsspp")
        }
        if (customLogo && logoRes != 0) {
            logo.setImageResource(logoRes)
            logo.scaleType = ImageView.ScaleType.CENTER_CROP
            logo.clipToOutline = true
            logo.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
                }
            }
        } else {
            logo.setImageResource(R.drawable.ic_launcher_foreground)
        }
        val logoSize = if (customLogo && logoRes != 0) dp(62) else dp(58)
        head.addView(logo, LinearLayout.LayoutParams(logoSize, logoSize))
        if (!(customLogo && logoRes != 0)) {
            // The built-in wheel turns slowly; a logo picture of your own stays still.
            val spin = ObjectAnimator.ofFloat(logo, View.ROTATION, 0f, 360f)
            spin.duration = 26000
            spin.repeatCount = ObjectAnimator.INFINITE
            spin.interpolator = LinearInterpolator()
            spin.start()
            animators.add(spin)
        }

        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        names.setPadding(dp(6), 0, 0, 0)
        val name = nText("ULTRAMAN", 22f, Nk.TEXT, true)
        name.letterSpacing = 0.1f
        names.addView(name)
        val game = prefs.isoTitle ?: "Fight Evolution 0"
        val sub = nText(game, 11.5f, Nk.ACCENT_HI)
        sub.maxLines = 2
        names.addView(sub)
        names.addView(nText("${appVersion()} · by #ditz", 10.5f, Nk.DIM))
        head.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        tile.addView(head, lp())

        // Animated picture area. With your own poster (video, gif, picture) it fills the whole
        // panel behind everything, so a tall 9:16 clip is cropped as little as possible. Without
        // one, the game's cover art with streaks sits in the free space between title and button.
        val iconFile = File(filesDir, "icon0.png")
        val cover = if (iconFile.exists()) BitmapFactory.decodeFile(iconFile.path) else null
        val customPoster = uiAssets(this).any { n ->
            n.startsWith("poster.", true) &&
                n.substringAfterLast('.', "").lowercase() in listOf("mp4", "gif", "webp", "png", "jpg", "jpeg")
        }
        val st = StageView(this, cover)
        stage = st
        if (customPoster) {
            tile.glass = true
            tile.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))
        } else {
            st.minimumHeight = dp(70)
            val slp = LinearLayout.LayoutParams(MATCH, 0, 1f)
            slp.topMargin = dp(10)
            tile.addView(st, slp)
        }
        st.resume()

        val hasGame = prefs.isoUri != null
        val play = PlayButton(this)
        play.label = if (hasGame) "FIGHT" else "IMPORT GAME"
        play.setOnClickListener { if (hasGame) play() else pickGame() }
        tile.addView(play, lp(top = 10))

        // Game file line
        val file = LinearLayout(this)
        file.orientation = LinearLayout.VERTICAL
        file.gravity = Gravity.CENTER_HORIZONTAL
        file.setPadding(0, dp(7), 0, dp(3))
        val fname = nText(
            if (hasGame) (prefs.isoName ?: prefs.isoTitle ?: "Imported game") else "No game imported",
            12f, Nk.TEXT, true
        )
        fname.maxLines = 1
        fname.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        file.addView(fname)
        val state = StringBuilder()
        if (hasGame) {
            val id = prefs.isoDiscId
            if (!id.isNullOrBlank()) state.append(id).append("  ·  ")
            if (prefs.isoSize > 0) state.append(fmtBytes(prefs.isoSize)).append("  ·  ")
            state.append(if (prefs.isoVerified) "verified  ·  " else "unverified  ·  ")
            state.append("tap to change")
        } else {
            state.append("tap to choose your game file")
        }
        file.addView(nText(state.toString(), 10.5f, Nk.DIM))
        file.isClickable = true
        file.setOnClickListener { pickGame() }
        tile.addView(file, lp())

        // Footer links
        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.gravity = Gravity.CENTER_HORIZONTAL
        foot.addView(link("About") { showAbout() })
        foot.addView(dot())
        foot.addView(link("Licenses") { showLicenses() })
        foot.addView(dot())
        foot.addView(link("Clear cache") { clearCache() })
        foot.addView(dot())
        foot.addView(link("Reset") { confirmReset() })
        tile.addView(foot, lp(top = 2))

        if (!customPoster) return scroll

        val frame = FrameLayout(this)
        frame.addView(st, FrameLayout.LayoutParams(MATCH, MATCH))
        val scrim = View(this)
        scrim.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(175, 5, 6, 9), Color.argb(0, 5, 6, 9),
                Color.argb(0, 5, 6, 9), Color.argb(215, 5, 6, 9)
            )
        )
        frame.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        return frame
    }

    private fun link(text: String, onClick: () -> Unit): TextView {
        val t = nText(text, 11f, Nk.DIM)
        t.setPadding(dp(8), dp(6), dp(8), dp(4))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun dot(): TextView {
        val t = nText("◆", 6f, Nk.ACCENT_LO)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(2), 0, 0)
        return t
    }

    // ------------------------------------------------------------------ settings pane

    /** Returns the scroll view and the tiles in the order they should animate in. */
    private fun settingsPane(): Pair<View, List<View>> {
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipToPadding = false

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))

        val shown = ArrayList<View>()

        val res = resolutionTile()
        col.addView(res, lp(bottom = 8))
        shown.add(res)

        val cols = LinearLayout(this)
        cols.orientation = LinearLayout.HORIZONTAL
        val a = LinearLayout(this)
        a.orientation = LinearLayout.VERTICAL
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        val alp = LinearLayout.LayoutParams(0, WRAP, 1f)
        alp.rightMargin = dp(4)
        val blp = LinearLayout.LayoutParams(0, WRAP, 1f)
        blp.leftMargin = dp(4)
        cols.addView(a, alp)
        cols.addView(b, blp)
        col.addView(cols, lp())

        fun put(into: LinearLayout, v: View) {
            into.addView(v, lp(bottom = 8))
            shown.add(v)
        }

        put(a, screenTile())
        put(b, frameSkipTile())
        put(a, aaTile())
        put(b, boostTile())
        put(a, apiTile())
        put(b, saveTile())
        put(a, fpsTile())
        put(b, textureTile())
        put(a, controlsTile())

        return Pair(scroll, shown)
    }

    private fun tileOf(title: String, action: View?, body: View?, hint: String?): NTile {
        val t = NTile(this, title, action)
        if (body != null) t.addView(body, lp())
        if (hint != null) t.addView(nText(hint, 10.5f, Nk.DIM), lp(top = 6))
        return t
    }

    private fun resText(): String {
        val m = prefs.resolution
        return "${480 * m}×${272 * m}  ·  ${m}×"
    }

    private fun resolutionTile(): NTile {
        val info = nText(resText(), 11f, Nk.SILVER, true)
        val row = chipRow(Res.LABELS, null, prefs.resTier.coerceIn(0, Res.LABELS.size - 1)) { i ->
            prefs.resTier = i
            info.text = resText()
        }
        return tileOf(
            "Resolution", info, row,
            "The game draws in fixed steps, so 480p and 540p use the same step."
        )
    }

    private fun screenTile(): NTile {
        val row = chipRow(listOf("16:9", "Stretch"), null, if (prefs.stretch) 1 else 0) { i ->
            prefs.stretch = i == 1
        }
        return tileOf("Screen", null, row, null)
    }

    private fun frameSkipTile(): NTile {
        val row = chipRow(listOf("Off", "Auto", "1", "2"), null, prefs.frameMode.coerceIn(0, 3)) { i ->
            prefs.frameMode = i
        }
        return tileOf("Frame skip", null, row, null)
    }

    private fun aaTile(): NTile {
        val row = chipRow(listOf("Off", "FXAA", "MSAA"), null, prefs.aa.coerceIn(0, 2)) { i ->
            prefs.aa = i
        }
        return tileOf("Anti-aliasing", null, row, null)
    }

    private fun apiTile(): NTile {
        val row = chipRow(listOf("Vulkan", "OpenGL ES"), null, if (prefs.backend == BACKEND_VULKAN) 0 else 1) { i ->
            prefs.backend = if (i == 0) BACKEND_VULKAN else BACKEND_OPENGL
        }
        val hint = if (device.vulkan11) null else "Vulkan 1.1 wasn't found on this phone."
        return tileOf("Graphics API", null, row, hint)
    }

    private fun toggleTile(title: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit): NTile {
        val tg = NToggle(this)
        tg.setChecked(value, false)
        tg.onChange = onChange
        return tileOf(title, tg, null, hint)
    }

    private fun boostTile(): NTile {
        val hint = if (device.isMediaTek) "Locks 60 Hz and holds steady clocks. Tuned for MediaTek."
        else "Locks 60 Hz and holds steady clocks."
        return toggleTile("Performance boost", hint, prefs.boost) { prefs.boost = it }
    }

    private fun fpsTile(): NTile =
        toggleTile("FPS panel", "Shows FPS and speed in game.", prefs.showFps) { prefs.showFps = it }

    private fun saveTile(): NTile {
        val txt = nText("", 12f, Nk.TEXT)
        saveText = txt
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val imp = nButton("Import", true) { startSaveImport() }
        val exp = nButton("Export", false) { startSaveExport() }
        val l1 = LinearLayout.LayoutParams(0, WRAP, 1f)
        l1.rightMargin = dp(3)
        val l2 = LinearLayout.LayoutParams(0, WRAP, 1f)
        l2.leftMargin = dp(3)
        row.addView(imp, l1)
        row.addView(exp, l2)

        val t = NTile(this, "Game save", null)
        t.addView(txt, lp(bottom = 8))
        t.addView(row, lp())
        return t
    }

    private fun textureTile(): NTile {
        val tg = NToggle(this)
        tg.setChecked(prefs.textureMods, false)
        tg.onChange = { prefs.textureMods = it }
        val txt = nText("", 12f, Nk.TEXT)
        texText = txt
        val t = NTile(this, "Texture mods", tg)
        t.addView(txt, lp(bottom = 8))
        t.addView(nButton("Import modpack (.zip)", false) { startTextureImport() }, lp())
        if (hasBundledMods()) {
            t.addView(nText("A modpack is built into this app and installs automatically.", 10.5f, Nk.DIM), lp(top = 6))
        }
        return t
    }

    private fun controlsTile(): NTile {
        val tg = NToggle(this)
        tg.setChecked(prefs.touchControls, false)
        tg.onChange = { prefs.touchControls = it }
        val t = NTile(this, "Controls", tg)
        t.addView(nText("Move, resize and restyle every button.", 11.5f, Nk.DIM), lp(bottom = 8))
        t.addView(nButton("Edit layout", true) {
            startActivity(Intent(this, LayoutEditorActivity::class.java))
        }, lp())
        return t
    }

    // ------------------------------------------------------------------ status lines

    private fun refreshSave() {
        val t = saveText ?: return
        val info = SaveManager.info(this, prefs.isoDiscId)
        if (info.folders == 0) {
            t.text = "No save data yet"
        } else {
            val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(info.modified))
            val slots = if (info.folders == 1) "1 save" else "${info.folders} saves"
            t.text = "$slots  ·  ${fmtBytes(info.bytes)}  ·  $date"
        }
    }

    private fun refreshTextures() {
        val t = texText ?: return
        val n = TextureMods.fileCount(this, prefs.isoDiscId)
        t.text = if (n == 0) "No texture pack installed" else "$n files installed"
    }

    private fun fmtBytes(bytes: Long): String {
        val gb = bytes / 1073741824.0
        if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb)
        val mb = bytes / 1048576.0
        if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    // ------------------------------------------------------------------ dialogs

    private fun panel(
        title: String,
        body: View,
        buttons: List<Pair<String, () -> Unit>>,
        cancelable: Boolean = true
    ): Dialog = nPanel(title, body, buttons, cancelable)

    private fun message(text: String): TextView {
        val t = nText(text, 13f, Nk.TEXT)
        t.setLineSpacing(0f, 1.1f)
        return t
    }

    private fun info(title: String, text: String) {
        panel(title, message(text), listOf(Pair("OK") {}))
    }

    private fun busyDialog(text: String): Dialog = panel("Please wait", message(text), emptyList(), false)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun showAbout() {
        val text = "Ultraman Fight Evolution 0 ${appVersion()}, made by #ditz.\n\n" +
            "A standalone fighting game app. Import your own copy of the game once, then play with " +
            "touch controls built for it: a glowing movement ring and colour-timer buttons that you " +
            "can move, resize and remap.\n\n" +
            "• Resolution from 360p up to 4K, frame skip and anti-aliasing\n" +
            "• Live FPS, CPU and GPU panel you can drag anywhere\n" +
            "• Save import and export, texture mods and mod packs\n" +
            "• Works with disc image files or an extracted game folder\n\n" +
            "Nothing else needs to be installed. No game files are included, so you supply your own copy. " +
            "Not affiliated with or endorsed by any publisher. Ultraman and related names belong to their owners."
        info("About", text)
    }

    /** Open-source notices. These have to stay: the game engine inside this app is GPLv2 software. */
    private fun showLicenses() {
        val text = "This app contains the open-source PPSSPP engine (copyright the PPSSPP Project, GNU GPL v2).\n" +
            "Website: ppsspp.org\nSource: github.com/hrydgard/ppsspp\n\n" +
            "The source code of this app's own launcher and controls is available from its creator on request."
        info("Open-source licenses", text)
    }

    private fun clearCache() {
        val freed = EmulatorBridge.clearShaderCache(this)
        toast("Cleared " + String.format(Locale.US, "%.1f", freed / 1048576.0) + " MB of shader cache")
    }

    private fun confirmReset() {
        panel(
            "Reset settings?",
            message("Every setting returns to the default for this phone. Your game and saves are kept."),
            listOf(
                Pair("Cancel") {},
                Pair("Reset") {
                    prefs.resetSettings()
                    Defaults.apply(prefs, device)
                    render()
                }
            )
        )
    }

    // ------------------------------------------------------------------ ISO import

    private fun pickIso() {
        isoPicker.launch(arrayOf("*/*"))
    }

    /** One file (.iso .cso .chd .pbp) or an extracted game folder. */
    private fun pickGame() {
        if (busy) return
        panel(
            "Import your game",
            message(
                "A disc image is a single file (.iso, .cso or .chd). An extracted game is the " +
                    "unpacked folder that holds the game's own files. A folder is copied into the app, " +
                    "so keep enough free space for it."
            ),
            listOf(
                Pair("Cancel") {},
                Pair("Disc image") { pickIso() },
                Pair("Game folder") { folderPicker.launch(null) }
            )
        )
    }

    private fun queryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun importIso(uri: Uri) {
        if (busy) return
        busy = true
        val dlg = busyDialog("Checking your game file…")
        worker.execute {
            val name = queryName(uri)
            val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.US) ?: ""
            val supported = ext.isEmpty() || ext == "iso" || ext == "cso" || ext == "chd" || ext == "pbp"
            val found = if (supported) IsoReader.inspect(contentResolver, uri) else null
            runOnUiThread {
                busy = false
                dlg.dismiss()
                when {
                    found == null -> info("Unsupported file", "Pick a disc image (.iso, .cso or .chd), or use \"Game folder\" for an extracted game.")
                    // Mods often rename the disc, so a different title is a question, not a refusal.
                    found.verified && !found.looksRight -> panel(
                        "Not sure about this disc",
                        message(
                            "This disc is called \"${found.title}\", which doesn't look like Ultraman Fight Evolution. " +
                                "This app is made for Ultraman Fight Evolution 0. Import it anyway?"
                        ),
                        listOf(Pair("Cancel") {}, Pair("Import anyway") { acceptIso(uri, name, found) })
                    )
                    else -> acceptIso(uri, name, found)
                }
            }
        }
    }

    private fun importFolder(tree: Uri) {
        if (busy) return
        busy = true
        val dlg = busyDialog("Looking for the game in that folder…")
        worker.execute {
            val cr = contentResolver
            val where = try {
                FolderImport.locate(cr, tree)
            } catch (e: Exception) {
                null
            }
            if (where == null) {
                runOnUiThread {
                    busy = false
                    dlg.dismiss()
                    info(
                        "No game found",
                        "That folder doesn't look like an extracted game. Pick the folder that holds the " +
                            "game's own files."
                    )
                }
                return@execute
            }
            val total = try {
                FolderImport.totalSize(cr, tree, where.rootDocId)
            } catch (e: Exception) {
                0L
            }
            val meta = FolderImport.inspect(cr, tree, where, total)
            runOnUiThread {
                busy = false
                dlg.dismiss()
                if (meta.verified && !meta.looksRight) {
                    panel(
                        "Not sure about this game",
                        message(
                            "This game is called \"${meta.title}\", which doesn't look like Ultraman Fight Evolution. " +
                                "This app is made for Ultraman Fight Evolution 0. Import it anyway?"
                        ),
                        listOf(Pair("Cancel") {}, Pair("Import anyway") { copyFolder(tree, where, meta) })
                    )
                } else {
                    copyFolder(tree, where, meta)
                }
            }
        }
    }

    private fun copyFolder(tree: Uri, where: FolderImport.Found, found: IsoInfo) {
        if (busy) return
        val games = FolderImport.gamesDir(this)
        games.mkdirs()
        val need = found.sizeBytes + found.sizeBytes / 50 + 64L * 1024 * 1024
        if (found.sizeBytes > 0 && games.usableSpace < need) {
            info(
                "Not enough space",
                "This game needs about ${fmtBytes(need)} free to copy, but only " +
                    "${fmtBytes(games.usableSpace)} is available. Free some space and try again."
            )
            return
        }
        busy = true
        val text = message("Copying the game into the app…")
        val dlg = panel("Please wait", text, emptyList(), false)
        // A folder called just "GAME" says nothing; use the game's own title instead.
        val generic = listOf("game", "psp_game", "iso", "root", "psp", "data")
        val name = if (where.name.lowercase(Locale.US) in generic && !found.title.isNullOrBlank()) found.title!! else where.name
        worker.execute {
            val tmp = File(games, ".importing")
            var ok = false
            var error: String? = null
            var dest: File? = null
            try {
                tmp.deleteRecursively()
                FolderImport.copy(contentResolver, tree, where, tmp) { done ->
                    runOnUiThread {
                        val pct = if (found.sizeBytes > 0) " (${(done * 100 / found.sizeBytes).coerceIn(0, 100)}%)" else ""
                        text.text = "Copying the game into the app…\n${fmtBytes(done)}$pct"
                    }
                }
                // Replace the previous copy only after the new one is complete.
                FolderImport.deleteOurCopy(this, prefs.isoUri)
                var target = File(games, FolderImport.safeName(name))
                var n = 2
                while (target.exists()) {
                    target = File(games, FolderImport.safeName(name) + "_" + n)
                    n++
                }
                ok = tmp.renameTo(target)
                if (ok) dest = target else error = "Couldn't finish the copy."
            } catch (e: Exception) {
                error = e.message ?: "unknown error"
            }
            if (!ok) tmp.deleteRecursively()
            runOnUiThread {
                busy = false
                dlg.dismiss()
                val d = dest
                if (ok && d != null) {
                    acceptIso(Uri.fromFile(d), name, found)
                    toast("Game imported.")
                } else {
                    info("Import failed", error ?: "The game couldn't be copied.")
                }
            }
        }
    }

    private fun acceptIso(uri: Uri, name: String?, found: IsoInfo) {
        val old = prefs.isoUri
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Some providers only grant a one-off permission; playing then needs a re-import.
        }
        if (old != null && old != uri.toString()) {
            // A game folder this app copied earlier is no longer needed.
            worker.execute { FolderImport.deleteOurCopy(this, old) }
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Nothing to release.
            }
        }

        prefs.isoUri = uri.toString()
        prefs.isoName = name
        prefs.isoTitle = found.title
        prefs.isoDiscId = found.discId
        prefs.isoSize = found.sizeBytes
        prefs.isoVerified = found.verified

        val iconFile = File(filesDir, "icon0.png")
        iconFile.delete()
        val icon: Bitmap? = found.icon
        if (icon != null) {
            try {
                FileOutputStream(iconFile).use { out -> icon.compress(Bitmap.CompressFormat.PNG, 100, out) }
            } catch (e: Exception) {
                // Cover art is cosmetic.
            }
        }

        if (!found.verified) {
            toast("Imported. This file type can't be verified, so it will be loaded as is.")
        }
        render()
    }

    // ------------------------------------------------------------------ game save

    private fun askCloseGame() {
        panel(
            "Game is still running",
            message("Close the game first so its save isn't overwritten while it runs."),
            listOf(
                Pair("Cancel") {},
                Pair("Close game") {
                    EmulatorBridge.requestQuit(this)
                    toast("Closing the game… try again in a moment.")
                }
            )
        )
    }

    private fun startSaveImport() {
        if (EmulatorBridge.isRunning(this)) {
            askCloseGame()
            return
        }
        saveImportPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    }

    private fun confirmSaveImport(uri: Uri) {
        panel(
            "Import save data?",
            message("Matching saves are replaced. The old ones are copied to a backup folder first."),
            listOf(Pair("Cancel") {}, Pair("Import") { doSaveImport(uri) })
        )
    }

    private fun doSaveImport(uri: Uri) {
        val dlg = busyDialog("Importing save data…")
        worker.execute {
            var count = 0
            var error: String? = null
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't open that file.")
                count = input.use { SaveManager.import(this, it) }
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
            }
            runOnUiThread {
                dlg.dismiss()
                refreshSave()
                if (error != null) info("Import failed", error)
                else info("Save imported", "$count files imported. Start the game and load it as usual.")
            }
        }
    }

    private fun startSaveExport() {
        if (EmulatorBridge.isRunning(this)) {
            askCloseGame()
            return
        }
        if (SaveManager.info(this, prefs.isoDiscId).folders == 0) {
            info("Nothing to export", "There is no save data yet. Play and save in game first.")
            return
        }
        saveExportPicker.launch("UFE_save.zip")
    }

    private fun doSaveExport(uri: Uri) {
        val dlg = busyDialog("Exporting save data…")
        worker.execute {
            var count = 0
            var error: String? = null
            try {
                val out = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Can't write there.")
                count = out.use { SaveManager.export(this, prefs.isoDiscId, it) }
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
            }
            runOnUiThread {
                dlg.dismiss()
                if (error != null) info("Export failed", error)
                else info("Save exported", "$count files saved to the zip.")
            }
        }
    }

    // ------------------------------------------------------------------ texture mods

    private fun hasBundledMods(): Boolean = TextureMods.hasBundled(this) || ModPack.hasBundled(this)

    private fun startTextureImport() {
        textureImportPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    }

    private fun confirmModpack(uri: Uri) {
        panel(
            "Install modpack?",
            message(
                "Textures, cheats and plugins in the zip are installed. Textures go to your game's " +
                    "own ID, so the US or EU disc both work.\n\n" +
                    "If the zip has save data, it replaces matching saves. Old ones are backed up first."
            ),
            listOf(
                Pair("Cancel") {},
                Pair("Skip saves") { doModpackImport(uri, false) },
                Pair("Install all") { doModpackImport(uri, true) }
            )
        )
    }

    private fun doModpackImport(uri: Uri, includeSaves: Boolean) {
        if (includeSaves && EmulatorBridge.isRunning(this)) {
            askCloseGame()
            return
        }
        val id = prefs.isoDiscId
        val dlg = busyDialog("Installing modpack…\nThis can take a minute.")
        worker.execute {
            var result: ModPack.Result? = null
            var error: String? = null
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't open that file.")
                result = input.use { ModPack.importZip(this, id, it, includeSaves) }
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
            }
            runOnUiThread {
                dlg.dismiss()
                refreshTextures()
                refreshSave()
                val r = result
                if (r == null) {
                    info("Import failed", error ?: "Something went wrong.")
                } else {
                    prefs.textureMods = true
                    render()
                    val lines = ArrayList<String>()
                    if (r.textures > 0) lines.add("Textures: ${r.textures} files")
                    if (r.saves > 0) lines.add("Save data: ${r.saves} files")
                    if (r.cheats > 0) lines.add("Cheats: ${r.cheats} files")
                    if (r.plugins > 0) lines.add("Plugins: ${r.plugins} files")
                    info("Modpack installed", lines.joinToString("\n") + "\n\nPress Play to use it.")
                }
            }
        }
    }

    // ------------------------------------------------------------------ play

    private fun play() {
        val s = prefs.isoUri ?: return
        val uri = Uri.parse(s)
        val readable = if (uri.scheme == "file") {
            File(uri.path ?: "").isDirectory
        } else try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            pfd?.close()
            pfd != null
        } catch (e: Exception) {
            false
        }
        if (!readable) {
            toast("Can't read the game any more. Please import it again.")
            prefs.clearGame()
            File(filesDir, "icon0.png").delete()
            render()
            return
        }
        if (EmulatorBridge.isRunning(this)) askResumeOrRestart(uri) else startGame(uri)
    }

    /** Installs a texture pack bundled in the APK (first run per app version), then starts. */
    private fun startGame(uri: Uri) {
        val id = prefs.isoDiscId
        if (prefs.textureMods && hasBundledMods()) {
            val dlg = busyDialog("Preparing texture mods…")
            try {
                worker.execute {
                    try {
                        if (!id.isNullOrBlank()) TextureMods.installBundled(this, id)
                        ModPack.installBundled(this, id)
                    } catch (e: Exception) {
                        // A pack that fails to copy just means no custom textures this run.
                    }
                    runOnUiThread {
                        dlg.dismiss()
                        launchNow(uri)
                    }
                }
            } catch (e: Exception) {
                dlg.dismiss()
                launchNow(uri)
            }
        } else {
            launchNow(uri)
        }
    }

    private fun launchNow(uri: Uri) {
        try {
            EmulatorBridge.launch(this, uri)
        } catch (e: Exception) {
            toast("Couldn't start the game: ${e.message}")
        }
    }

    /**
     * A game left running in the background still has the old settings in memory. Resuming keeps
     * it as is; restarting applies whatever was changed here since.
     */
    private fun askResumeOrRestart(uri: Uri) {
        panel(
            "Game is still running",
            message("Resume where you left off, or restart to use your latest settings?"),
            listOf(
                Pair("Cancel") {},
                Pair("Restart") { restartGame(uri) },
                Pair("Resume") { if (!EmulatorBridge.bringToFront(this)) restartGame(uri) }
            )
        )
    }

    private fun restartGame(uri: Uri) {
        toast("Restarting…")
        EmulatorBridge.requestQuit(this)
        val started = SystemClock.uptimeMillis()
        val h = Handler(Looper.getMainLooper())
        val check = object : Runnable {
            override fun run() {
                val gone = !EmulatorBridge.isRunning(this@LauncherActivity)
                val timedOut = SystemClock.uptimeMillis() - started > 6000L
                if (gone || timedOut) startGame(uri) else h.postDelayed(this, 250L)
            }
        }
        h.postDelayed(check, 400L)
    }
}
