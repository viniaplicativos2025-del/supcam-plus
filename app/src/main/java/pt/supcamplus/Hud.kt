package pt.supcamplus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max

// Desenha velocímetro e gráficos por cima do vídeo (bitmap transparente do tamanho do fotograma).
object Hud {

    private fun paint(c: Int, style: Paint.Style = Paint.Style.FILL, sw: Float = 0f): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = c
        p.style = style
        p.strokeWidth = sw
        return p
    }

    fun draw(d: TrackData, absMs: Long, w: Int, h: Int, showSpeed: Boolean, showTilt: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val u = w / 1280f
        val i = d.idx(absMs)
        if (i < 0) return bmp
        val panelW = 360f * u
        val panelH = 200f * u
        val m = 24f * u
        val bg = paint(0x99000000.toInt())
        val white = paint(Color.WHITE)
        val accent = paint(0xFFFF5A36.toInt())

        if (showSpeed) {
            val r = RectF(m, h - m - panelH, m + panelW, h - m)
            cv.drawRoundRect(r, 16f * u, 16f * u, bg)
            white.textSize = 64f * u
            white.isFakeBoldText = true
            cv.drawText("%.0f".format(d.speed[i]), r.left + 16f * u, r.top + 70f * u, white)
            white.isFakeBoldText = false
            white.textSize = 22f * u
            cv.drawText("km/h", r.left + 150f * u, r.top + 70f * u, white)
            graph(cv, d, d.speed, absMs, RectF(r.left + 16f * u, r.top + 90f * u, r.right - 16f * u, r.bottom - 14f * u), u, 0f, null)
        }
        if (showTilt) {
            val r = RectF(w - m - panelW, h - m - panelH, w - m, h - m)
            cv.drawRoundRect(r, 16f * u, 16f * u, bg)
            // indicador: linha inclinada pelo ângulo lateral
            val cx = r.left + 60f * u
            val cy = r.top + 52f * u
            val rad = 36f * u
            cv.drawCircle(cx, cy, rad, paint(0x55FFFFFF, Paint.Style.STROKE, 3f * u))
            val a = Math.toRadians(d.roll[i].toDouble())
            val dx = (Math.cos(a) * rad).toFloat()
            val dy = (Math.sin(a) * rad).toFloat()
            cv.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint(0xFFFF5A36.toInt(), Paint.Style.STROKE, 6f * u))
            white.textSize = 38f * u
            white.isFakeBoldText = true
            cv.drawText("%.0f°".format(abs(d.roll[i])), r.left + 120f * u, r.top + 50f * u, white)
            white.isFakeBoldText = false
            white.textSize = 20f * u
            cv.drawText("inclinação", r.left + 122f * u, r.top + 74f * u, white)
            cv.drawText("subida %.0f°".format(d.pitch[i]), r.left + 235f * u, r.top + 74f * u, white)
            graph(cv, d, d.roll, absMs, RectF(r.left + 16f * u, r.top + 90f * u, r.right - 16f * u, r.bottom - 14f * u), u, 0f, 0f)
        }
        return bmp
    }

    // Janela de 60 s centrada no instante atual; cursor no centro.
    private fun graph(cv: Canvas, d: TrackData, v: List<Float>, absMs: Long, r: RectF, u: Float, minHint: Float, zero: Float?) {
        val half = 30000L
        val a = d.idx(absMs - half)
        val b = d.idx(absMs + half)
        if (a < 0 || b <= a) return
        var lo = minHint
        var hi = minHint
        for (k in a..b) {
            lo = minOf(lo, v[k])
            hi = max(hi, v[k])
        }
        if (hi - lo < 5f) hi = lo + 5f
        val path = Path()
        var first = true
        for (k in a..b) {
            val x = r.left + (d.t[k] - (absMs - half)).toFloat() / (2 * half) * r.width()
            val y = r.bottom - (v[k] - lo) / (hi - lo) * r.height()
            if (x < r.left || x > r.right) continue
            if (first) {
                path.moveTo(x, y)
                first = false
            } else path.lineTo(x, y)
        }
        if (zero != null) {
            val y0 = r.bottom - (zero - lo) / (hi - lo) * r.height()
            val z = paint(0x44FFFFFF, Paint.Style.STROKE, 1.5f * u)
            cv.drawLine(r.left, y0, r.right, y0, z)
        }
        cv.drawPath(path, paint(0xFFFF5A36.toInt(), Paint.Style.STROKE, 3f * u))
        val cx = r.left + r.width() / 2
        cv.drawLine(cx, r.top, cx, r.bottom, paint(Color.WHITE, Paint.Style.STROKE, 2f * u))
    }
}
