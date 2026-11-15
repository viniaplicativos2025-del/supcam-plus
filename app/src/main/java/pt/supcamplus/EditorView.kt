package pt.supcamplus

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
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
    private val info = Ui.text(act, "Escolhe um vídeo do telemóvel.", 13f, Ui.MUTED)
    private val lblTrim = Ui.text(act, "", 13f, Ui.TEXT)
    private val sStart = SeekBar(act)
    private val sEnd = SeekBar(act)
    private val speeds = floatArrayOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)

    private var rotIdx = 0
    private var speedIdx = 2
    private var flipH = false
    private var flipV = false
    private var mute = false
    private var bright = 0f
    private var contr = 0f
    private var satur = 0f
    private var warmth = 0f
    private var player: ExoPlayer? = null
    private var uri: Uri? = null
    private var durMs = 0L
    private var exporting = false

    private fun dp(v: Int) = Ui.dp(act, v)

    init {
        setBackgroundColor(Ui.BG)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(14), dp(14), dp(24))
        addView(box)

        box.addView(Ui.text(act, "Editor de vídeo", 22f, Ui.TEXT, true))
        box.addView(Ui.button(act, "Escolher vídeo", true) { onPick() }, Ui.lp(act, -1, -2, t = 10))

        val vb = FrameLayout(act)
        vb.setBackgroundColor(Color.BLACK)
        ar.addView(tv, FrameLayout.LayoutParams(-1, -1))
        vb.addView(ar, FrameLayout.LayoutParams(-1, -1))
        box.addView(vb, Ui.lp(act, -1, 220, t = 12))
        box.addView(Ui.button(act, "Play / Pausa", false) {
            val p = player
            if (p != null) {
                if (p.isPlaying) p.pause() else p.play()
            }
        }, Ui.lp(act, -1, -2, t = 8))

        sStart.max = 1000
        sEnd.max = 1000
        sEnd.progress = 1000
        val tint = ColorStateList.valueOf(Ui.ACCENT)
        sStart.progressTintList = tint
        sStart.thumbTintList = tint
        sEnd.progressTintList = tint
        sEnd.thumbTintList = tint
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
        card("Cortar (início e fim)", sStart, sEnd, lblTrim)

        card("Rotação", Ui.group(act, listOf("0°", "90°", "180°", "270°"), 0) { rotIdx = it; previewTransform() })

        val flips = LinearLayout(act)
        flips.addView(toggle("Espelhar H", { flipH }, { flipH = it }))
        flips.addView(toggle("Espelhar V", { flipV }, { flipV = it }), Ui.lp(act, -2, -2, l = 6))
        flips.addView(toggle("Sem áudio", { mute }, { mute = it }), Ui.lp(act, -2, -2, l = 6))
        card("Espelho e áudio", flips)

        card(
            "Cor",
            slider("Brilho") { bright = it },
            slider("Contraste") { contr = it },
            slider("Saturação") { satur = it },
            slider("Tom (frio / quente)") { warmth = it },
            Ui.group(act, listOf("Cor", "Preto e branco"), 0) { satur = if (it == 1) -1f else 0f; applyColorPreview() }
        )

        card("Velocidade", Ui.group(act, speeds.map { "${it}×" }, speedIdx) { speedIdx = it })

        box.addView(Ui.button(act, "Exportar vídeo", true) { export() }, Ui.lp(act, -1, -2, t = 16))
        box.addView(Ui.button(act, "Guardar fotograma atual", false) { saveFrame() }, Ui.lp(act, -1, -2, t = 8))
        box.addView(info, Ui.lp(act, -1, -2, t = 12))
    }

    private fun slider(label: String, set: (Float) -> Unit): LinearLayout {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.VERTICAL
        val t = Ui.text(act, "$label: 0", 12f, Ui.MUTED)
        val sb = SeekBar(act)
        sb.max = 200
        sb.progress = 100
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = (p - 100) / 100f
                t.text = "$label: ${p - 100}"
                set(v)
                if (fromUser) applyColorPreview()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        row.addView(t)
        row.addView(sb)
        return row
    }

    private fun colorEffects(): List<Effect> {
        val l = ArrayList<Effect>()
        if (bright != 0f) l.add(Brightness(bright * 0.5f))
        if (contr != 0f) l.add(Contrast(contr))
        if (satur != 0f) l.add(HslAdjustment.Builder().adjustSaturation(satur * 100f).build())
        if (warmth != 0f) l.add(HslAdjustment.Builder().adjustHue(warmth * -20f).build())
        return l
    }

    private fun applyColorPreview() {
        try {
            player?.setVideoEffects(colorEffects())
        } catch (e: Exception) {
            info.text = "Pré-visualização de cor indisponível; aplica-se ao exportar."
        }
    }

    private fun card(title: String, vararg views: View) {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.background = Ui.bg(Ui.CARD, 14, act)
        c.setPadding(dp(12), dp(10), dp(12), dp(12))
        c.addView(Ui.text(act, title, 13f, Ui.MUTED))
        for (x in views) c.addView(x, Ui.lp(act, -1, -2, t = 8))
        box.addView(c, Ui.lp(act, -1, -2, t = 12))
    }

    private fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit): TextView {
        val c = Ui.chip(act, label, false) {}
        c.setOnClickListener {
            set(!get())
            Ui.styleChip(c, get(), act)
            previewTransform()
        }
        return c
    }

    private fun startMs() = durMs * sStart.progress / 1000
    private fun endMs() = durMs * sEnd.progress / 1000
    private fun rotDeg() = rotIdx * 90

    private fun fmt(ms: Long): String = "%d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)

    private fun updateTrimLabel() {
        lblTrim.text = "${fmt(startMs())} → ${fmt(endMs())}  (duração ${fmt(endMs() - startMs())})"
    }

    private fun previewTransform() {
        tv.rotation = if (rotDeg() == 180) 180f else 0f
        tv.scaleX = if (flipH) -1f else 1f
        tv.scaleY = if (flipV) -1f else 1f
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
        val sx = if (flipH) -1f else 1f
        val sy = if (flipV) -1f else 1f
        if (rot != 0 || sx != 1f || sy != 1f) {
            video.add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(rot.toFloat())
                    .setScale(sx, sy)
                    .build()
            )
        }
        video.addAll(colorEffects())
        val speed = speeds[speedIdx]
        if (speed != 1f) {
            video.add(SpeedChangeEffect(speed))
            val sonic = SonicAudioProcessor()
            sonic.setSpeed(speed)
            sonic.setPitch(1f)
            audio.add(sonic)
        }
        val item = EditedMediaItem.Builder(mi)
            .setRemoveAudio(mute)
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
            m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f, bmp.width / 2f, bmp.height / 2f)
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
