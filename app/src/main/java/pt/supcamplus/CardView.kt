package pt.supcamplus

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// Lista os ficheiros do cartão da câmara (Config.cgi action=dir), descarrega e abre no Editor.
class CardView(
    private val act: Activity,
    private val api: CameraApi,
    private val onOpen: (Uri) -> Unit
) : LinearLayout(act) {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val list = LinearLayout(act)
    private val status = Ui.text(act, "", 13f, Ui.MUTED)
    private var loaded = false

    init {
        orientation = VERTICAL
        setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12), 0)
        addView(Ui.button(act, "Atualizar lista", true) { refresh() })
        addView(status, Ui.lp(act, -1, -2, t = 8, b = 8))
        list.orientation = VERTICAL
        val sv = ScrollView(act)
        sv.addView(list)
        addView(sv, Ui.lp(act, -1, 0, weight = 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!loaded) refresh()
    }

    private fun say(s: String) = ui.post { status.text = s }

    private data class Item(val name: String, val size: Long)

    private fun refresh() {
        say("A ler o cartão…")
        io.execute {
            try {
                val items = ArrayList<Item>()
                val seen = HashSet<String>()
                var raw = ""
                for (prop in listOf("DCIM", "Normal", "Movie", "Photo", "Event")) {
                    val r = api.httpGet(
                        "http://${api.ip}/cgi-bin/Config.cgi?action=dir&property=$prop&format=all&count=100&from=0"
                    ) ?: continue
                    raw = r
                    for (m in Regex("<file>(.*?)</file>", RegexOption.DOT_MATCHES_ALL).findAll(r)) {
                        val b = m.groupValues[1]
                        val n = Regex("<name>\\s*(.*?)\\s*</name>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1) ?: continue
                        val sz = Regex("<size>\\s*(\\d+)\\s*</size>").find(b)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        if (seen.add(n)) items.add(Item(n, sz))
                    }
                }
                Trace.log("cartao ficheiros=${items.size} raw=${raw.replace('\n', ' ').take(120)}")
                loaded = true
                ui.post {
                    list.removeAllViews()
                    if (items.isEmpty()) {
                        status.text = "Sem ficheiros na lista. Resposta da câmara:\n" + raw.take(300)
                    } else {
                        status.text = "${items.size} ficheiros. Toca para descarregar e editar."
                        for (it in items.sortedByDescending { it.name }) addRow(it)
                    }
                }
            } catch (e: Throwable) {
                say("Erro: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private fun addRow(it: Item) {
        val short = it.name.substringAfterLast('/')
        val mb = if (it.size > 0) "  ${it.size / 1048576} MB" else ""
        val t = Ui.text(act, short + mb, 15f, Ui.TEXT)
        t.background = Ui.bg(Ui.CARD, 10, act)
        t.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14))
        t.setOnClickListener { download(it, t, short) }
        list.addView(t, Ui.lp(act, -1, -2, b = 8))
    }

    private fun download(it: Item, row: TextView, short: String) {
        io.execute {
            val dest = File(act.getExternalFilesDir(null) ?: act.filesDir, short)
            val n = it.name
            val path = if (n.startsWith("/")) n else "/$n"
            val urls = listOf(path, "/SD$path", "/mmc$path", "/sd$path", "/DCIM/$short", "/DCIM/Movie/$short")
            for (u in urls) {
                try {
                    val c = URL("http://${api.ip}$u").openConnection() as HttpURLConnection
                    c.connectTimeout = 4000
                    c.readTimeout = 8000
                    if (c.responseCode !in 200..299) {
                        Trace.log("download $u -> ${c.responseCode}")
                        c.disconnect()
                        continue
                    }
                    val total = c.contentLengthLong
                    var done = 0L
                    var last = 0L
                    c.inputStream.use { inp ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val r = inp.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                done += r
                                val now = System.currentTimeMillis()
                                if (now - last > 500) {
                                    last = now
                                    val p = if (total > 0) " ${done * 100 / total}%" else " ${done / 1048576} MB"
                                    ui.post { row.text = "$short  a descarregar$p" }
                                }
                            }
                        }
                    }
                    c.disconnect()
                    Trace.log("download ok $u")
                    ui.post {
                        row.text = "$short  descarregado"
                        onOpen(Uri.fromFile(dest))
                    }
                    return@execute
                } catch (e: Exception) {
                    Trace.log("download $u erro ${e.javaClass.simpleName}")
                }
            }
            ui.post { row.text = "$short  falhou (ver diagnóstico)" }
        }
    }
}
