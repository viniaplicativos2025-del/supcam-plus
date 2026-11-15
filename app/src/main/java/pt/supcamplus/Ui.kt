package pt.supcamplus

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

// Estilo comum (tema escuro, cartões arredondados, "chips" de seleção).
object Ui {
    val BG = 0xFF0E1116.toInt()
    val CARD = 0xFF1A1F29.toInt()
    val CHIP = 0xFF2A3140.toInt()
    val ACCENT = 0xFFFF5A36.toInt()
    val TEXT = 0xFFF2F4F8.toInt()
    val MUTED = 0xFF8B94A5.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    private fun d(c: Context, v: Int): Int = if (v < 0) v else dp(c, v)

    // Larguras/alturas em dp (valores negativos = MATCH/WRAP).
    fun lp(
        c: Context, w: Int, h: Int,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, weight: Float = 0f
    ): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(d(c, w), d(c, h), weight)
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b))
        return p
    }

    fun bg(color: Int, radiusDp: Int, c: Context): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(c, radiusDp).toFloat()
        return g
    }

    fun text(c: Context, s: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    fun styleChip(t: TextView, selected: Boolean, c: Context) {
        t.background = bg(if (selected) ACCENT else CHIP, 18, c)
        t.setTextColor(if (selected) Color.WHITE else TEXT)
        t.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    fun chip(c: Context, s: String, selected: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = 13f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
        styleChip(t, selected, c)
        t.setOnClickListener { onClick() }
        return t
    }

    // Grupo de escolha única, deslizável na horizontal.
    fun group(c: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): HorizontalScrollView {
        val hs = HorizontalScrollView(c)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        hs.addView(row)
        val chips = ArrayList<TextView>()
        labels.forEachIndexed { i, l ->
            val ch = chip(c, l, i == selected) {}
            ch.setOnClickListener {
                chips.forEachIndexed { j, x -> styleChip(x, j == i, c) }
                onSelect(i)
            }
            chips.add(ch)
            row.addView(ch, lp(c, -2, -2, r = 6))
        }
        return hs
    }

    fun button(c: Context, label: String, primary: Boolean, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 15f
        b.background = bg(if (primary) ACCENT else CHIP, 14, c)
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
        b.setOnClickListener { onClick() }
        return b
    }
}
