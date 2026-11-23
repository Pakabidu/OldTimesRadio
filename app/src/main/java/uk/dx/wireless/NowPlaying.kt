package uk.dx.wireless

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils

/**
 * The "now playing" strip under the set: a backlit amber window in a worn chrome bezel,
 * lit like the dial. A small engraved heading, then what the station says is on (or the
 * station's name), shrunk to fit and cut short with an ellipsis if it's very long.
 */
class NowPlayingView(ctx: Context) : CabinetView(ctx), MoodGlow {
    var heading = "NOW PLAYING"
        set(v) { if (v != field) { field = v; invalidate() } }
    var text = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    /** How far the lamp behind the window is up (0 cold, 1 full). */
    var glow = 1f
        set(v) { if (v != field) { field = v; invalidate() } }

    private val outer = RectF()
    private val inner = RectF()
    private val clip = Path()
    private val head = paint { typeface = Radio.SERIF; textSize = 6.5f * t; letterSpacing = 0.3f; textAlign = Paint.Align.CENTER }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Radio.SERIF; textSize = 11 * t; letterSpacing = 0.04f; textAlign = Paint.Align.CENTER }
    private val rim get() = 6 * d

    override fun glows(): List<Glow> = if (inner.isEmpty) emptyList() else
        listOf(Glow(RectF(inner), Mood.AMBER, 0.4f + 0.7f * glow, screen = true, radius = 6 * d))

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (2 * rim + 6.5f * t + 11 * t + 20 * d).toInt())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        outer.set(0f, 0f, w.toFloat(), h.toFloat())
        inner.set(rim, rim, w - rim, h - rim)
        clip.reset(); clip.addRoundRect(inner, 6 * d, 6 * d, Path.Direction.CW)
    }

    override fun onDraw(c: Canvas) {
        Screen.bezel(c, outer, 9 * d, 61, d)
        c.save(); c.clipPath(clip)
        Screen.face(c, inner, glow)
        head.color = Screen.gold(glow)
        body.color = Screen.cream(glow)
        val maxW = inner.width() - 16 * d
        fitText(c, heading, inner.centerX(), inner.top + 5 * d + head.textSize, head, maxW)
        // Shrink a little for long titles, then cut them short rather than run off the glass.
        val size = body.textSize
        while (body.measureText(text) > maxW && body.textSize > 9 * t) body.textSize *= 0.95f
        val shown = TextUtils.ellipsize(text, body, maxW, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, inner.centerX(), inner.bottom - 6 * d - body.descent(), body)
        body.textSize = size
        Screen.glass(c, inner, 63, d, dust = 40)
        c.restore()
        // Two little screws holding the bezel.
        Hardware.screw(c, outer.left + 3.5f * d, outer.centerY(), d, 1.6f)
        Hardware.screw(c, outer.right - 3.5f * d, outer.centerY(), d, 1.6f)
    }
}
