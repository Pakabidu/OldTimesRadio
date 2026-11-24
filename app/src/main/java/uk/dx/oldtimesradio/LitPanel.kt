package uk.dx.oldtimesradio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A ruled list printed on glass in the lid (the station log, the favourite songs). In
 * daylight it's plain cream on black; with the mood light on, a lamp behind it comes up:
 * a warm amber backlight under old, dusty glass, and the lettering glows, like the dial.
 * The room's darkness then leaves it alone (it reports itself as a lit screen).
 */
class LitPanel(ctx: Context) : LinearLayout(ctx), MoodGlow {
    private val d = resources.displayMetrics.density
    private val r = RectF()
    private val clip = Path()
    private val base = Paint().apply { color = Color.rgb(0x22, 0x14, 0x08) }
    private val lamp = Paint(Paint.ANTI_ALIAS_FLAG)

    init { orientation = VERTICAL }

    override fun dispatchDraw(c: Canvas) {
        if (Mood.on && width > 0 && height > 0) {
            r.set(d, d, width - d, height - d)
            c.save()
            clip.reset(); clip.addRect(r, Path.Direction.CW); c.clipPath(clip)
            c.drawRect(r, base)
            // Bulbs behind the glass: brightest in the middle, warm to the edges.
            lamp.shader = RadialGradient(r.centerX(), r.centerY(), maxOf(r.width(), r.height()) * 0.75f,
                intArrayOf(Color.argb(0xC0, 0xB0, 0x70, 0x2C), Color.argb(0x70, 0x7A, 0x44, 0x16), Color.argb(0x10, 0x40, 0x20, 0x08)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(r, lamp)
            Screen.glass(c, r, 71 + id, d, dust = (r.width() * r.height() / (d * d) / 350).toInt().coerceIn(20, 300))
            c.restore()
        }
        super.dispatchDraw(c)
    }

    override fun glows(): List<Glow> = if (Mood.on && width > 0)
        listOf(Glow(RectF(0f, 0f, width.toFloat(), height.toFloat()), Mood.AMBER, 0.8f, screen = true, radius = 2 * d))
        else emptyList()

    /** The lettering glows under the mood light (the cream heading strip keeps its ink). */
    fun applyMood() = walk(this)

    private fun walk(v: View) {
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        if (v is TextView && v.currentTextColor != Radio.INK) {
            if (Mood.on) v.setShadowLayer(7 * d, 0f, 0f, Mood.AMBER) else v.setShadowLayer(0f, 0f, 0f, 0)
        }
    }
}
