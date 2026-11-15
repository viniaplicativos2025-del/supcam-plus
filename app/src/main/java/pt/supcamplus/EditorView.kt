package pt.supcamplus

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.AspectRatioFrameLayout
import java.io.File
import java.io.FileInputStream

// Editor: cortar, rodar (90/180/270), espelhar, velocidade, sem áudio, guardar fotograma.
class EditorView(private val act: ComponentActivity, private val onPick: () -> Unit) : ScrollView(act) {

    private val ui = Handler(Looper.getMainLooper())
    private val box = LinearLayout(act)
    private val ar = AspectRatioFrameLayout(act)
    private val tv = TextureView(act)
    private val info = TextView(act)
    private val lblTrim = TextView(act)
    private val sStart = SeekBar(act)
    private val sEnd = SeekBar(act)
    private val spRot = Spinner(act)
    private val spSpeed = Spinner(act)
    private val cbH = CheckBox(act)
    private val cbV = CheckBox(act)
    private val cbMute = CheckBox(act)
    private val speeds = floatArrayOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)

    private var player: ExoPlayer? = null
    private var uri: Uri? = null
    private var durMs = 0L
    private var exporting = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(box)

        val pick = Button(act)
        pick.text = "Escolher vídeo"
        pick.setOnClickListener { onPick() }
        box.addView(pick)

        ar.addView(tv, FrameLayout.LayoutParams(-1, -1))
        box.addView(ar, LinearLayout.LayoutParams(-1, dp(240)))

        val play = Button(act)
        play.text = "Play / Pausa"
        play.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
        }
        box.addView(play)

        label("Cortar: início / fim")
        sStart.max = 1000
        sEnd.max = 1000
        sEnd.progress = 1000
        box.addView(sStart)
        box.addView(sEnd)
        box.addView(lblTrim)
        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (sStart.progress >= sEnd.progress) {
                    if (s === sStart) sStart.progress = (sEnd.progress - 1).coerceAtLeast(0)
                    else sEnd.progress = (sStart.progress + 1).coerceAtMost(1000)
                }
                updateTrimLabel()
                if (s === sStart) player?.seekTo(startMs())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        sStart.setOnSeekBarChangeListener(l)
        sEnd.setOnSeekBarChangeListener(l)

        label("Rotação")
        spRot.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, listOf("0°", "90°", "180°", "270°"))
        box.addView(spRot)
        spRot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = previewTransform()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        cbH.text = "Espelhar na horizontal"
        cbV.text = "Espelhar na vertical"
        cbMute.text = "Sem áudio"
        cbH.setOnClickListener { previewTransform() }
        cbV.setOnClickListener { previewTransform() }
        box.addView(cbH)
        box.addView(cbV)
        box.addView(cbMute)

        label("Velocidade")
        spSpeed.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, speeds.map { "${it}×" })
        spSpeed.setSelection(2)
        box.addView(spSpeed)

        val exp = Button(act)
        exp.text = "Exportar vídeo"
        exp.setOnClickListener { export() }
        box.addView(exp)

        val frame = Button(act)
        frame.text = "Guardar fotograma atual"
        frame.setOnClickListener { saveFrame() }
        box.addView(frame)

        info.text = "Escolhe um vídeo do telemóvel."
        box.addView(info)
    }

    private fun label(t: String) {
        val v = TextView(act)
        v.text = t
        v.setPadding(0, dp(12), 0, 0)
        box.addView(v)
    }

    private fun startMs() = durMs * sStart.progress / 1000
    private fun endMs() = durMs * sEnd.progress / 1000
    private fun rotDeg() = spRot.selectedItemPosition * 90

    private fun fmt(ms: Long): String = "%d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)

    private fun updateTrimLabel() {
        lblTrim.text = "${fmt(startMs())} → ${fmt(endMs())}  (duração ${fmt(endMs() - startMs())})"
    }

    private fun previewTransform() {
        tv.rotation = if (rotDeg() == 180) 180f else 0f
        tv.scaleX = if (cbH.isChecked) -1f else 1f
        tv.scaleY = if (cbV.isChecked) -1f else 1f
        if (rotDeg() == 90 || rotDeg() == 270) info.text = "A rotação de 90°/270° só aparece no vídeo exportado."
    }

    fun load(u: Uri) {
        player?.release()
        uri = u
        durMs = 0
        sStart.progress = 0
        sEnd.progress = 1000
        val p = ExoPlayer.Builder(act).build()
        p.setVideoTextureView(tv)
        p.setMediaItem(MediaItem.fromUri(u))
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.height > 0) ar.setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && durMs == 0L) {
                    durMs = p.duration
                    updateTrimLabel()
                    info.text = "Vídeo carregado."
                }
            }
        })
        p.prepare()
        player = p
        previewTransform()
    }

    override fun onDetachedFromWindow() {
        player?.pause()
        super.onDetachedFromWindow()
    }

    private fun export() {
        val u = uri
        if (u == null || durMs == 0L) {
            info.text = "Escolhe primeiro um vídeo."
            return
        }
        if (exporting) return
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs())
            .setEndPositionMs(endMs())
            .build()
        val mi = MediaItem.Builder().setUri(u).setClippingConfiguration(clip).build()

        val video = ArrayList<Effect>()
        val audio = ArrayList<AudioProcessor>()
        val rot = rotDeg()
        val sx = if (cbH.isChecked) -1f else 1f
        val sy = if (cbV.isChecked) -1f else 1f
        if (rot != 0 || sx != 1f || sy != 1f) {
            video.add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(rot.toFloat())
                    .setScale(sx, sy)
                    .build()
            )
        }
        val speed = speeds[spSpeed.selectedItemPosition]
        if (speed != 1f) {
            video.add(SpeedChangeEffect(speed))
            val sonic = SonicAudioProcessor()
            sonic.setSpeed(speed)
            sonic.setPitch(1f)
            audio.add(sonic)
        }
        val item = EditedMediaItem.Builder(mi)
            .setRemoveAudio(cbMute.isChecked)
            .setEffects(Effects(audio, video))
            .build()

        val out = File(act.cacheDir, "out_${System.currentTimeMillis()}.mp4")
        val transformer = Transformer.Builder(act)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    exporting = false
                    val name = "SupCamPlus_${System.currentTimeMillis()}.mp4"
                    val ok = saveToGallery(out, name)
                    out.delete()
                    info.text = if (ok) "Exportado para Filmes/SupCamPlus/$name" else "Exportado, mas falhou ao guardar na galeria."
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    exporting = false
                    info.text = "Erro ao exportar: ${exportException.errorCodeName}"
                }
            })
            .build()
        exporting = true
        info.text = "A exportar…"
        transformer.start(item, out.path)

        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (!exporting) return
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    info.text = "A exportar… ${holder.progress}%"
                }
                ui.postDelayed(this, 500)
            }
        }
        ui.postDelayed(poll, 500)
    }

    private fun saveToGallery(f: File, name: String): Boolean = try {
        val cv = ContentValues()
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name)
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SupCamPlus")
        val u = act.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
        act.contentResolver.openOutputStream(u)!!.use { o -> FileInputStream(f).use { it.copyTo(o) } }
        true
    } catch (e: Exception) {
        false
    }

    private fun saveFrame() {
        val u = uri
        val p = player
        if (u == null || p == null) {
            info.text = "Escolhe primeiro um vídeo."
            return
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(act, u)
            var bmp: Bitmap = r.getFrameAtTime(p.currentPosition * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: run {
                    info.text = "Não consegui obter o fotograma."
                    return
                }
            val m = Matrix()
            m.postScale(if (cbH.isChecked) -1f else 1f, if (cbV.isChecked) -1f else 1f, bmp.width / 2f, bmp.height / 2f)
            m.postRotate(-rotDeg().toFloat())
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val cv = ContentValues()
            val name = "SupCamPlus_${System.currentTimeMillis()}.jpg"
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SupCamPlus")
            val out = act.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
            act.contentResolver.openOutputStream(out)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            info.text = "Fotograma guardado em Imagens/SupCamPlus/$name"
        } catch (e: Exception) {
            info.text = "Erro: ${e.message}"
        } finally {
            r.release()
        }
    }
}
