package app.lightphonekeyboard.text

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ln

/**
 * Dictionary fixtures for the text tests.
 *
 * [of] writes the real words.bin format from a word list, so the loader is exercised by every test
 * rather than being stubbed around — a format mistake fails here instead of on the phone.
 *
 * [bundled] reads the actual shipped asset. Unit tests run with the module directory as their working
 * directory, so the resource is reachable as a plain file without dragging in Robolectric. Tests using
 * it skip themselves if it's absent, so a checkout without the generated asset still builds.
 */
object TestDictionary {

    /** Build a dictionary from `word to countWeight` pairs. Weights are relative, not absolute. */
    fun of(vararg entries: Pair<String, Int>): Dictionary {
        val total = entries.sumOf { it.second }.toDouble()
        // The format requires words sorted by length, then alphabetically.
        val sorted = entries.sortedWith(compareBy({ it.first.length }, { it.first }))
        val out = ByteArrayOutputStream()
        writeIntLE(out, 0x314C4B44)
        writeIntLE(out, sorted.size)
        for ((word, count) in sorted) {
            out.write(word.length)
            writeShortLE(out, Math.round(ln(count / total) * 1000).toInt())
            for (c in word) out.write(c.code)
        }
        return Dictionary.load(ByteArrayInputStream(out.toByteArray()))
    }

    /** The shipped 80k-word list, or null if it hasn't been generated in this checkout. */
    fun bundled(): Dictionary? {
        for (path in listOf("src/main/res/raw/words.bin", "app/src/main/res/raw/words.bin")) {
            val f = File(path)
            if (f.isFile) return f.inputStream().use { Dictionary.load(it) }
        }
        return null
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }
}
