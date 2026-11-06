package com.ultrafe.launcher

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Full modpacks. A modpack zip mirrors the PSP memory-stick folder:
 *
 *   TEXTURES/<disc id>/…   texture replacements (textures.ini + png files)
 *   SAVEDATA/<folder>/…    game saves
 *   Cheats/…               cheat.db and <disc id>.ini
 *   PLUGINS/…              PPSSPP plugins
 *
 * Any wrapper folder above these (for example "PSP/") is ignored, and a zip that holds only a
 * texture pack, with no such folders, is treated as textures.
 *
 * Textures go to the folder named after the imported ISO's own disc id, so a pack made for
 * the same game always lines up with the disc that is actually played.
 */
object ModPack {
    private const val ASSET_ROOT = "modpack"
    private const val MARKER = ".hero_modpack"
    private const val LOOSE = "_other"
    private val KNOWN = listOf("TEXTURES", "SAVEDATA", "Cheats", "PLUGINS")

    data class Result(val textures: Int, val saves: Int, val cheats: Int, val plugins: Int) {
        val total: Int get() = textures + saves + cheats + plugins
    }

    private fun known(name: String): String? = KNOWN.firstOrNull { it.equals(name, ignoreCase = true) }

    private fun psp(ctx: Context): File = File(EmulatorBridge.memstick(ctx), "PSP")

    private fun countFiles(dir: File): Int = dir.walkTopDown().count { it.isFile && it.name != ".nomedia" }

    private fun copyTree(from: File, to: File): Int {
        if (!from.isDirectory) return 0
        to.mkdirs()
        from.copyRecursively(to, overwrite = true)
        return countFiles(from)
    }

    /** Disc id to install textures under: the imported game's, else the pack's own folder name. */
    private fun textureId(discId: String?, texRoot: File): String? {
        if (!discId.isNullOrBlank()) return discId
        val dirs = texRoot.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
        return dirs.singleOrNull()?.name
    }

    /** Unpacks a modpack zip and installs what it holds. Call from a background thread. */
    fun importZip(ctx: Context, discId: String?, input: InputStream, includeSaves: Boolean): Result {
        val scratch = File(ctx.cacheDir, "modpack_" + System.currentTimeMillis())
        try {
            val count = ZipUtil.extract(input, scratch) { parts ->
                val at = parts.take(3).indexOfFirst { known(it) != null }
                if (at >= 0) {
                    val rest = parts.drop(at + 1)
                    if (rest.isEmpty()) null else listOf(known(parts[at])!!) + rest
                } else {
                    listOf(LOOSE) + parts
                }
            }
            if (count == 0) throw IllegalStateException("This zip has no files in it.")

            var textures = 0
            var saves = 0
            var cheats = 0
            var plugins = 0

            val texDir = File(scratch, "TEXTURES")
            val looseDir = File(scratch, LOOSE)
            val anyKnown = KNOWN.any { File(scratch, it).isDirectory }

            if (texDir.isDirectory || (!anyKnown && looseDir.isDirectory)) {
                val src = if (texDir.isDirectory) texDir else looseDir
                val id = textureId(discId, src)
                    ?: throw IllegalStateException("Import your game first so the textures know where to go.")
                val packRoot = TextureMods.findPackRoot(src)
                textures = copyTree(packRoot, TextureMods.dir(ctx, id))
            }
            if (includeSaves) {
                val sd = File(scratch, "SAVEDATA")
                if (sd.isDirectory) saves = SaveManager.installFolders(ctx, sd)
            }
            cheats = copyTree(File(scratch, "Cheats"), File(psp(ctx), "Cheats"))
            plugins = copyTree(File(scratch, "PLUGINS"), File(psp(ctx), "PLUGINS"))

            val r = Result(textures, saves, cheats, plugins)
            if (r.total == 0) throw IllegalStateException("Nothing usable was found in this zip.")
            return r
        } finally {
            scratch.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ bundled in the APK

    fun hasBundled(ctx: Context): Boolean = TextureMods.list(ctx, ASSET_ROOT).isNotEmpty()

    /**
     * Installs the modpack shipped in the APK (assets/modpack/, same layout as above), once per
     * app version. Saves are only added when that save folder doesn't exist yet, so an update
     * never overwrites progress. Returns true when something was copied.
     */
    fun installBundled(ctx: Context, discId: String?): Boolean {
        val top = TextureMods.list(ctx, ASSET_ROOT)
        if (top.isEmpty()) return false

        val marker = File(psp(ctx), MARKER)
        val version = TextureMods.appVersion(ctx)
        if (marker.isFile && marker.readText().trim() == version) return false

        for (name in top) {
            val kind = known(name) ?: continue
            val path = "$ASSET_ROOT/$name"
            when (kind) {
                "TEXTURES" -> {
                    val inner = TextureMods.list(ctx, path)
                    val pack = when {
                        inner.any { it.equals("textures.ini", ignoreCase = true) } -> path
                        else -> {
                            val dirs = inner.filter { TextureMods.isDir(ctx, "$path/$it") }
                            val pick = dirs.firstOrNull { it.equals(discId, ignoreCase = true) }
                                ?: dirs.singleOrNull()
                            if (pick != null) "$path/$pick" else null
                        }
                    }
                    if (pack != null) {
                        val id = discId?.takeIf { it.isNotBlank() } ?: pack.substringAfterLast('/')
                        val dest = TextureMods.dir(ctx, id)
                        dest.mkdirs()
                        TextureMods.copyAssets(ctx, pack, dest)
                    }
                }
                "SAVEDATA" -> {
                    val saves = SaveManager.saveDir(ctx)
                    saves.mkdirs()
                    for (folder in TextureMods.list(ctx, path)) {
                        val dest = File(saves, folder)
                        if (!dest.exists() && TextureMods.isDir(ctx, "$path/$folder")) {
                            dest.mkdirs()
                            TextureMods.copyAssets(ctx, "$path/$folder", dest)
                        }
                    }
                }
                else -> {
                    val dest = File(psp(ctx), kind)
                    dest.mkdirs()
                    TextureMods.copyAssets(ctx, path, dest)
                }
            }
        }
        marker.parentFile?.mkdirs()
        marker.writeText(version)
        return true
    }
}
