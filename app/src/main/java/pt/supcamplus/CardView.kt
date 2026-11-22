package pt.supcamplus

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
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
    private val ar = AspectRatioFrameLayout(act)
    private val tv = TextureView(act)
    private val preview = LinearLayout(act)
    private val pTitle = Ui.text(act, "", 14f, Ui.TEXT, true)
    private var player: ExoPlayer? = null
    private var cur: Item? = null
    private var curUrl: String? = null

    init {
        orientation = VERTICAL
        setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12), 0)
        preview.orientation = VERTICAL
        preview.visibility = View.GONE
        preview.addView(pTitle)
        ar.addView(tv, android.widget.FrameLayout.LayoutParams(-1, -1))
        ar.setBackgroundColor(0xFF000000.toInt())
        preview.addView(ar, Ui.lp(act, -1, 220, t = 6, b = 6))
        val br = LinearLayout(act)
        br.orientation = HORIZONTAL
        br.addView(Ui.button(act, "Descarregar e editar", true) { cur?.let { f -> curUrl?.let { u -> download(f, u) } } }, Ui.lp(act, 0, -2, r = 6, weight = 1f))
        br.addView(Ui.button(act, "Fechar", false) { closePreview() }, Ui.lp(act, 0, -2, weight = 1f))
        preview.addView(br)
        addView(preview, Ui.lp(act, -1, -2, b = 8))
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

    private var inPlayback = false

    override fun onDetachedFromWindow() {
        closePreview()
        if (inPlayback) {
            inPlayback = false
            loaded = false
            Thread {
                val r = api.cgiRaw("set", "Playback", "exit")
                Trace.log("Playback exit -> ${r?.replace('\n', ' ')?.take(40)}")
            }.start()
        }
        super.onDetachedFromWindow()
    }

    private fun closePreview() {
        player?.release()
        player = null
        preview.visibility = View.GONE
    }

    private fun candidates(f: Item): List<String> {
        val short = f.name.substringAfterLast('/')
        val path = if (f.name.startsWith("/")) f.name else "/${f.name}"
        return listOf(path, "/SD$path", "/mmc$path", "/sd$path", "/DCIM/$short", "/DCIM/Movie/$short")
    }

    // Descobre qual endereço a câmara serve (pede só o 1.º byte).
    private fun probe(f: Item): String? {
        for (u in candidates(f)) {
            try {
                val c = URL("http://${api.ip}$u").openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 6000
                c.setRequestProperty("Range", "bytes=0-0")
                val code = c.responseCode
                c.disconnect()
                Trace.log("sonda $u -> $code")
                if (code in 200..299) return "http://${api.ip}$u"
            } catch (e: Exception) {
                Trace.log("sonda $u erro ${e.javaClass.simpleName}")
            }
        }
        return null
    }

    private fun open(f: Item, row: TextView) {
        val short = f.name.substringAfterLast('/')
        row.text = "$short  a abrir…"
        io.execute {
            val url = probe(f)
            ui.post {
                row.text = short + if (f.size > 0) "  ${f.size / 1048576} MB" else ""
                if (url == null) {
                    status.text = "Não consegui abrir $short (ver diagnóstico)."
                    return@post
                }
                cur = f
                curUrl = url
                pTitle.text = short
                preview.visibility = View.VISIBLE
                player?.release()
                val p = ExoPlayer.Builder(act).build()
                p.setVideoTextureView(tv)
                p.setMediaItem(MediaItem.fromUri(url))
                p.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(v: VideoSize) {
                        if (v.height > 0) ar.setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Trace.log("amostra erro ${error.errorCodeName}")
                        status.text = "Erro a reproduzir: ${error.errorCodeName}"
                    }
                })
                p.prepare()
                p.playWhenReady = true
                player = p
            }
        }
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
                // A câmara só lista o cartão em modo de reprodução (como a SupCam Pro faz).
                val enter = api.cgiRaw("set", "Playback", "enter")
                Trace.log("Playback enter -> ${enter?.replace('\n', ' ')?.take(40)}")
                inPlayback = true
                Thread.sleep(1500)
                for (prop in listOf("Normal", "Event", "Parking", "Photo", "DCIM")) {
                    val r = api.httpGet(
                        "http://${api.ip}/cgi-bin/Config.cgi?action=dir&property=$prop&format=all&from=0&count=100"
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
                        status.text = if (raw.isEmpty()) "Sem resposta da câmara." else "A câmara respondeu 0 ficheiros (cartão sem gravações?).\n" + raw.take(200)
                    } else {
                        status.text = "${items.size} ficheiros. Toca para ver a amostra."
                        for (it in items.sortedByDescending { it.name }) addRow(it)
                    }
                }
            } catch (e: Throwable) {
                say("Erro: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private fun addRow(f: Item) {
        val short = f.name.substringAfterLast('/')
        val mb = if (f.size > 0) "  ${f.size / 1048576} MB" else ""
        val t = Ui.text(act, short + mb, 15f, Ui.TEXT)
        t.background = Ui.bg(Ui.CARD, 10, act)
        t.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14))
        t.setOnClickListener { open(f, t) }
        list.addView(t, Ui.lp(act, -1, -2, b = 8))
    }

    private fun download(f: Item, url: String) {
        val short = f.name.substringAfterLast('/')
        io.execute {
            val dest = File(act.getExternalFilesDir(null) ?: act.filesDir, short)
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 8000
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
                                ui.post { status.text = "A descarregar $short$p" }
                            }
                        }
                    }
                }
                c.disconnect()
                Trace.log("download ok $url")
                ui.post {
                    closePreview()
                    status.text = "$short descarregado."
                    onOpen(Uri.fromFile(dest))
                }
            } catch (e: Exception) {
                Trace.log("download $url erro ${e.javaClass.simpleName}")
                ui.post { status.text = "Falhou o download de $short." }
            }
        }
    }
}
