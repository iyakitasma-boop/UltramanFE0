package com.ultrafe.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.Locale

/**
 * Glue between the launcher and the embedded PPSSPP core.
 *
 * 1. Points the core at an app-private "memory stick" folder so it never asks the user
 *    to pick a storage location (memstick_dir.txt is read by the core at startup).
 * 2. Writes the launcher's settings into PSP/SYSTEM/ppsspp.ini.
 * 3. Starts the core with the imported ISO.
 */
object EmulatorBridge {

    fun memstick(ctx: Context): File {
        val ext = ctx.getExternalFilesDir(null)
        if (ext != null) return ext
        val fallback = File(ctx.filesDir, "memstick")
        fallback.mkdirs()
        return fallback
    }

    private fun flag(v: Boolean) = if (v) "True" else "False"
    private fun f2(v: Float) = String.format(Locale.US, "%.2f", v)

    fun prepare(ctx: Context, p: Prefs) {
        val mem = memstick(ctx)
        val dirs = listOf(
            "PSP/SYSTEM", "PSP/SYSTEM/CACHE", "PSP/GAME", "PSP/SAVEDATA",
            "PSP/PPSSPP_STATE", "PSP/TEXTURES", "PSP/Cheats", "PSP/PLUGINS"
        )
        for (d in dirs) File(mem, d).mkdirs()

        // No trailing newline: the core uses the file content as a path verbatim.
        File(ctx.filesDir, "memstick_dir.txt").writeText(mem.absolutePath)

        val ini = IniFile(File(mem, "PSP/SYSTEM/ppsspp.ini"))

        ini.set("General", "FirstRun", "False")
        ini.set("General", "CheckForNewVersion", "False")

        ini.set("Graphics", "GraphicsBackend", if (p.backend == BACKEND_OPENGL) "OPENGL" else "VULKAN")
        ini.set("Graphics", "InternalResolution", p.resolution.toString())
        ini.set("Graphics", "AnisotropyLevel", p.aniso.toString())

        val skip = when (p.frameMode) {
            SKIP_1 -> 1
            SKIP_2 -> 2
            else -> 0
        }
        ini.set("Graphics", "FrameSkip", skip.toString())
        ini.set("Graphics", "AutoFrameSkip", flag(p.frameMode == SKIP_AUTO))

        // Anti-aliasing: MSAA samples = 1 shl level, so level 1 is 2x. FXAA is a post shader;
        // "Off" in the shader list means no shader.
        ini.set("Graphics", "MultiSampleLevel", if (p.aa == AA_MSAA) "1" else "0")
        ini.set("PostShaderList", "PostShader1", if (p.aa == AA_FXAA) "FXAA" else "Off")

        // Keep the PSP's 16:9 picture, or stretch it over the whole screen.
        ini.set("DisplayLayout.Landscape", "DisplayStretch", flag(p.stretch))

        // Cheats from a modpack (PSP/Cheats/<disc id>.ini) only run when the master switch is on.
        val id = p.isoDiscId
        val hasCheats = !id.isNullOrBlank() && File(mem, "PSP/Cheats/$id.ini").isFile
        ini.set("General", "EnableCheats", flag(p.textureMods && hasCheats))

        ini.set("Graphics", "ReplaceTextures", flag(p.textureMods))
        ini.set("Graphics", "SaveNewTextures", "False")

        // PPSSPP's own green status text stays off. The Ultraman Fight Evolution 0 FPS panel (FpsPanel) replaces it.
        ini.set("Graphics", "iShowStatusFlags", "0")

        // PPSSPP's own PSP-style touch controls and animated menu background stay off.
        // The Ultraman Fight Evolution 0 overlay (PadView) replaces them.
        ini.set("General", "BackgroundAnimation", "0")
        ini.set("Control", "ShowTouchControls", "False")
        ini.set("Control", "AnalogDeadzone", f2(p.deadzone / 100f))
        ini.set("Control", "AnalogSensitivity", f2(p.sensitivity / 100f))

        ini.save()
    }

    fun launch(activity: Activity, uri: Uri) {
        prepare(activity, Prefs(activity))
        val intent = Intent(Intent.ACTION_VIEW)
        // By name, so the launcher process never has to load the emulator classes.
        intent.setClassName(activity, HeroApp.EMU_CLASS)
        intent.data = uri
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(intent)
    }

    /** True while the emulator process is alive, e.g. a game left running in the background. */
    fun isRunning(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = ctx.packageName + ":emu"
        val list = am.runningAppProcesses ?: return false
        for (p in list) {
            if (p.processName == name) return true
        }
        return false
    }

    /** Brings a running game back to the foreground without restarting it. */
    fun bringToFront(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            var moved = false
            for (t in am.appTasks) {
                val comp = t.taskInfo.baseIntent.component
                if (comp != null && comp.className == HeroApp.EMU_CLASS) {
                    t.moveToFront()
                    moved = true
                    break
                }
            }
            moved
        } catch (e: Exception) {
            false
        }
    }

    /** Asks the emulator process to shut down cleanly (it kills itself afterwards). */
    fun requestQuit(ctx: Context) {
        val i = Intent(HeroApp.ACTION_QUIT_EMU)
        i.setPackage(ctx.packageName)
        ctx.sendBroadcast(i)
    }

    /** Deletes compiled shader caches (they are rebuilt on the next run). Returns bytes freed. */
    fun clearShaderCache(ctx: Context): Long {
        val dir = File(memstick(ctx), "PSP/SYSTEM/CACHE")
        var freed = 0L
        if (!dir.exists()) return 0L
        for (f in dir.walkBottomUp()) {
            if (f == dir) continue
            if (f.isFile) freed += f.length()
            f.delete()
        }
        return freed
    }
}
