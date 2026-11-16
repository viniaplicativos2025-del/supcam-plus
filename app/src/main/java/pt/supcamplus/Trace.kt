package pt.supcamplus

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Registo curto em ficheiro dos últimos passos da app, para perceber onde pára quando fecha.
object Trace {
    private var f: File? = null

    fun init(c: Context) {
        f = File(c.filesDir, "trace.txt")
    }

    @Synchronized
    fun log(m: String) {
        try {
            val file = f ?: return
            if (file.length() > 60000) {
                file.writeText(file.readLines().takeLast(150).joinToString("\n") + "\n")
            }
            file.appendText(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + m + "\n")
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun tail(n: Int): String = try {
        f?.takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n") ?: "(vazio)"
    } catch (_: Throwable) {
        "(erro a ler)"
    }
}
