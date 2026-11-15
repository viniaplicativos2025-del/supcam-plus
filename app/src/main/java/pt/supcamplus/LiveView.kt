package pt.supcamplus

import android.content.ContentValues
import android.graphics.Color
import android.provider.MediaStore
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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

// Vídeo em direto + painel de definições que fica por cima da imagem (nunca a fecha).
class LiveView(private val act: ComponentActivity, private val api: CameraApi) : FrameLayout(act) {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val ar = AspectRatioFrameLayout(act)
    private val tv = TextureView(act)
    private val status = TextView(act)
    private val panel = ScrollView(act)
    private val panelBox = LinearLayout(act)
    private var player: ExoPlayer? = null
    private var rot = 0f
    private var mirror = false
    private var recording = false
    private var alive = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val beat = object : Runnable {
        override fun run() {
            if (!alive) return
            io.execute { api.cgi("set", "StreamStatus", "ON") }
            ui.postDelayed(this, 5000)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        ar.addView(tv, FrameLayout.LayoutParams(-1, -1))
        addView(ar, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        status.setTextColor(Color.WHITE)
        status.setBackgroundColor(0x88000000.toInt())
        status.setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))

        panelBox.orientation = LinearLayout.VERTICAL
        panelBox.setPadding(dp(8), dp(8), dp(8), dp(48))
        panel.addView(panelBox)
        panel.setBackgroundColor(0xDD000000.toInt())
        panel.visibility = View.GONE
        addView(panel, FrameLayout.LayoutParams(dp(240), -1, Gravity.START))

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        fun btn(t: String, f: (Button) -> Unit) {
            val b = Button(act)
            b.text = t
            b.setOnClickListener { f(b) }
            col.addView(b)
        }
        btn("Definições") {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btn("Foto") {
            io.execute {
                val r = api.cgi("set", "Video", "capture")
                setStatus(if (r != null) "Foto pedida" else "Falhou")
            }
        }
        btn("REC") { b ->
            recording = !recording
            b.text = if (recording) "STOP" else "REC"
            val v = if (recording) "recordon" else "recordoff"
            io.execute {
                val r = api.cgi("set", "Video", v)
                setStatus(if (r != null) v else "Falhou")
            }
        }
        btn("Rodar 180°") { rot = if (rot == 0f) 180f else 0f; applyFlip() }
        btn("Espelhar") { mirror = !mirror; applyFlip() }
        btn("Ligar") { connect() }
        btn("Diagnóstico") { saveDiag() }
        addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP))
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

    private fun applyFlip() {
        tv.rotation = rot
        tv.scaleX = if (mirror) -1f else 1f
    }

    private fun setStatus(s: String) {
        ui.post { status.text = s }
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
                setStatus("Sem resposta da câmara. Liga o Wi-Fi da câmara e toca em Ligar.")
                return@execute
            }
            api.cgi("set", "StreamStatus", "ON")
            val url = api.liveUrl(preview["Camera.Preview.RTSP.av"])
            ui.post {
                startPlayer(url)
                ui.removeCallbacks(beat)
                ui.postDelayed(beat, 5000)
            }
            val vals = api.cgi("get", "Camera.Menu.*") ?: emptyMap()
            val menus = api.loadMenus()
            ui.post { if (alive) buildPanel(menus, vals) }
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
                if (state == Player.STATE_READY) setStatus("Direto: $url")
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

    private fun buildPanel(menus: List<CameraApi.Menu>, vals: Map<String, String>) {
        panelBox.removeAllViews()
        val spinKey = "Camera.Menu.MovieSpin"
        val list = ArrayList(menus)
        if (list.none { it.id == spinKey }) {
            list.add(
                CameraApi.Menu(
                    spinKey, "Rotação da câmara (também grava)",
                    listOf("Ori_0", "Ori_90", "Ori_180", "Ori_270")
                        .map { CameraApi.Opt(it, it.removePrefix("Ori_") + "°") }
                )
            )
        }
        val shown = HashSet<String>()
        for (m in list) {
            if (m.opts.isEmpty()) continue
            if (!vals.containsKey(m.id) && m.id != spinKey) continue
            shown.add(m.id)
            addMenuRow(m, vals[m.id])
        }
        if (shown.size <= 1) {
            // Fallback: a câmara não deu menus legíveis; mostra os valores em bruto, editáveis.
            for ((k, v) in vals) {
                if (k in shown || k.contains("CardInfo")) continue
                addRawRow(k, v)
            }
        }
    }

    private fun addMenuRow(m: CameraApi.Menu, current: String?) {
        val t = TextView(act)
        t.text = m.title
        t.setTextColor(Color.WHITE)
        t.setPadding(dp(8), dp(10), dp(8), 0)
        val sp = Spinner(act)
        sp.adapter = object : ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, m.opts.map { it.label }) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent)
                (v as TextView).setTextColor(Color.WHITE)
                return v
            }
        }
        var cur = m.opts.indexOfFirst { it.value == current }.coerceAtLeast(0)
        sp.setSelection(cur, false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == cur) return
                cur = pos
                val o = m.opts[pos]
                setStatus("A aplicar: ${m.title}…")
                io.execute {
                    val r = api.cgi("set", m.id, o.value)
                    setStatus(if (r != null) "${m.title}: ${o.label}" else "Falhou: ${m.title}")
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        panelBox.addView(t)
        panelBox.addView(sp)
    }

    private fun addRawRow(k: String, v: String) {
        val t = TextView(act)
        t.text = k.removePrefix("Camera.Menu.")
        t.setTextColor(Color.WHITE)
        t.setPadding(dp(8), dp(10), dp(8), 0)
        val et = EditText(act)
        et.setText(v)
        et.setTextColor(Color.WHITE)
        et.setSingleLine()
        val b = Button(act)
        b.text = "Aplicar"
        b.setOnClickListener {
            val nv = et.text.toString()
            io.execute {
                val r = api.cgi("set", k, nv)
                setStatus(if (r != null) "$k = $nv" else "Falhou: $k")
            }
        }
        panelBox.addView(t)
        panelBox.addView(et)
        panelBox.addView(b)
    }
}
