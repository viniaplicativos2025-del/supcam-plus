package pt.supcamplus

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.os.Build
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
import androidx.media3.common.C
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
class LiveView(
    private val act: ComponentActivity,
    private val api: CameraApi,
    private val askPerms: (() -> Unit) -> Unit
) : FrameLayout(act) {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val ar = AspectRatioFrameLayout(act)
    private val tv = TextureView(act)
    private val videoBox = FrameLayout(act)
    private val statusChip = Ui.text(act, "", 12f, Color.WHITE)
    private val latChip = Ui.text(act, "", 11f, Color.WHITE)
    private val trkInfo = Ui.text(act, "", 12f, Ui.MUTED)
    private lateinit var trkBtn: TextView

    private val body = LinearLayout(act)
    private val panel = LinearLayout(act)
    private val tabs = LinearLayout(act)
    private val ctrlView = LinearLayout(act)
    private val ctrlScroll = ScrollView(act)
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
    private var useTcp = false
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

    // Mostra o atraso do direto (quando o leitor o consegue medir).
    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            val p = player
            if (p != null && p.playbackState == Player.STATE_READY) {
                val off = p.currentLiveOffset
                latChip.text = if (off != C.TIME_UNSET) "atraso %.1f s".format(off / 1000.0) else "atraso n/d"
            } else {
                latChip.text = ""
            }
            ui.postDelayed(this, 1000)
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
        latChip.setPadding(dp(8), dp(2), dp(8), dp(2))
        latChip.background = Ui.bg(0x99000000.toInt(), 10, act)
        val lpc = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
        lpc.setMargins(dp(8), 0, 0, dp(8))
        videoBox.addView(latChip, lpc)
        val quick = LinearLayout(act)
        quick.addView(Ui.chip(act, "Rodar 180°", false) { rot = if (rot == 0f) 180f else 0f; applyFlip() })
        quick.addView(Ui.chip(act, "Espelhar", false) { mirror = !mirror; applyFlip() }, Ui.lp(act, -2, -2, l = 6))
        val qp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
        qp.setMargins(0, 0, dp(8), dp(8))
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
        ctrlView.addView(Ui.text(act, "Transporte do vídeo", 12f, Ui.MUTED), Ui.lp(act, -2, -2, t = 18))
        ctrlView.addView(
            Ui.group(act, listOf("UDP (mais rápido)", "TCP (mais estável)"), 0) { useTcp = it == 1; connect() },
            Ui.lp(act, -1, -2, t = 6)
        )
        buildTrackCard()
        val tools = LinearLayout(act)
        tools.gravity = Gravity.CENTER
        tools.addView(Ui.chip(act, "Ligar de novo", false) { connect() })
        tools.addView(Ui.chip(act, "Diagnóstico", false) { saveDiag() }, Ui.lp(act, -2, -2, l = 8))
        ctrlView.addView(tools, Ui.lp(act, -1, -2, t = 18))
        ctrlScroll.addView(ctrlView)
        panel.addView(ctrlScroll, Ui.lp(act, -1, 0, t = 4, weight = 1f))

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
        ctrlScroll.visibility = if (settings) View.GONE else View.VISIBLE
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

    private val trkTick = object : Runnable {
        override fun run() {
            if (!alive) return
            trkBtn.text = if (TrackService.running) "Parar registo" else "Iniciar registo"
            trkInfo.text = if (TrackService.running) {
                (if (TrackService.hasFix) "%.0f km/h".format(TrackService.speed) else "sem sinal GPS") +
                    "  ·  inclinação %.0f°  ·  subida %.0f°".format(TrackService.roll, TrackService.pitch)
            } else "Grava velocidade e inclinação do telemóvel para usar no Editor."
            ui.postDelayed(this, 500)
        }
    }

    private fun buildTrackCard() {
        ctrlView.addView(Ui.text(act, "Registo GPS e inclinação", 12f, Ui.MUTED), Ui.lp(act, -2, -2, t = 18))
        val row = LinearLayout(act)
        trkBtn = Ui.chip(act, "Iniciar registo", false) {
            if (TrackService.running) {
                TrackService.stop(act)
            } else {
                askPerms {
                    if (act.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) TrackService.start(act)
                    else setStatus("Sem permissão de localização: o registo não pode começar.")
                }
            }
        }
        row.addView(trkBtn)
        row.addView(Ui.chip(act, "Calibrar zero", false) { TrackService.calibrate() }, Ui.lp(act, -2, -2, l = 6))
        row.addView(Ui.chip(act, "Inverter sinal", false) { TrackService.invert = !TrackService.invert }, Ui.lp(act, -2, -2, l = 6))
        ctrlView.addView(row, Ui.lp(act, -2, -2, t = 6))
        ctrlView.addView(
            Ui.group(act, listOf("Telemóvel na vertical", "Telemóvel deitado"), 0) { TrackService.flat = it == 1 },
            Ui.lp(act, -1, -2, t = 6)
        )
        ctrlView.addView(trkInfo, Ui.lp(act, -2, -2, t = 6))
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
            TrackService.event(if (recording) "REC_START" else "REC_STOP")
            io.execute {
                val r = api.cgi("set", "Video", v)
                setStatus(if (r != null) (if (v == "recordon") "A gravar…" else "Gravação parada") else "Falhou")
            }
        }
    }

    private fun selectMode(n: Int) {
        Trace.log("selectMode $n")
        setStatus("A mudar para ${modeNames[n]}…")
        ui.post {
            ui.removeCallbacks(beat)
            ui.removeCallbacks(tick)
            try {
                player?.release()
            } catch (_: Exception) {
            }
            player = null
        }
        io.execute {
            try {
                var ok = false
                var resp = ""
                for (prop in listOf("sysworkmode", "Camera.Menu.sysworkmode")) {
                    resp = api.cgiRaw("set", prop, "MODE$n") ?: "sem resposta"
                    Trace.log("set $prop MODE$n -> ${resp.replace('\n', ' ').take(40)}")
                    for (i in 1..12) {
                        Thread.sleep(500)
                        val cur = api.cgi("get", "Camera.Menu.sysworkmode")?.get("Camera.Menu.sysworkmode")
                        if (cur == "MODE$n") {
                            ok = true
                            break
                        }
                    }
                    if (ok) break
                }
                if (!ok) {
                    setStatus("O modo não mudou. Resposta: " + resp.replace('\n', ' ').take(80))
                    ui.post { buildModeRow() }
                    connect()
                    return@execute
                }
                ui.post {
                    mode = n
                    recording = false
                    updateShutter()
                }
                // a câmara reinicia o pipeline de vídeo ao mudar de modo
                Trace.log("modo confirmado, espera")
                Thread.sleep(2500)
                connect()
            } catch (e: Throwable) {
                setStatus("Erro ao mudar de modo: ${e.javaClass.simpleName} ${e.message}")
            }
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
        Trace.log("attached")
        ui.removeCallbacks(trkTick)
        ui.post(trkTick)
        showLastCrash()
        connect()
    }

    // Mostra porque a app fechou da última vez (excepção Java ou motivo de saída do sistema).
    private fun showLastCrash() {
        try {
            val f = java.io.File(act.filesDir, "crash.txt")
            var msg: String? = null
            if (f.exists()) {
                msg = f.readText()
                f.renameTo(java.io.File(act.filesDir, "crash_shown.txt"))
            } else if (Build.VERSION.SDK_INT >= 30) {
                val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val ex = am.getHistoricalProcessExitReasons(act.packageName, 0, 1).firstOrNull()
                val sp = act.getSharedPreferences("diag", Context.MODE_PRIVATE)
                if (ex != null && ex.timestamp > sp.getLong("exitTs", 0L) &&
                    ex.reason in 2..9
                ) {
                    msg = "Saída anterior: motivo=${ex.reason} (2 sinal, 3 memória, 4 erro Java, 5 erro nativo, 6 ANR) ${ex.description}"
                }
                if (ex != null) sp.edit().putLong("exitTs", ex.timestamp).apply()
            }
            if (msg != null) {
                java.io.File(act.filesDir, "crash_shown.txt").writeText(msg)
                AlertDialog.Builder(act).setTitle("A app fechou da última vez")
                    .setMessage(msg.take(1200)).setPositiveButton("OK", null).show()
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDetachedFromWindow() {
        Trace.log("detached")
        alive = false
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDetachedFromWindow()
    }

    private var lastConnect = 0L
    private var lastUrl: String? = null

    private fun connect() {
        val now = System.currentTimeMillis()
        if (now - lastConnect < 3000) {
            Trace.log("connect ignorado (repetido)")
            return
        }
        lastConnect = now
        Trace.log("connect")
        setStatus("A ligar à câmara ${api.ip}…")
        io.execute {
            // Depois de sair do modo Playback a câmara demora a voltar: tenta até 4 vezes.
            var preview = api.cgi("get", "Camera.Preview.*")
            var tries = 1
            while (preview == null && tries < 4 && alive) {
                Thread.sleep(1500)
                preview = api.cgi("get", "Camera.Preview.*")
                tries++
            }
            if (preview == null) {
                setStatus("Sem resposta da câmara. Liga o Wi-Fi da câmara e toca em Ligar de novo.")
                return@execute
            }
            api.cgi("set", "StreamStatus", "ON")
            val url = api.liveUrl(preview["Camera.Preview.RTSP.av"])
            Trace.log("url $url")
            lastUrl = url
            ui.post {
                if (!alive) return@post
                try {
                    startPlayer(url)
                } catch (e: Throwable) {
                    setStatus("Erro no vídeo: ${e.javaClass.simpleName} ${e.message}")
                }
                ui.removeCallbacks(beat)
                ui.postDelayed(beat, 5000)
                ui.removeCallbacks(tick)
                ui.postDelayed(tick, 1000)
            }
            loadSettings()
        }
    }

    // Lê valores e menus do modo atual (corre fora do fio principal).
    private fun loadSettings() {
        try {
            loadSettings0()
        } catch (e: Throwable) {
            setStatus("Erro nas definições: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun loadSettings0() {
        Trace.log("loadSettings")
        val v = api.cgi("get", "Camera.Menu.*") ?: emptyMap()
        val n = v["Camera.Menu.sysworkmode"]?.removePrefix("MODE")?.toIntOrNull()
        val md = if (n != null && n in 0..7) n else mode
        val general = api.loadMenus(CameraApi.GENERAL_FILE)
        val modeMenus = api.loadMenus(CameraApi.modeFiles[md])
        ui.post {
            if (alive) {
                try {
                    Trace.log("buildSettings modo $md")
                    vals = v
                    mode = md
                    buildModeRow()
                    updateShutter()
                    buildSettings(general, modeMenus)
                } catch (e: Throwable) {
                    setStatus("Erro no ecrã de definições: ${e.javaClass.simpleName} ${e.message}")
                }
            }
        }
    }

    // Em segundo plano liberta o descodificador (evita ser morto por falta de memória).
    fun pauseStream() {
        Trace.log("pauseStream")
        ui.removeCallbacks(beat)
        ui.removeCallbacks(tick)
        player?.release()
        player = null
    }

    fun resumeStream() {
        if (player == null && alive) {
            Trace.log("resumeStream")
            connect()
        }
    }

    private fun startPlayer(url: String) {
        Trace.log("startPlayer")
        player?.release()
        // Modos de vídeo: stream mais pesado, buffer maior para não ciclar em rebuffer.
        val heavy = mode in 0..3
        val lc = DefaultLoadControl.Builder()
            .apply { if (heavy) setBufferDurationsMs(500, 2000, 250, 500) else setBufferDurationsMs(100, 500, 50, 100) }
            .build()
        val p = ExoPlayer.Builder(act).setLoadControl(lc).build()
        val live = MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(200)
            .setMinOffsetMs(if (heavy) 200 else 50)
            .setMaxOffsetMs(if (heavy) 1500 else 600)
            .setMinPlaybackSpeed(1.0f)
            .setMaxPlaybackSpeed(if (heavy) 1.05f else 1.5f)
            .build()
        val item = MediaItem.Builder().setUri(url).setLiveConfiguration(live).build()
        val src = RtspMediaSource.Factory().setForceUseRtpTcp(useTcp)
            .createMediaSource(item)
        p.setVideoTextureView(tv)
        p.setMediaSource(src)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.height > 0) ar.setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height)
            }

            override fun onPlaybackStateChanged(state: Int) {
                Trace.log("player estado $state")
                if (state == Player.STATE_READY) setStatus("Em direto")
            }

            override fun onPlayerError(error: PlaybackException) {
                Trace.log("player erro ${error.errorCodeName} ${error.message}")
                setStatus("Stream: ${error.errorCodeName}. A tentar de novo…")
                ui.postDelayed({ if (alive) player?.prepare() }, 3000)
            }
        })
        p.prepare()
        p.playWhenReady = true
        player = p
        applyFlip()
    }

    private val sdInfo = Ui.text(act, "A ler cartão…", 14f, Ui.TEXT)

    private fun readable(b: Long): String {
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < u.size - 1) {
            v /= 1024
            i++
        }
        return "%.1f %s".format(v, u[i])
    }

    private fun refreshSd() {
        io.execute {
            val raw = api.cgiRaw("get", "Camera.Menu.CardInfo.*")
            val txt = if (raw == null) "Sem resposta da câmara." else {
                val m = HashMap<String, String>()
                Regex("(\\w+)=([^,\\n]+)").findAll(raw).forEach { m[it.groupValues[1]] = it.groupValues[2].trim() }
                val parts = m["size"]?.split(":")
                val a = parts?.getOrNull(0)?.toLongOrNull()
                val b = parts?.getOrNull(1)?.toLongOrNull()
                if (a != null && b != null) "${readable(a)} / ${readable(b)}" else "Formato não reconhecido: " + raw.replace('\n', ' ').take(120)
            }
            ui.post { sdInfo.text = txt }
        }
    }

    private fun addSdCard() {
        setBox.addView(Ui.text(act, "Cartão de memória", 15f, Ui.ACCENT, true), Ui.lp(act, -1, -2, t = 16, b = 2))
        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.bg(Ui.CARD, 14, act)
        card.setPadding(dp(12), dp(10), dp(12), dp(12))
        card.addView(Ui.text(act, "Capacidade (livre / total, como a app original)", 13f, Ui.MUTED))
        card.addView(sdInfo, Ui.lp(act, -2, -2, t = 4))
        val row = LinearLayout(act)
        row.addView(Ui.chip(act, "Atualizar", false) { refreshSd() })
        row.addView(Ui.chip(act, "Formatar…", false) { confirmFormat() }, Ui.lp(act, -2, -2, l = 8))
        card.addView(row, Ui.lp(act, -2, -2, t = 8))
        setBox.addView(card, Ui.lp(act, -1, -2, t = 8))
        refreshSd()
    }

    private fun confirmFormat() {
        android.app.AlertDialog.Builder(act)
            .setTitle("Formatar cartão")
            .setMessage("Isto apaga TODAS as fotos e vídeos do cartão da câmara e não se pode desfazer. Continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Formatar") { _, _ ->
                setStatus("A formatar…")
                io.execute {
                    if (recording) api.cgi("set", "Video", "recordoff")
                    recording = false
                    val r = api.cgiRaw("set", "SD0", "format")
                    setStatus(if (r != null) "Formatação pedida" else "Falhou a formatar")
                    Thread.sleep(3000)
                    refreshSd()
                }
            }
            .show()
    }

    private fun buildSettings(general: List<CameraApi.Menu>, modeMenus: List<CameraApi.Menu>) {
        setBox.removeAllViews()
        val a = addSection("Modo atual: ${modeNames[mode]}", modeMenus)
        val b = addSection("Geral", general)
        addSdCard()
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
                val names = listOfNotNull(m.cmd, m.id).distinct()
                var raw = ""
                var cur2: String? = null
                var via = ""
                for (nm in names) {
                    raw = api.cgiRaw("set", nm, o.value) ?: "sem resposta"
                    Thread.sleep(300)
                    cur2 = api.cgi("get", m.id)?.get(m.id)
                    via = " (via $nm)"
                    if (cur2 == o.value) break
                }
                if (cur2 == o.value) {
                    vals = vals + (m.id to o.value)
                    api.cgi("set", "StreamStatus", "ON")
                    setStatus("${m.title}: ${o.label} confirmado$via")
                    // Reinicia só o stream para a imagem refletir a definição (sem recarregar o menu).
                    Thread.sleep(600)
                    val u = lastUrl
                    if (u != null) ui.post {
                        if (alive) try { startPlayer(u) } catch (_: Throwable) { }
                    }
                } else {
                    setStatus("Câmara não aceitou ${m.title}. Lê '$cur2'. Resp: " + raw.replace('\n', ' ').take(70))
                }
            }
        }
        card.addView(g, Ui.lp(act, -1, -2, t = 8))
        setBox.addView(card, Ui.lp(act, -1, -2, t = 8))
    }

    private fun saveDiag() {
        setStatus("A recolher diagnóstico…")
        io.execute {
            val crash = try {
                java.io.File(act.filesDir, "crash_shown.txt").takeIf { it.exists() }?.readText()
            } catch (_: Exception) {
                null
            }
            val txt = "##### ultima falha da app\n" + (crash ?: "(nenhuma)") + "\n\n##### passos recentes\n" + Trace.tail(120) + "\n\n" + api.diagnostics()
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
