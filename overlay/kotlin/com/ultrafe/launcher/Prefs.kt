package com.ultrafe.launcher

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Values match PPSSPP's own enums so they can be written to ppsspp.ini as-is.
const val BACKEND_OPENGL = 0
const val BACKEND_VULKAN = 3

// Anti-aliasing choices
const val AA_OFF = 0
const val AA_FXAA = 1
const val AA_MSAA = 2

// Frame skip choices
const val SKIP_OFF = 0
const val SKIP_AUTO = 1
const val SKIP_1 = 2
const val SKIP_2 = 3

/**
 * Resolution tiles. PPSSPP can only render at whole multiples of the PSP's 480x272 screen
 * (272p, 544p, 816p, 1088p, 1360p, 1632p, 2176p), so each tile maps to the nearest multiple.
 * That makes 480p and 540p the same 2x setting.
 */
object Res {
    val LABELS = listOf("360p", "480p", "540p", "720p", "1080p", "2K", "4K")
    val MULT = intArrayOf(1, 2, 2, 3, 4, 5, 8)
    const val DEFAULT_TIER = 2
}

private class IntPref(
    private val sp: SharedPreferences,
    private val key: String,
    private val def: Int
) : ReadWriteProperty<Any?, Int> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(key, def)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        sp.edit().putInt(key, value).apply()
    }
}

private class BoolPref(
    private val sp: SharedPreferences,
    private val key: String,
    private val def: Boolean
) : ReadWriteProperty<Any?, Boolean> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(key, def)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
    }
}

private class LongPref(
    private val sp: SharedPreferences,
    private val key: String,
    private val def: Long
) : ReadWriteProperty<Any?, Long> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Long = sp.getLong(key, def)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
        sp.edit().putLong(key, value).apply()
    }
}

private class StrPref(
    private val sp: SharedPreferences,
    private val key: String
) : ReadWriteProperty<Any?, String?> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): String? = sp.getString(key, null)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
        val e = sp.edit()
        if (value == null) e.remove(key) else e.putString(key, value)
        e.apply()
    }
}

/** Everything the launcher remembers. Applied to ppsspp.ini right before each launch. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("ultrafe_prefs", Context.MODE_PRIVATE)

    // Imported game (keys start with "iso" so reset keeps them)
    var isoUri by StrPref(sp, "isoUri")
    var isoName by StrPref(sp, "isoName")
    var isoTitle by StrPref(sp, "isoTitle")
    var isoDiscId by StrPref(sp, "isoDiscId")
    var isoSize by LongPref(sp, "isoSize", 0L)
    var isoVerified by BoolPref(sp, "isoVerified", false)

    var configured by BoolPref(sp, "configured", false)

    // Graphics
    var backend by IntPref(sp, "backend", BACKEND_VULKAN)
    var resTier by IntPref(sp, "resTier", Res.DEFAULT_TIER)   // index into Res
    var aniso by IntPref(sp, "aniso", 1)                      // 0=off 1=2x 2=4x 3=8x 4=16x
    var frameMode by IntPref(sp, "frameMode", SKIP_OFF)
    var aa by IntPref(sp, "aa", AA_OFF)
    var stretch by BoolPref(sp, "stretch", false)             // false = keep 16:9, true = fill screen

    /** Internal render multiple of the PSP screen, from the selected tile. */
    val resolution: Int
        get() = Res.MULT[resTier.coerceIn(0, Res.MULT.size - 1)]

    // FPS panel
    var showFps by BoolPref(sp, "showFps", true)

    // Textures
    var textureMods by BoolPref(sp, "textureMods", true)

    // Controls
    var touchControls by BoolPref(sp, "touchControls", true)
    var touchOpacity by IntPref(sp, "touchOpacity", 65)
    var padStyle by IntPref(sp, "padStyle", 0)                // 0 = icons, 1 = words
    var haptics by BoolPref(sp, "haptics", true)
    var deadzone by IntPref(sp, "deadzone", 15)               // percent
    var sensitivity by IntPref(sp, "sensitivity", 110)        // percent

    // Device tuning (one "Performance boost" switch in the UI sets both)
    var lock60 by BoolPref(sp, "lock60", true)
    var sustained by BoolPref(sp, "sustained", true)

    var boost: Boolean
        get() = lock60 || sustained
        set(v) {
            lock60 = v
            sustained = v
        }

    /** Resets every setting but keeps the imported game. */
    fun resetSettings() {
        val e = sp.edit()
        for (k in sp.all.keys) {
            if (!k.startsWith("iso")) e.remove(k)
        }
        e.apply()
    }

    fun clearGame() {
        isoUri = null
        isoName = null
        isoTitle = null
        isoDiscId = null
        isoSize = 0L
        isoVerified = false
    }
}

/** First-run defaults, picked from what the phone can do. */
object Defaults {
    fun apply(p: Prefs, dev: DeviceProfile) {
        p.backend = if (dev.vulkan11) BACKEND_VULKAN else BACKEND_OPENGL
        p.resTier = when {
            !dev.vulkan11 -> 1        // 480p
            dev.ramGb < 4f -> 1       // 480p
            dev.ramGb < 8f -> 2       // 540p
            else -> 4                 // 1080p
        }
        p.aniso = 1
        p.frameMode = SKIP_OFF
        p.aa = AA_OFF
        p.boost = true
        p.configured = true
    }
}
