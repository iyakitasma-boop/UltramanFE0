package com.ultrafe.launcher

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject
import java.io.File

/**
 * One movable thing on the game screen: where it is, how big, whether it is shown, and which
 * game button it presses ([k], an index into [PadLayout.KEY_IDS]; -1 means "its normal one").
 */
class Elem(
    var x: Float, var y: Float, var s: Float = 1f, var v: Boolean = true,
    var c: Boolean = false, var k: Int = -1
)

/**
 * Where every control sits. Positions are fractions of the screen (0..1, centre of the item) and
 * sizes are a multiplier of the item's normal size.
 *
 * Three layers, last one wins:
 *  1. built-in defaults (below)
 *  2. the layout shipped in the APK, assets/ui/layout.json (your permanent default)
 *  3. the layout the player saved with the editor (filesDir/pad_layout.json)
 *
 * It is a plain file, not SharedPreferences, because the editor (launcher process) and the game
 * (emulator process) both read and write it.
 */
class PadLayout private constructor(val items: LinkedHashMap<String, Elem>) {

    fun get(id: String): Elem = items.getValue(id)

    fun copyFrom(other: PadLayout) {
        for ((k, e) in other.items) {
            val mine = items[k] ?: continue
            mine.x = e.x
            mine.y = e.y
            mine.s = e.s
            mine.v = e.v
            mine.c = e.c
            mine.k = e.k
        }
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        val o = JSONObject()
        for ((k, e) in items) {
            val j = JSONObject()
            j.put("x", round3(e.x))
            j.put("y", round3(e.y))
            j.put("s", round3(e.s))
            j.put("v", e.v)
            if (k == FPS) j.put("c", e.c)
            if (e.k >= 0 && k != FPS) j.put("sends", KEY_IDS[e.k.coerceIn(0, KEY_IDS.size - 1)])
            o.put(k, j)
        }
        root.put("elements", o)
        return root.toString(2)
    }

    private fun round3(f: Float): Double = Math.round(f * 1000.0) / 1000.0

    /** Reads values from [json] over what is already here. Returns false when it isn't a layout. */
    fun applyJson(json: String): Boolean {
        return try {
            val o = JSONObject(json).getJSONObject("elements")
            var any = false
            for (id in IDS) {
                val j = o.optJSONObject(id) ?: continue
                val e = items.getValue(id)
                e.x = j.optDouble("x", e.x.toDouble()).toFloat().coerceIn(0f, 1f)
                e.y = j.optDouble("y", e.y.toDouble()).toFloat().coerceIn(0f, 1f)
                e.s = j.optDouble("s", e.s.toDouble()).toFloat().coerceIn(MIN_SCALE, MAX_SCALE)
                e.v = j.optBoolean("v", e.v)
                e.c = j.optBoolean("c", e.c)
                if (j.has("sends")) e.k = KEY_IDS.indexOf(j.optString("sends"))
                any = true
            }
            any
        } catch (t: Exception) {
            false
        }
    }

    companion object {
        const val STICK = "stick"
        const val ATTACK = "attack"
        const val STRONG = "strong"
        const val JUMP = "jump"
        const val GUARD = "guard"
        const val BEAM = "beam"
        const val DASH = "dash"
        const val SELECT = "select"
        const val PAUSE = "pause"
        const val DPAD = "dpad"
        const val FPS = "fps"

        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 2.5f

        val IDS = listOf(STICK, ATTACK, STRONG, JUMP, GUARD, BEAM, DASH, SELECT, PAUSE, DPAD, FPS)

        val NAMES = mapOf(
            STICK to "Movement ring",
            ATTACK to "Attack",
            STRONG to "Strong attack",
            JUMP to "Jump",
            GUARD to "Guard",
            BEAM to "Beam",
            DASH to "Dash",
            SELECT to "Select",
            PAUSE to "Pause",
            DPAD to "Menu pad",
            FPS to "FPS panel"
        )

        /**
         * The game buttons a control can press. A guess at what the game expects is built in
         * ([DEFAULT_KEY]); each control can be pointed at any other in the editor ("Presses").
         */
        val KEY_IDS = listOf(
            "cross", "circle", "square", "triangle", "l", "r", "start", "select",
            "up", "down", "left", "right"
        )
        val KEY_NAMES = listOf(
            "✕ Cross", "○ Circle", "□ Square", "△ Triangle", "L", "R", "Start", "Select",
            "Up", "Down", "Left", "Right"
        )
        val KEY_CODES = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        )

        /** Which button each control presses unless the editor says otherwise (a best guess). */
        val DEFAULT_KEY = mapOf(
            ATTACK to 2,    // Square
            STRONG to 3,    // Triangle
            JUMP to 0,      // Cross
            GUARD to 1,     // Circle
            BEAM to 5,      // R
            DASH to 4,      // L
            SELECT to 7,    // Select
            PAUSE to 6      // Start
        )

        fun keyIndex(layout: PadLayout, id: String): Int {
            val k = layout.get(id).k
            return if (k in KEY_IDS.indices) k else (DEFAULT_KEY[id] ?: 0)
        }

        fun defaults(): PadLayout {
            val m = LinkedHashMap<String, Elem>()
            m[STICK] = Elem(0.13f, 0.72f)
            m[ATTACK] = Elem(0.875f, 0.73f)
            m[STRONG] = Elem(0.762f, 0.835f)
            m[JUMP] = Elem(0.875f, 0.47f)
            m[GUARD] = Elem(0.762f, 0.60f)
            m[BEAM] = Elem(0.955f, 0.54f)
            m[DASH] = Elem(0.075f, 0.09f)
            m[SELECT] = Elem(0.445f, 0.075f)
            m[PAUSE] = Elem(0.555f, 0.075f)
            m[DPAD] = Elem(0.085f, 0.36f, 0.72f)
            m[FPS] = Elem(0.74f, 0.075f)
            return PadLayout(m)
        }

        fun userFile(ctx: Context): File = File(ctx.filesDir, "pad_layout.json")

        /** The built-in defaults with the APK's own layout.json (if you added one) applied. */
        fun shipped(ctx: Context): PadLayout {
            val l = defaults()
            try {
                val text = ctx.assets.open("ui/layout.json").use { it.readBytes().toString(Charsets.UTF_8) }
                l.applyJson(text)
            } catch (e: Exception) {
                // No shipped layout: the built-in one is used.
            }
            return l
        }

        /** What the game and the editor start from. */
        fun load(ctx: Context): PadLayout {
            val l = shipped(ctx)
            val f = userFile(ctx)
            if (f.isFile) {
                try {
                    l.applyJson(f.readText())
                } catch (e: Exception) {
                    // A damaged file just means the shipped layout is used.
                }
            }
            return l
        }

        fun save(ctx: Context, layout: PadLayout) {
            try {
                userFile(ctx).writeText(layout.toJson())
            } catch (e: Exception) {
                // Out of space or similar: the layout simply isn't remembered.
            }
        }

        /** Forget the player's changes; the shipped layout comes back. */
        fun reset(ctx: Context) {
            userFile(ctx).delete()
        }

        fun hasUserLayout(ctx: Context): Boolean = userFile(ctx).isFile
    }
}
