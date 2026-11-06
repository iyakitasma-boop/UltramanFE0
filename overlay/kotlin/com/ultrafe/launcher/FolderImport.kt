package com.ultrafe.launcher

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream

/**
 * Import of an extracted game: a folder that holds PSP_GAME (an unpacked disc) or EBOOT.PBP.
 *
 * The folder is picked with Android's folder picker and then COPIED into this app's own storage.
 * The emulator core reads extracted discs from a normal path far more reliably than through
 * Android's document provider, and the copy also means moving or deleting the original folder
 * can't break the game. It needs about as much free space as the folder itself.
 */
object FolderImport {

    class Node(val docId: String, val name: String, val isDir: Boolean, val size: Long)

    /** Where the game is inside the picked folder. */
    class Found(
        /** Document id of the folder to copy. */
        val rootDocId: String,
        /** Name the game is stored under (also used as the label in the launcher). */
        val name: String,
        /** Set when the picked folder IS the PSP_GAME folder: it is copied into <dest>/PSP_GAME. */
        val wrapAs: String?
    )

    fun gamesDir(ctx: Context): File = File(EmulatorBridge.memstick(ctx), "games")

    /** True when [uriString] points at a copy this app made (so it may be deleted with the game). */
    fun isOurCopy(ctx: Context, uriString: String?): Boolean {
        if (uriString == null) return false
        val u = Uri.parse(uriString)
        if (u.scheme != "file") return false
        val p = u.path ?: return false
        return try {
            File(p).canonicalPath.startsWith(gamesDir(ctx).canonicalPath + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    fun deleteOurCopy(ctx: Context, uriString: String?) {
        if (!isOurCopy(ctx, uriString)) return
        try {
            File(Uri.parse(uriString).path ?: return).deleteRecursively()
        } catch (e: Exception) {
            // Leftover files are harmless.
        }
    }

    // ------------------------------------------------------------------ reading the picked tree

    fun children(cr: ContentResolver, tree: Uri, docId: String): List<Node> {
        val out = ArrayList<Node>()
        try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            cr.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    out.add(Node(c.getString(0), c.getString(1) ?: "", dir, size))
                }
            }
        } catch (e: Exception) {
            // Unreadable folder: reported as "nothing found" by the caller.
        }
        return out
    }

    private fun find(list: List<Node>, name: String): Node? =
        list.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun nameOf(docId: String): String {
        val tail = docId.substringAfterLast('/').substringAfterLast(':')
        return tail.ifBlank { "game" }
    }

    fun safeName(s: String): String {
        val t = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.')
        return t.ifBlank { "game" }.take(60)
    }

    private fun isGameFolder(kids: List<Node>): Boolean =
        find(kids, "PSP_GAME")?.isDir == true || find(kids, "EBOOT.PBP")?.isDir == false

    /** Works out which folder inside [tree] is the game. Null when there is no PSP game here. */
    fun locate(cr: ContentResolver, tree: Uri): Found? {
        val top = DocumentsContract.getTreeDocumentId(tree)
        val kids = children(cr, tree, top)
        if (kids.isEmpty()) return null
        val name = nameOf(top)

        if (isGameFolder(kids)) return Found(top, name, null)

        // The picked folder is the PSP_GAME folder itself.
        if (name.equals("PSP_GAME", ignoreCase = true) ||
            (find(kids, "PARAM.SFO") != null && find(kids, "SYSDIR")?.isDir == true)
        ) {
            return Found(top, "game", "PSP_GAME")
        }

        // A parent folder with exactly one game inside it.
        val hits = kids.filter { it.isDir && isGameFolder(children(cr, tree, it.docId)) }
        if (hits.size == 1) return Found(hits[0].docId, hits[0].name, null)
        return null
    }

    private fun readBytes(cr: ContentResolver, tree: Uri, docId: String, max: Int): ByteArray? {
        return try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            cr.openInputStream(uri)?.use { it.readBytes().let { b -> if (b.size > max) b.copyOf(max) else b } }
        } catch (e: Exception) {
            null
        }
    }

    /** Title, disc id and cover picture from PSP_GAME/PARAM.SFO; unverified for EBOOT.PBP games. */
    fun inspect(cr: ContentResolver, tree: Uri, f: Found, totalSize: Long): IsoInfo {
        val gameDir: Node? = if (f.wrapAs != null) {
            Node(f.rootDocId, "PSP_GAME", true, 0L)
        } else {
            find(children(cr, tree, f.rootDocId), "PSP_GAME")?.takeIf { it.isDir }
        }
        if (gameDir == null) return IsoInfo(null, null, totalSize, null, false)
        val inner = children(cr, tree, gameDir.docId)
        val sfoNode = find(inner, "PARAM.SFO") ?: return IsoInfo(null, null, totalSize, null, false)
        val sfoBytes = readBytes(cr, tree, sfoNode.docId, 65536)
            ?: return IsoInfo(null, null, totalSize, null, false)
        val sfo = IsoReader.readSfo(sfoBytes)
        val title = sfo["TITLE"]?.trim() ?: return IsoInfo(null, null, totalSize, null, false)
        var icon: android.graphics.Bitmap? = null
        val iconNode = find(inner, "ICON0.PNG")
        if (iconNode != null) {
            val b = readBytes(cr, tree, iconNode.docId, 524288)
            if (b != null) icon = BitmapFactory.decodeByteArray(b, 0, b.size)
        }
        return IsoInfo(title, sfo["DISC_ID"], totalSize, icon, true)
    }

    fun totalSize(cr: ContentResolver, tree: Uri, docId: String): Long {
        var sum = 0L
        for (n in children(cr, tree, docId)) {
            sum += if (n.isDir) totalSize(cr, tree, n.docId) else n.size
        }
        return sum
    }

    // ------------------------------------------------------------------ copying

    /** Copies the game into a fresh folder, reporting the bytes copied so far. */
    fun copy(
        cr: ContentResolver, tree: Uri, f: Found, dest: File,
        onProgress: (Long) -> Unit
    ) {
        val target = if (f.wrapAs != null) File(dest, f.wrapAs) else dest
        target.mkdirs()
        var done = 0L
        val buf = ByteArray(256 * 1024)
        var lastReport = 0L

        fun walk(docId: String, into: File) {
            for (n in children(cr, tree, docId)) {
                if (n.name.isEmpty() || n.name == "." || n.name == ".." || n.name.contains('/')) continue
                val out = File(into, n.name)
                if (n.isDir) {
                    out.mkdirs()
                    walk(n.docId, out)
                } else {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, n.docId)
                    cr.openInputStream(uri)?.use { input ->
                        FileOutputStream(out).use { o ->
                            while (true) {
                                val r = input.read(buf)
                                if (r < 0) break
                                o.write(buf, 0, r)
                                done += r
                                if (done - lastReport >= 4L * 1024 * 1024) {
                                    lastReport = done
                                    onProgress(done)
                                }
                            }
                        }
                    }
                }
            }
        }
        walk(f.rootDocId, target)
        onProgress(done)
    }
}
