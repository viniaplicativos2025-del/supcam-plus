package pt.supcamplus

import android.content.ContentValues
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import java.util.concurrent.Executors

// Ecrã em direto: vídeo em cima e, por baixo, modos, obturador e definições.
// As definições aplicam-se logo e o vídeo nunca fica tapado.
class LiveView(private val act: ComponentActivity, private val api: CameraApi) : FrameLayout(act) {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val ar = AspectRatioFrameLayout(act)
    private val tv = TextureView(act)
    private val videoBox = FrameLayout(act)
    private val statusChip = Ui.text(act, "", 12f, Color.WHITE)

    private val body = LinearLayout(act)
    private val panel = LinearLayout(act)
    private val tabs = LinearLayout(act)
    private val ctrlView = LinearLayout(act)
    private val setScroll = ScrollView(act)
    private val setBox = LinearLayout(act)
    private val modeHolder = LinearLayout(act)
    private val shutter = TextView(act)
    private lateinit var tabA: TextView
    private lateinit var tabB: TextView

    private val modeNames = listOf("Vídeo", "C. lenta", "Loop", "Time-lapse", "Foto", "Foto auto", "Contínuo", "Temporizada")

    private var player: ExoPlayer? = null
    private var rot = 0f
    private var mirror = false
    private var recording = false
    private var alive = false
    private var mode = 2
    private var vals: Map<String, String> = emptyMap()

    private fun dp(v: Int) = Ui.dp(act, v)

    private val beat = object : Runnable {
        override fun run() {
            if (!alive) return
            io.execute { api.cgi("set", "StreamStatus", "ON") }
            ui.postDelayed(this, 5000)
        }
    }

    init {
        setBackgroundColor(Ui.BG)

        // vídeo
        videoBox.setBackgroundColor(Color.BLACK)
        ar.addView(tv, FrameLayout.LayoutParams(-1, -1))
        videoBox.addView(ar, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        statusChip.setPadding(dp(10), dp(4), dp(10), dp(4))
        statusChip.background = Ui.bg(0x99000000.toInt(), 12, act)
        val sp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
        sp.setMargins(dp(8), dp(8), 0, 0)
        videoBox.addView(statusChip, sp)
        val quick = LinearLayout(act)
        quick.addView(Ui.chip(act, "Rodar 180°", false) { rot = if (rot == 0f) 180f else 0f; applyFlip() })
        quick.addView(Ui.chip(act, "Espelhar", false) { mirror = !mirror; applyFlip() }, Ui.lp(act, -2, -2, l = 6))
        val qp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        qp.setMargins(0, dp(8), dp(8), 0)
        videoBox.addView(quick, qp)

        // painel
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(14), dp(12), dp(14), dp(12))
        tabs.orientation = LinearLayout.HORIZONTAL
        tabA = Ui.chip(act, "Controlo", true) { showTab(false) }
        tabB = Ui.chip(act, "Definições", false) { showTab(true) }
        tabs.addView(tabA)
        tabs.addView(tabB, Ui.lp(act, -2, -2, l = 8))
        panel.addView(tabs)

        ctrlView.orientation = LinearLayout.VERTICAL
        ctrlView.addView(Ui.text(act, "Modo", 12f, Ui.MUTED), Ui.lp(act, -2, -2, t = 14))
        ctrlView.addView(modeHolder, Ui.lp(act, -1, -2, t = 6))
        shutter.gravity = Gravity.CENTER
        shutter.textSize = 28f
        shutter.setTextColor(Color.WHITE)
        shutter.setOnClickListener { onShutter() }
        val shutterRow = LinearLayout(act)
        shutterRow.gravity = Gravity.CENTER
        shutterRow.addView(shutter, Ui.lp(act, 84, 84))
        ctrlView.addView(shutterRow, Ui.lp(act, -1, -2, t = 18))
        val tools = LinearLayout(act)
        tools.gravity = Gravity.CENTER
        tools.addView(Ui.chip(act, "Ligar de novo", false) { connect() })
        tools.addView(Ui.chip(act, "Diagnóstico", false) { saveDiag() }, Ui.lp(act, -2, -2, l = 8))
        ctrlView.addView(tools, Ui.lp(act, -1, -2, t = 18))
        panel.addView(ctrlView, Ui.lp(act, -1, -2, t = 4))

        setBox.orientation = LinearLayout.VERTICAL
        setBox.setPadding(0, 0, 0, dp(24))
        setScroll.addView(setBox)
        setScroll.visibility = View.GONE
        panel.addView(setScroll, Ui.lp(act, -1, 0, t = 4, weight = 1f))

        body.orientation = LinearLayout.VERTICAL
        body.addView(videoBox, LinearLayout.LayoutParams(-1, dp(220)))
        body.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(body, FrameLayout.LayoutParams(-1, -1))

        buildModeRow()
        updateShutter()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w == 0 || h == 0) return
        if (w > h) {
            body.orientation = LinearLayout.HORIZONTAL
            val vw = minOf(h * 16 / 9, (w * 0.62f).toInt())
            videoBox.layoutParams = LinearLayout.LayoutParams(vw, -1)
            panel.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        } else {
            body.orientation = LinearLayout.VERTICAL
            videoBox.layoutParams = LinearLayout.LayoutParams(-1, w * 9 / 16)
            panel.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
    }

    private fun showTab(settings: Boolean) {
        ctrlView.visibility = if (settings) View.GONE else View.VISIBLE
        setScroll.visibility = if (settings) View.VISIBLE else View.GONE
        Ui.styleChip(tabA, !settings, act)
        Ui.styleChip(tabB, settings, act)
    }

    private fun buildModeRow() {
        modeHolder.removeAllViews()
        modeHolder.addView(Ui.group(act, modeNames, mode) { selectMode(it) })
    }

    private fun updateShutter() {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(if (recording) 0xFF444B59.toInt() else 0xFFE5322D.toInt())
        g.setStroke(dp(4), Color.WHITE)
        shutter.background = g
        shutter.text = if (recording) "■" else ""
    }

    private fun onShutter() {
        if (mode >= 4) {
            io.execute {
                val r = api.cgi("set", "Video", "capture")
                setStatus(if (r != null) "Foto tirada" else "Falhou")
            }
        } else {
            recording = !recording
            updateShutter()
            val v = if (recording) "recordon" else "recordoff"
            io.execute {
                val r = api.cgi("set", "Video", v)
                setStatus(if (r != null) (if (v == "recordon") "A gravar…" else "Gravação parada") else "Falhou")
            }
        }
    }

    private fun selectMode(n: Int) {
        mode = n
        recording = false
        updateShutter()
        setStatus("A mudar para ${modeNames[n]}…")
        io.execute {
            api.cgi("set", "sysworkmode", "MODE$n")
            Thread.sleep(800)
            loadSettings()
        }
    }

    private fun applyFlip() {
        tv.rotation = rot
        tv.scaleX = if (mirror) -1f else 1f
    }

    private fun setStatus(s: String) {
        ui.post { statusChip.text = s }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alive = true
        connect()
    }

    override fun onDetachedFromWindow() {
        alive = false
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDetachedFromWindow()
    }

    private fun connect() {
        setStatus("A ligar à câmara ${api.ip}…")
        io.execute {
            val preview = api.cgi("get", "Camera.Preview.*")
            if (preview == null) {
                setStatus("Sem resposta da câmara. Liga o Wi-Fi da câmara e toca em Ligar de novo.")
                return@execute
            }
            api.cgi("set", "StreamStatus", "ON")
            val url = api.liveUrl(preview["Camera.Preview.RTSP.av"])
            ui.post {
                startPlayer(url)
                ui.removeCallbacks(beat)
                ui.postDelayed(beat, 5000)
            }
            loadSettings()
        }
    }

    // Lê valores e menus do modo atual (corre fora do fio principal).
    private fun loadSettings() {
        val v = api.cgi("get", "Camera.Menu.*") ?: emptyMap()
        val n = v["Camera.Menu.sysworkmode"]?.removePrefix("MODE")?.toIntOrNull()
        val md = if (n != null && n in 0..7) n else mode
        val general = api.loadMenus(CameraApi.GENERAL_FILE)
        val modeMenus = api.loadMenus(CameraApi.modeFiles[md])
        ui.post {
            if (alive) {
                vals = v
                mode = md
                buildModeRow()
                updateShutter()
                buildSettings(general, modeMenus)
            }
        }
    }

    private fun startPlayer(url: String) {
        player?.release()
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(250, 1000, 100, 250)
            .build()
        val p = ExoPlayer.Builder(act).setLoadControl(lc).build()
        val src = RtspMediaSource.Factory().setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(url))
        p.setVideoTextureView(tv)
        p.setMediaSource(src)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.height > 0) ar.setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) setStatus("Em direto")
            }

            override fun onPlayerError(error: PlaybackException) {
                setStatus("Stream: ${error.errorCodeName}. A tentar de novo…")
                ui.postDelayed({ if (alive) player?.prepare() }, 3000)
            }
        })
        p.prepare()
        p.playWhenReady = true
        player = p
        applyFlip()
    }

    private fun buildSettings(general: List<CameraApi.Menu>, modeMenus: List<CameraApi.Menu>) {
        setBox.removeAllViews()
        val a = addSection("Modo atual: ${modeNames[mode]}", modeMenus)
        val b = addSection("Geral", general)
        if (!a && !b) {
            setBox.addView(
                Ui.text(act, "Não consegui ler os menus da câmara. Usa Diagnóstico e envia o ficheiro.", 14f, Ui.MUTED),
                Ui.lp(act, -1, -2, t = 16)
            )
        }
    }

    private fun addSection(title: String, menus: List<CameraApi.Menu>): Boolean {
        val shown = menus.filter { vals.containsKey(it.id) && it.opts.isNotEmpty() }
        if (shown.isEmpty()) return false
        setBox.addView(Ui.text(act, title, 15f, Ui.ACCENT, true), Ui.lp(act, -1, -2, t = 16, b = 2))
        for (m in shown) addCard(m)
        return true
    }

    private fun addCard(m: CameraApi.Menu) {
        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.bg(Ui.CARD, 14, act)
        card.setPadding(dp(12), dp(10), dp(12), dp(12))
        card.addView(Ui.text(act, m.title, 13f, Ui.MUTED))
        val cur = m.opts.indexOfFirst { it.value == vals[m.id] }
        val g = Ui.group(act, m.opts.map { it.label }, cur) { i ->
            val o = m.opts[i]
            setStatus("A aplicar: ${m.title}…")
            io.execute {
                val r = api.cgi("set", m.id, o.value)
                setStatus(if (r != null) "${m.title}: ${o.label}" else "Falhou: ${m.title}")
            }
        }
        card.addView(g, Ui.lp(act, -1, -2, t = 8))
        setBox.addView(card, Ui.lp(act, -1, -2, t = 8))
    }

    private fun saveDiag() {
        setStatus("A recolher diagnóstico…")
        io.execute {
            val txt = api.diagnostics()
            try {
                val cv = ContentValues()
                val name = "supcamplus_diag_${System.currentTimeMillis()}.txt"
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name)
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                val u = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                act.contentResolver.openOutputStream(u)!!.use { it.write(txt.toByteArray()) }
                setStatus("Guardado em Transferências/$name")
            } catch (e: Exception) {
                setStatus("Falhou ao guardar: ${e.message}")
            }
        }
    }
}
