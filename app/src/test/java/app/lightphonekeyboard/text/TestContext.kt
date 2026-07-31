package app.lightphonekeyboard.text

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Word-pair fixtures for the context tests.
 *
 * [of] writes the real bigrams.bin format from a list of pairs and PMI values, so every test goes
 * through the shipped loader rather than around it — a format mistake fails here instead of on the
 * phone, and the Kotlin fingerprint is checked against itself on the way in and out.
 *
 * [bundled] reads the actual generated asset. Unit tests run with the module directory as their working
 * directory, so the resource is reachable as a plain file without dragging in Robolectric. Tests using
 * it skip themselves if it's absent, so a checkout without the generated asset still builds.
 */
object TestContext {

    /** A table holding exactly these `left to right to pmi` entries, in the shipped binary format. */
    fun of(vararg entries: Triple<String, String, Float>): ContextModel {
        // Big enough that the load factor stays low; the reader only requires a power of two.
        val capacity = 1024
        val keys = IntArray(capacity)
        val scores = ByteArray(capacity)
        val mask = capacity - 1
        for ((left, right, pmi) in entries) {
            val h = ContextModel.fingerprint(left, right)
            var i = h and mask
            while (keys[i] != 0 && keys[i] != h) i = (i + 1) and mask
            keys[i] = h
            val q = Math.round(minOf(pmi, ContextModel.PMI_FULL) / ContextModel.PMI_FULL * 255f)
            scores[i] = q.coerceIn(1, 255).toByte()
        }
        val out = ByteArrayOutputStream()
        writeIntLE(out, 0x314C4B42)
        writeIntLE(out, capacity)
        writeIntLE(out, entries.size)
        for (k in keys) writeIntLE(out, k)
        out.write(scores)
        return ContextModel.load(ByteArrayInputStream(out.toByteArray()))
    }

    /** The shipped 180k-pair table, or null if it hasn't been generated in this checkout. */
    fun bundled(): ContextModel? {
        for (path in listOf("src/main/res/raw/bigrams.bin", "app/src/main/res/raw/bigrams.bin")) {
            val f = File(path)
            if (f.isFile) return f.inputStream().use { ContextModel.load(it) }
        }
        return null
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}
