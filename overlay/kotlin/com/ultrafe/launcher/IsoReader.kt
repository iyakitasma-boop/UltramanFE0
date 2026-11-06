package com.ultrafe.launcher

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

data class IsoInfo(
    val title: String?,
    val discId: String?,
    val sizeBytes: Long,
    val icon: Bitmap?,
    /** True when PARAM.SFO was read, so [title] can be trusted. */
    val verified: Boolean
) {
    /** Looks like the Ultraman game (or a mod of it) going by the disc title. */
    val looksRight: Boolean
        get() {
            val t = title ?: return false
            return listOf("ultraman", "ultra", "fighting evolution", "fight evolution", "banpresto", "ウルトラマン")
                .any { t.contains(it, ignoreCase = true) }
        }
}

/**
 * Reads just enough of an ISO 9660 disc image to find PSP_GAME/PARAM.SFO (title and disc id)
 * and ICON0.PNG. Only a few KB are read; the game itself is never copied or modified.
 * Compressed images (CSO/CHD/PBP) can't be read this way and come back unverified.
 */
object IsoReader {
    private const val SECTOR = 2048L

    private class Entry(val lba: Long, val size: Long)

    fun inspect(cr: ContentResolver, uri: Uri): IsoInfo {
        val size = querySize(cr, uri)
        val unverified = IsoInfo(null, null, size, null, false)
        return try {
            val pfd = cr.openFileDescriptor(uri, "r")
            if (pfd == null) {
                unverified
            } else {
                val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                try {
                    readDisc(stream.channel, size) ?: unverified
                } finally {
                    stream.close()
                }
            }
        } catch (e: Exception) {
            unverified
        }
    }

    /** Returns null when the file isn't a plain ISO 9660 PSP disc image. */
    private fun readDisc(ch: FileChannel, size: Long): IsoInfo? {
        val pvd = readAt(ch, 16 * SECTOR, 2048) ?: return null
        if (pvd.size < 190 || pvd[0].toInt() != 1) return null
        if (String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return null

        val rootLba = le32(pvd, 156 + 2)
        val rootSize = le32(pvd, 156 + 10)

        val game = findEntry(ch, rootLba, rootSize, "PSP_GAME") ?: return null
        val sfoEntry = findEntry(ch, game.lba, game.size, "PARAM.SFO") ?: return null
        val sfoBytes = readAt(ch, sfoEntry.lba * SECTOR, sfoEntry.size.coerceAtMost(65536L).toInt())
            ?: return null
        val sfo = parseSfo(sfoBytes)
        val title = sfo["TITLE"] ?: return null

        var icon: Bitmap? = null
        val iconEntry = findEntry(ch, game.lba, game.size, "ICON0.PNG")
        if (iconEntry != null) {
            val data = readAt(ch, iconEntry.lba * SECTOR, iconEntry.size.coerceAtMost(524288L).toInt())
            if (data != null) icon = BitmapFactory.decodeByteArray(data, 0, data.size)
        }
        return IsoInfo(title.trim(), sfo["DISC_ID"], size, icon, true)
    }

    private fun querySize(cr: ContentResolver, uri: Uri): Long {
        return try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray? {
        if (len <= 0) return null
        val buf = ByteBuffer.allocate(len)
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos + buf.position())
            if (n < 0) break
        }
        if (buf.position() == 0) return null
        return buf.array().copyOf(buf.position())
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)

    /** Walks one ISO 9660 directory and returns the entry called [wanted] (";1" suffix ignored). */
    private fun findEntry(ch: FileChannel, lba: Long, size: Long, wanted: String): Entry? {
        val data = readAt(ch, lba * SECTOR, size.coerceAtMost(262144L).toInt()) ?: return null
        var off = 0
        while (off < data.size) {
            val len = data[off].toInt() and 0xFF
            if (len == 0) {
                off = (off / 2048 + 1) * 2048
                continue
            }
            if (off + len > data.size || off + 33 > data.size) break
            val nameLen = data[off + 32].toInt() and 0xFF
            if (off + 33 + nameLen <= data.size) {
                var name = String(data, off + 33, nameLen, Charsets.US_ASCII)
                val semi = name.indexOf(';')
                if (semi >= 0) name = name.substring(0, semi)
                if (name.equals(wanted, ignoreCase = true)) {
                    return Entry(le32(data, off + 2), le32(data, off + 10))
                }
            }
            off += len
        }
        return null
    }

    /** Reads a PARAM.SFO file already loaded into memory (used for extracted game folders). */
    fun readSfo(d: ByteArray): Map<String, String> = parseSfo(d)

    private fun cString(d: ByteArray, start: Int, maxLen: Int): String {
        if (start < 0 || start >= d.size) return ""
        var end = start
        val limit = minOf(d.size, start + maxLen)
        while (end < limit && d[end].toInt() != 0) end++
        return String(d, start, end - start, Charsets.UTF_8)
    }

    /** Parses a PSF/SFO file into key -> string value (numeric entries are skipped). */
    private fun parseSfo(d: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        if (d.size < 20 || String(d, 1, 3, Charsets.US_ASCII) != "PSF") return out
        val keyTable = le32(d, 8).toInt()
        val dataTable = le32(d, 12).toInt()
        val count = minOf(le32(d, 16).toInt(), 256)
        for (i in 0 until count) {
            val base = 20 + i * 16
            if (base + 16 > d.size) break
            val keyOff = le16(d, base)
            val fmt = le16(d, base + 2)
            val len = le32(d, base + 4).toInt()
            val dataOff = le32(d, base + 12).toInt()
            if (fmt != 0x0204 && fmt != 0x0004) continue
            val key = cString(d, keyTable + keyOff, 64)
            if (key.isEmpty()) continue
            out[key] = cString(d, dataTable + dataOff, len)
        }
        return out
    }
}
