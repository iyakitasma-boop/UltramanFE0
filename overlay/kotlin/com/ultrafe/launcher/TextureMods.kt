package com.ultrafe.launcher

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.InputStream

/**
 * Texture replacement packs. PPSSPP loads them from PSP/TEXTURES/<DISC_ID>/ (next to a
 * textures.ini), where DISC_ID is the id of the game being played.
 *
 * Two ways in:
 *  - a pack bundled in the APK under assets "textures/" (copied once, then kept up to date), and
 *  - a zip imported from the launcher.
 *
 * Both are installed into the folder named after the imported ISO's own disc id. That avoids the
 * usual problem with packs made for another region (a pack named for the US id never loads on a
 * European disc, and vice versa).
 */
object TextureMods {
    private const val ASSET_ROOT = "textures"
    private const val MARKER = ".hero_bundled"

    fun root(ctx: Context): File = File(EmulatorBridge.memstick(ctx), "PSP/TEXTURES")

    fun dir(ctx: Context, discId: String): File = File(root(ctx), discId)

    fun fileCount(ctx: Context, discId: String?): Int {
        if (discId.isNullOrBlank()) return 0
        val d = dir(ctx, discId)
        if (!d.isDirectory) return 0
        return d.walkTopDown().count { it.isFile && it.name != MARKER }
    }

    // ------------------------------------------------------------------ zip import

    /** Returns the number of files installed. */
    fun importZip(ctx: Context, discId: String, input: InputStream): Int {
        val scratch = File(ctx.cacheDir, "tex_import_" + System.currentTimeMillis())
        try {
            val count = ZipUtil.extract(input, scratch) { it }
            if (count == 0) throw IllegalStateException("This zip has no files in it.")
            val packRoot = findPackRoot(scratch)
            val dest = dir(ctx, discId)
            dest.mkdirs()
            packRoot.copyRecursively(dest, overwrite = true)
            return count
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** The folder that holds textures.ini (searched a few levels down), else the best guess. */
    internal fun findPackRoot(base: File): File {
        var level = listOf(base)
        for (depth in 0..3) {
            for (d in level) {
                if (File(d, "textures.ini").isFile) return d
            }
            level = level.flatMap { it.listFiles { f -> f.isDirectory }?.toList() ?: emptyList() }
            if (level.isEmpty()) break
        }
        // No textures.ini anywhere: unwrap a single wrapper folder if that's all there is.
        var cur = base
        while (true) {
            val kids = cur.listFiles()?.toList() ?: break
            if (kids.size == 1 && kids[0].isDirectory) cur = kids[0] else break
        }
        return cur
    }

    // ------------------------------------------------------------------ bundled pack

    internal fun list(ctx: Context, path: String): List<String> =
        try {
            ctx.assets.list(path)?.filter { !it.startsWith(".") && !it.startsWith("README", true) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    internal fun isDir(ctx: Context, path: String): Boolean = list(ctx, path).isNotEmpty()

    /** True when a texture pack was added to the APK. */
    fun hasBundled(ctx: Context): Boolean = list(ctx, ASSET_ROOT).isNotEmpty()

    /** Which asset folder to use for this disc, or null if it can't be decided. */
    private fun pickSource(ctx: Context, discId: String): String? {
        val top = list(ctx, ASSET_ROOT)
        if (top.isEmpty()) return null
        if (top.any { it.equals("textures.ini", true) }) return ASSET_ROOT
        val exact = top.firstOrNull { it.equals(discId, true) && isDir(ctx, "$ASSET_ROOT/$it") }
        if (exact != null) return "$ASSET_ROOT/$exact"
        val dirs = top.filter { isDir(ctx, "$ASSET_ROOT/$it") }
        if (dirs.size == 1) return "$ASSET_ROOT/${dirs[0]}"
        return null
    }

    internal fun appVersion(ctx: Context): String =
        try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString()
            else @Suppress("DEPRECATION") info.versionCode.toString()
        } catch (e: PackageManager.NameNotFoundException) {
            "0"
        }

    /**
     * Copies the bundled pack into this disc's texture folder, once per app version.
     * Returns true when files were copied. Call from a background thread.
     */
    fun installBundled(ctx: Context, discId: String): Boolean {
        val src = pickSource(ctx, discId) ?: return false
        val dest = dir(ctx, discId)
        val marker = File(dest, MARKER)
        val version = appVersion(ctx)
        if (marker.isFile && marker.readText().trim() == version) return false

        dest.mkdirs()
        copyAssets(ctx, src, dest)
        marker.writeText(version)
        return true
    }

    internal fun copyAssets(ctx: Context, assetPath: String, dest: File) {
        val names = list(ctx, assetPath)
        for (name in names) {
            val child = "$assetPath/$name"
            if (isDir(ctx, child)) {
                val sub = File(dest, name)
                sub.mkdirs()
                copyAssets(ctx, child, sub)
            } else {
                File(dest, name).outputStream().use { out ->
                    ctx.assets.open(child).use { it.copyTo(out) }
                }
            }
        }
    }
}
