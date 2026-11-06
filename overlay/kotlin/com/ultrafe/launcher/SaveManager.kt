package com.ultrafe.launcher

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SaveInfo(val folders: Int, val bytes: Long, val modified: Long)

/**
 * Game save import and export. Saves live in the app's memory stick folder (PSP/SAVEDATA),
 * which is the same place earlier builds of this app used, so updating the app over an
 * older install keeps its saves.
 */
object SaveManager {
    private const val KEEP_BACKUPS = 5

    fun saveDir(ctx: Context): File = File(EmulatorBridge.memstick(ctx), "PSP/SAVEDATA")

    /**
     * The save folders that belong to the game. They are named after the disc ID (first nine characters);
     * when none match (unusual naming) every folder is treated as ours.
     */
    fun folders(ctx: Context, discId: String?): List<File> {
        val all = saveDir(ctx).listFiles { f -> f.isDirectory }?.toList() ?: return emptyList()
        val prefix = if (discId != null && discId.length >= 9) discId.substring(0, 9) else null
        val matching = if (prefix == null) all else all.filter { it.name.startsWith(prefix, ignoreCase = true) }
        return (if (matching.isEmpty()) all else matching).sortedBy { it.name }
    }

    fun info(ctx: Context, discId: String?): SaveInfo {
        var bytes = 0L
        var modified = 0L
        val list = folders(ctx, discId)
        for (dir in list) {
            for (f in dir.walkTopDown()) {
                if (!f.isFile) continue
                bytes += f.length()
                if (f.lastModified() > modified) modified = f.lastModified()
            }
        }
        return SaveInfo(list.size, bytes, modified)
    }

    /** Returns the number of files written to [out]. */
    fun export(ctx: Context, discId: String?, out: OutputStream): Int {
        val list = folders(ctx, discId)
        if (list.isEmpty()) throw IllegalStateException("There is no save data to export yet.")
        return ZipUtil.zipDirs(list, "SAVEDATA", out)
    }

    /** Folder names a full modpack zip carries next to SAVEDATA; they are not save folders. */
    private val NOT_SAVES = listOf("TEXTURES", "Cheats", "PLUGINS", "PPSSPP_STATE", "SYSTEM", "GAME")

    /**
     * Imports a save zip. It is unpacked to a scratch folder first, so a bad file can't damage
     * what is already there, and any save it replaces is copied to a backup folder.
     * Accepts zips laid out as "SAVEDATA/<folder>/…", "PSP/SAVEDATA/<folder>/…" or "<folder>/…".
     * A whole modpack zip works too: only its SAVEDATA part is used.
     */
    fun import(ctx: Context, input: InputStream): Int {
        val scratch = File(ctx.cacheDir, "save_import_" + System.currentTimeMillis())
        try {
            val count = ZipUtil.extract(input, scratch) { parts ->
                val at = parts.indexOfFirst { it.equals("SAVEDATA", ignoreCase = true) }
                val other = parts.take(3).any { n -> NOT_SAVES.any { it.equals(n, ignoreCase = true) } }
                val rel = if (at >= 0) parts.drop(at + 1) else if (other) emptyList() else parts
                if (rel.size >= 2) rel else null   // needs <folder>/<file>
            }
            if (count == 0) throw IllegalStateException("No save data found in this file.")
            installFolders(ctx, scratch)
            return count
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * Moves every save folder inside [incomingRoot] into PSP/SAVEDATA, backing up any save it
     * replaces. Returns the number of files installed.
     */
    fun installFolders(ctx: Context, incomingRoot: File): Int {
        val incoming = incomingRoot.listFiles { f -> f.isDirectory }?.toList() ?: return 0
        val target = saveDir(ctx)
        target.mkdirs()

        val backupRoot = backupRoot(ctx)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var files = 0
        for (dir in incoming) {
            val existing = File(target, dir.name)
            if (existing.exists()) {
                existing.copyRecursively(File(backupRoot, "save_$stamp/${dir.name}"), overwrite = true)
                existing.deleteRecursively()
            }
            dir.copyRecursively(existing, overwrite = true)
            files += dir.walkTopDown().count { it.isFile }
        }
        pruneBackups(backupRoot)
        return files
    }

    private fun backupRoot(ctx: Context): File = File(EmulatorBridge.memstick(ctx), "UFE_backups")

    private fun pruneBackups(root: File) {
        val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return
        if (dirs.size > KEEP_BACKUPS) {
            for (d in dirs.take(dirs.size - KEEP_BACKUPS)) d.deleteRecursively()
        }
    }
}
