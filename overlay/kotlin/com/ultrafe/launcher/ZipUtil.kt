package com.ultrafe.launcher

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Small zip helpers. Extraction refuses entries that would escape the target folder. */
object ZipUtil {

    /** Writes every file under [dirs] into a zip as "[root]/<folder>/<file>". Returns file count. */
    fun zipDirs(dirs: List<File>, root: String, out: OutputStream): Int {
        var count = 0
        ZipOutputStream(out).use { zip ->
            for (dir in dirs) {
                val base = dir.parentFile ?: continue
                for (f in dir.walkTopDown()) {
                    if (!f.isFile) continue
                    val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry("$root/$rel"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
            }
        }
        return count
    }

    /**
     * Extracts a zip into [dest]. [mapPath] receives the entry path split on "/" and returns the
     * path segments to use, or null to skip the entry. Returns the number of files written.
     */
    fun extract(input: InputStream, dest: File, mapPath: (List<String>) -> List<String>?): Int {
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                val mapped = mapPath(parts)
                if (mapped == null || mapped.isEmpty() || mapped.any { it == ".." || it == "." }) {
                    zip.closeEntry()
                    continue
                }
                val target = File(destRoot, mapped.joinToString(File.separator)).canonicalFile
                if (!target.path.startsWith(destRoot.path + File.separator)) {
                    zip.closeEntry()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }
}
