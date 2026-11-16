package pt.supcamplus

import java.io.File

// Registo carregado de um CSV gerado pelo TrackService.
class TrackData(f: File) {
    val t = ArrayList<Long>()
    val speed = ArrayList<Float>()
    val pitch = ArrayList<Float>()
    val roll = ArrayList<Float>()
    val recStarts = ArrayList<Long>()

    init {
        f.forEachLine { line ->
            val c = line.split(",")
            if (c.size >= 7 && c[0].toLongOrNull() != null) {
                val ms = c[0].toLong()
                if (c[6].isNotEmpty()) {
                    if (c[6] == "REC_START") recStarts.add(ms)
                } else {
                    t.add(ms)
                    speed.add(c[3].toFloatOrNull() ?: 0f)
                    pitch.add(c[4].toFloatOrNull() ?: 0f)
                    roll.add(c[5].toFloatOrNull() ?: 0f)
                }
            }
        }
    }

    fun isEmpty() = t.isEmpty()

    // Índice da amostra mais próxima de ms (tempo absoluto).
    fun idx(ms: Long): Int {
        if (t.isEmpty()) return -1
        var lo = 0
        var hi = t.size - 1
        while (lo < hi) {
            val m = (lo + hi) / 2
            if (t[m] < ms) lo = m + 1 else hi = m
        }
        return if (lo > 0 && ms - t[lo - 1] < t[lo] - ms) lo - 1 else lo
    }
}
