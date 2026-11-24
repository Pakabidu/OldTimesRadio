package uk.dx.oldtimesradio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent

/**
 * The "now playing" strip under the set: a backlit amber window in a worn chrome bezel,
 * lit like the dial. A small engraved heading, then what the station says is on (or the
 * station's name), shrunk to fit and cut short with an ellipsis if it's very long.
 *
 * Beside it, the heart button: a bakelite push-button in a chrome ring with a heart-shaped
 * glass window, lit from behind like the set's jewel lamps. It glows warm amber when
 * there's a song to save, deep red once the song is saved ([onHeart] saves or unsaves),
 * and only faintly when the station isn't sending a title. It warms and dims with the set.
 */
class NowPlayingView(ctx: Context) : CabinetView(ctx), MoodGlow {
    var heading = "NOW PLAYING"
        set(v) { if (v != field) { field = v; invalidate() } }
    var text = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    /** How far the lamp behind the window is up (0 cold, 1 full). */
    var glow = 1f
        set(v) { if (v != field) { field = v; invalidate(); Mood.relight() } }

    /** What the heart button shows: no song to save, a song, or a song that's saved. */
    enum class Heart { NONE, SONG, SAVED }
    var heart = Heart.NONE
        set(v) { if (v != field) { field = v; invalidate(); Mood.relight() } }
    var onHeart: (() -> Unit)? = null

    private val outer = RectF()
    private val inner = RectF()
    private val clip = Path()
    private val head = paint { typeface = Radio.SERIF; textSize = 6.5f * t; letterSpacing = 0.3f; textAlign = Paint.Align.CENTER }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Radio.SERIF; textSize = 11 * t; letterSpacing = 0.04f; textAlign = Paint.Align.CENTER }
    private val ring = paint()
    private val dome = paint()
    private val well = paint(Color.argb(0x66, 0, 0, 0))
    private val engrave = paint { style = Paint.Style.STROKE; strokeWidth = 1.3f * d; strokeJoin = Paint.Join.ROUND }
    private val lit = paint()
    private val halo = paint()
    private val domeClip = Path()
    private val heartPath = Path()
    private val r = RectF()
    private val rim get() = 6 * d

    // The heart button, to the right of the window.
    private var hx = 0f; private var hy = 0f; private var hr = 0f
    private var pressed = false

    override fun glows(): List<Glow> {
        if (inner.isEmpty) return emptyList()
        val out = arrayListOf(Glow(RectF(inner), Mood.AMBER, 0.4f + 0.7f * glow, screen = true, radius = 6 * d))
        val hg = RectF(hx - hr * 0.8f, hy - hr * 0.8f, hx + hr * 0.8f, hy + hr * 0.8f)
        when (heart) {
            Heart.SAVED -> out += Glow(hg, HEART_RED, 0.6f + 0.5f * glow)
            Heart.SONG -> out += Glow(hg, Mood.LAMP, 0.5f + 0.4f * glow)
            Heart.NONE -> out += Glow(hg, Mood.AMBER, 0.15f)
        }
        return out
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (2 * rim + 6.5f * t + 11 * t + 20 * d).toInt())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        hr = minOf(h * 0.4f, 19 * d)
        val btnW = hr * 2.4f + 6 * d
        outer.set(0f, 0f, w - btnW, h.toFloat())
        inner.set(rim, rim, outer.right - rim, h - rim)
        clip.reset(); clip.addRoundRect(inner, 6 * d, 6 * d, Path.Direction.CW)
        hx = w - btnW / 2 + 2 * d; hy = h / 2f
        // A heart, drawn once around the button's centre.
        val s = hr * 0.62f
        heartPath.reset()
        heartPath.moveTo(hx, hy + s * 0.85f)
        heartPath.cubicTo(hx - s * 1.35f, hy - s * 0.05f, hx - s * 0.75f, hy - s * 1.05f, hx, hy - s * 0.45f)
        heartPath.cubicTo(hx + s * 0.75f, hy - s * 1.05f, hx + s * 1.35f, hy - s * 0.05f, hx, hy + s * 0.85f)
        heartPath.close()
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
        drawHeart(c)
    }

    private fun drawHeart(c: Canvas) {
        // Chrome ring, worn to brass where fingers have pressed past it.
        ring.shader = LinearGradient(hx - hr * 1.2f, hy - hr * 1.2f, hx + hr * 1.2f, hy + hr * 1.2f,
            intArrayOf(Color.rgb(0xF0, 0xF0, 0xEC), Radio.CHROME_DARK, Color.rgb(0xDA, 0xDA, 0xD4), Color.rgb(0x50, 0x50, 0x4C)), null, Shader.TileMode.CLAMP)
        c.drawCircle(hx, hy, hr * 1.18f, ring)
        r.set(hx - hr * 1.18f, hy - hr * 1.18f, hx + hr * 1.18f, hy + hr * 1.18f)
        c.save(); c.clipRect(r); Wear.chips(c, r, 64, 4, d, Color.argb(150, 0xB8, 0x8A, 0x40)); c.restore()
        c.drawCircle(hx, hy, hr * 1.02f, well)
        // The bakelite button, a touch lower while held down.
        val dy = if (pressed) 1.2f * d else 0f
        dome.shader = RadialGradient(hx - hr * 0.3f, hy - hr * 0.35f + dy, hr * 1.3f,
            intArrayOf(Color.rgb(0x4A, 0x36, 0x28), Color.rgb(0x22, 0x18, 0x12), Color.rgb(0x0A, 0x07, 0x05)), null, Shader.TileMode.CLAMP)
        c.drawCircle(hx, hy + dy, hr * 0.92f, dome)
        c.save(); c.translate(0f, dy)
        // The lamp behind the heart: its colour and how bright, following the set's own lamps.
        val (core, deep, bright) = when (heart) {
            Heart.SAVED -> Triple(HEART_RED, Color.rgb(0x7A, 0x12, 0x0C), 0.75f + 0.25f * glow)
            Heart.SONG -> Triple(HEART_AMBER, Color.rgb(0x8A, 0x4A, 0x10), 0.6f + 0.4f * glow)
            Heart.NONE -> Triple(Color.rgb(0x6A, 0x40, 0x20), Color.rgb(0x24, 0x14, 0x0A), 0.35f)
        }
        // Light spilling through the bakelite round the glass, kept to the dome.
        domeClip.reset(); domeClip.addCircle(hx, hy, hr * 0.92f, Path.Direction.CW)
        c.save(); c.clipPath(domeClip)
        val a = (0xA0 * bright * (if (heart == Heart.NONE) 0.3f else 1f)).toInt().coerceIn(0, 255)
        halo.shader = RadialGradient(hx, hy, hr * 0.95f,
            intArrayOf(Color.argb(a, Color.red(core), Color.green(core), Color.blue(core)),
                Color.argb(a / 3, Color.red(core), Color.green(core), Color.blue(core)),
                Color.argb(0, Color.red(core), Color.green(core), Color.blue(core))),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(hx, hy, hr * 0.95f, halo)
        c.restore()
        // The heart-shaped glass itself, brightest in the middle like a bulb behind it.
        val hot = Radio.mix(core, Color.WHITE, 0.55f * bright)
        lit.shader = RadialGradient(hx - hr * 0.08f, hy - hr * 0.12f, hr * 0.75f,
            intArrayOf(Radio.mix(deep, hot, bright), Radio.mix(deep, core, bright), deep), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(heartPath, lit)
        // A chrome bead round the glass, and a glint on it.
        engrave.color = Color.argb(0xC0, 0xC8, 0xC4, 0xB8); c.drawPath(heartPath, engrave)
        lit.shader = null; lit.color = Color.argb((0x70 * bright).toInt(), 255, 255, 255)
        c.drawCircle(hx - hr * 0.22f, hy - hr * 0.2f, hr * 0.08f, lit)
        // Thumb-polished shine on the dome.
        Wear.scuffs(c, RectF(hx - hr * 0.8f, hy - hr * 0.8f, hx + hr * 0.8f, hy + hr * 0.8f), 65, 1, d, alpha = 24)
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val onButton = kotlin.math.hypot(e.x - hx, e.y - hy) < hr * 1.5f
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { if (!onButton) return false; pressed = true; invalidate(); return true }
            MotionEvent.ACTION_UP -> {
                if (pressed && onButton) { onHeart?.invoke(); performClick() }
                pressed = false; invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate(); return true }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    init { contentDescription = "Now playing, and the heart button to save the song" }

    private companion object {
        val HEART_RED = Color.rgb(0xF0, 0x40, 0x2C)
        val HEART_AMBER = Color.rgb(0xFF, 0xB0, 0x50)
    }
}
