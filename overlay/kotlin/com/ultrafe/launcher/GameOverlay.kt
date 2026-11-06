package com.ultrafe.launcher

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import org.ppsspp.ppsspp.PpssppActivity

/**
 * Puts the racing controller on top of the emulator screen and takes over the Back button,
 * so PPSSPP's own PSP-styled controls and menus are never shown during play.
 * Runs inside the emulator process.
 *
 * There is deliberately no pause menu: leaving the app (Home, recents) pauses the game by itself,
 * and Back asks for a second press before quitting.
 */
object GameOverlay {
    private const val OVERLAY_TAG = "hero_overlay"
    private const val BACK_WINDOW_MS = 2000L

    fun attach(activity: PpssppActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(OVERLAY_TAG) != null) return

        val prefs = Prefs(activity)

        val layout = PadLayout.load(activity)

        val root = FrameLayout(activity)
        root.tag = OVERLAY_TAG
        if (prefs.touchControls) {
            val pad = PadView(activity, prefs, layout, false) {
                leaveToLauncher(activity, "The game stopped. Returning to Ultraman Fight Evolution 0.")
            }
            root.addView(pad, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        if (prefs.showFps && layout.get(PadLayout.FPS).v) {
            val panel = FpsPanel(activity, layout, false)
            // Saves the spot / compact state. Only the FPS entry changes, so the rest of the
            // player's layout is re-read from disk to avoid overwriting it.
            panel.setOnChanged {
                val fresh = PadLayout.load(activity)
                val mine = layout.get(PadLayout.FPS)
                val theirs = fresh.get(PadLayout.FPS)
                theirs.x = mine.x
                theirs.y = mine.y
                theirs.c = mine.c
                PadLayout.save(activity, fresh)
            }
            root.addView(panel, FrameLayout.LayoutParams(WRAP, WRAP))
            root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                panel.place(r - l, b - t)
            }
            root.post { panel.place(root.width, root.height) }
        }
        activity.addContentView(root, ViewGroup.LayoutParams(MATCH, MATCH))

        // Added after PPSSPP's own callback, so it is asked first.
        var lastBack = 0L
        val back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.uptimeMillis()
                if (lastBack != 0L && now - lastBack < BACK_WINDOW_MS) {
                    leaveToLauncher(activity, null)
                } else {
                    lastBack = now
                    Toast.makeText(activity, "Press Back again to quit", Toast.LENGTH_SHORT).show()
                }
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, back)
    }

    fun leaveToLauncher(activity: PpssppActivity, message: String?) {
        if (message != null) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        backToLauncher(activity)
        activity.heroQuit()
    }

    fun backToLauncher(context: Context) {
        try {
            val i = Intent(context, LauncherActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(i)
        } catch (e: Exception) {
            // The system will still return to the previous screen.
        }
    }
}
