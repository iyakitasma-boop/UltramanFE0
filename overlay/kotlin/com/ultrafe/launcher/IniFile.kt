package com.ultrafe.launcher

import java.io.File

/**
 * Minimal INI editor for ppsspp.ini. It only touches the keys we set and keeps every other
 * line (including settings the user changed inside the emulator) exactly as it was.
 */
class IniFile(private val file: File) {
    private val lines = ArrayList<String>()

    init {
        if (file.exists()) {
            lines.addAll(file.readLines())
        }
    }

    fun set(section: String, key: String, value: String) {
        val header = "[$section]"
        var start = -1
        var end = lines.size
        for (i in lines.indices) {
            val t = lines[i].trim()
            if (start == -1) {
                if (t.equals(header, ignoreCase = true)) start = i
            } else if (t.startsWith("[")) {
                end = i
                break
            }
        }

        if (start == -1) {
            if (lines.isNotEmpty() && lines[lines.size - 1].isNotBlank()) lines.add("")
            lines.add(header)
            lines.add("$key = $value")
            return
        }

        for (i in start + 1 until end) {
            val line = lines[i]
            val eq = line.indexOf('=')
            if (eq > 0 && line.substring(0, eq).trim().equals(key, ignoreCase = true)) {
                lines[i] = "$key = $value"
                return
            }
        }

        var insertAt = end
        while (insertAt > start + 1 && lines[insertAt - 1].isBlank()) insertAt--
        lines.add(insertAt, "$key = $value")
    }

    fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(lines.joinToString("\n") + "\n")
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
