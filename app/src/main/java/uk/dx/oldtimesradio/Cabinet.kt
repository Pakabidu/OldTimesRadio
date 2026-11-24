package uk.dx.oldtimesradio

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** Shared bits for the custom-drawn cabinet parts. */
abstract class CabinetView(ctx: Context) : View(ctx) {
    protected val d = resources.displayMetrics.density

    /**
     * Text unit: 1.3x the original lettering, growing with the phone's font-size
     * setting up to 1.5x, so the cabinet stays readable without overflowing.
     */
    protected val t = d * (1.3f * resources.configuration.fontScale).coerceIn(1.3f, 1.5f)

    /** Draws text with the paint's alignment, shrinking it until it fits [maxW]. */
    protected fun fitText(c: Canvas, text: String, x: Float, y: Float, p: Paint, maxW: Float) {
        val size = p.textSize
        while (p.measureText(text) > maxW && p.textSize > 8 * d) p.textSize *= 0.94f
        c.drawText(text, x, y, p)
        p.textSize = size
    }
    private val anims = mutableListOf<ValueAnimator>()

    protected fun animate(from: Float, to: Float, ms: Long, delay: Long = 0,
                          interp: TimeInterpolator = DecelerateInterpolator(), repeat: Boolean = false,
                          set: (Float) -> Unit) {
        anims += ValueAnimator.ofFloat(from, to).apply {
            duration = ms; startDelay = delay; interpolator = interp
            if (repeat) { repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART }
            addUpdateListener { set(it.animatedValue as Float); invalidate() }
            start()
        }
    }

    protected fun stopAnims() { anims.forEach { it.cancel() }; anims.clear() }

    override fun onDetachedFromWindow() { stopAnims(); super.onDetachedFromWindow() }

    protected fun paint(color: Int = Color.BLACK, block: Paint.() -> Unit = {}) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; block() }
}

/** Shared hardware drawing: etched chrome plates and slotted screws. */
object Hardware {
    private val plateFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mottle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(0x5E, 0x5D, 0x57) }
    private val screwPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x3A, 0x3A, 0x36) }

    private val clip = Path()

    /** Mottled, etched-looking chrome like the radio's control plates, scratched and grubby with use. */
    fun plate(c: Canvas, r: RectF, radius: Float, d: Float, seed: Int = 0) {
        plateFill.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
            intArrayOf(Color.rgb(0xB0, 0xAE, 0xA4), Color.rgb(0xDC, 0xD9, 0xCE), Color.rgb(0x9E, 0x9B, 0x90)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, plateFill)
        mottle.shader = BitmapShader(Radio.mottle, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRoundRect(r, radius, radius, mottle)
        clip.reset(); clip.addRoundRect(r, radius, radius, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        Wear.panel(c, r, seed, d, heavy = true)
        Wear.chips(c, r, seed, 14, d, Color.argb(150, 0x6A, 0x60, 0x50))   // plating worn through to the base metal
        c.restore()
        edge.strokeWidth = 1.2f * d
        c.drawRoundRect(r, radius, radius, edge)
    }

    private val jewelRing = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jewelDome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jewelFacet = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * A faceted jewel pilot lamp in a chrome ring, lit from behind: [colour] is the
     * glass. Used for the small decorative lamps around the set.
     */
    fun jewel(c: Canvas, x: Float, y: Float, r: Float, colour: Int, d: Float) {
        jewelRing.shader = RadialGradient(x - r * 0.4f, y - r * 0.4f, r * 1.9f,
            intArrayOf(Color.rgb(0xF2, 0xF2, 0xEC), Radio.CHROME_DARK, Color.rgb(0x44, 0x44, 0x40)), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r * 1.35f, jewelRing)
        jewelDome.shader = RadialGradient(x - r * 0.25f, y - r * 0.3f, r * 1.2f,
            intArrayOf(Color.WHITE, Radio.mix(colour, Color.WHITE, 0.35f), colour, Radio.mix(colour, Color.BLACK, 0.55f)),
            floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, jewelDome)
        // Cut facets catching the light.
        jewelFacet.strokeWidth = 0.5f * d; jewelFacet.color = Color.argb(70, 255, 255, 255)
        for (k in 0 until 6) {
            val a = k / 6.0 * Math.PI * 2
            c.drawLine(x + r * 0.35f * kotlin.math.cos(a).toFloat(), y + r * 0.35f * kotlin.math.sin(a).toFloat(),
                x + r * 0.95f * kotlin.math.cos(a).toFloat(), y + r * 0.95f * kotlin.math.sin(a).toFloat(), jewelFacet)
        }
    }

    /** Rust bloom around a screw head that's seen damp sheds. */
    private val rust = Paint(Paint.ANTI_ALIAS_FLAG)
    fun rustRing(c: Canvas, x: Float, y: Float, d: Float, r: Float = 2.4f) {
        rust.shader = RadialGradient(x, y, r * 2.2f * d, intArrayOf(Color.argb(0, 0x8A, 0x4A, 0x1A), Color.argb(70, 0x8A, 0x4A, 0x1A),
            Color.argb(0, 0x8A, 0x4A, 0x1A)), floatArrayOf(0.4f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r * 2.2f * d, rust)
    }

    fun screw(c: Canvas, x: Float, y: Float, d: Float, r: Float = 2.4f) {
        rustRing(c, x, y, d, r)
        screwPaint.shader = RadialGradient(x - d, y - d, r * 1.8f * d, Color.rgb(0xF4, 0xF2, 0xEA), Radio.CHROME_DARK, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r * d, screwPaint)
        slot.strokeWidth = 0.8f * d
        c.drawLine(x - r * 0.7f * d, y, x + r * 0.7f * d, y, slot)
    }
}

/** Crackled leather carrying strap on aged copper brackets, with a copper latch at each corner. */
class HandleView(ctx: Context) : CabinetView(ctx) {
    private val strap = paint { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val crack = paint(Color.argb(0x8C, 0x14, 0x0A, 0x06)) { strokeWidth = 0.6f * d }
    private val copper = paint()
    private val verdigris = paint(Color.argb(0x59, 0x46, 0x5A, 0x3C))
    private val rivet = paint(Color.rgb(0x3A, 0x24, 0x14))
    private val path = Path()
    private val cracks = ArrayList<FloatArray>()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (40 * d).toInt())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = kotlin.random.Random(3)
        cracks.clear()
        repeat(28) { cracks += floatArrayOf(r.nextFloat(), r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1) }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = w * 0.32f; val r = w * 0.68f; val base = h - 6 * d
        path.reset(); path.moveTo(l, base); path.cubicTo(l, 3 * d, r, 3 * d, r, base)
        strap.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(0x8A, 0x4E, 0x36), Color.rgb(0x3A, 0x1E, 0x14), Shader.TileMode.CLAMP)
        strap.strokeWidth = 12 * d
        c.drawPath(path, strap)
        for (k in cracks) {   // fine crazing on the strap's top face
            val x = l + (r - l) * k[0]
            val y = 9 * d + kotlin.math.abs(k[0] - 0.5f) * 17 * d
            c.drawLine(x, y + k[1] * 3 * d, x + k[2] * 3 * d, y + 5 * d, crack)
        }
        copper.shader = LinearGradient(0f, base - 9 * d, 0f, h,
            intArrayOf(Color.rgb(0xD9, 0xA8, 0x77), Radio.COPPER, Radio.COPPER_DARK), null, Shader.TileMode.CLAMP)
        for (x in listOf(l, r)) {
            c.drawRoundRect(x - 15 * d, base - 7 * d, x + 15 * d, h, 3 * d, 3 * d, copper)
            c.drawCircle(x + 6 * d, base + 1 * d, 3 * d, verdigris)
            c.drawCircle(x - 10 * d, base - 1 * d, 1.6f * d, rivet)
            c.drawCircle(x + 10 * d, base - 1 * d, 1.6f * d, rivet)
        }
        for (x in listOf(18 * d, w - 18 * d)) {   // corner latches
            c.drawRoundRect(x - 9 * d, base - 4 * d, x + 9 * d, h, 2 * d, 2 * d, copper)
            c.drawRoundRect(x - 3 * d, base - 9 * d, x + 3 * d, base - 2 * d, 1.5f * d, 1.5f * d, copper)
        }
    }
}

/**
 * Black speaker grille: a fine basket weave in a bevelled recess. Behind the cloth the
 * valves glow warm orange while the set plays, and a green "magic eye" sits in a chrome
 * escutcheon at the top, a full green disc pulsing with the music.
 */
class GrilleView(ctx: Context) : CabinetView(ctx), MoodGlow {
    /** How far the valves behind the cloth are lit (0 cold and dark, 1 full). */
    var glow = 0f
        set(v) { if (v != field) { field = v; invalidate() } }
    /** The music's level for the magic eye (0 quiet, 1 loud): it pulses brighter as it rises. */
    var eye = 0f
        set(v) { if (v != field) { field = v; invalidate() } }

    private val recess = paint(Color.rgb(0x05, 0x05, 0x05))
    private val shade = paint()
    private val rim = paint(Color.BLACK) { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val weave = paint()
    private val valveGlow = paint()
    private val ring = paint()
    private val tube = paint(Color.rgb(0x04, 0x06, 0x04))
    private val phosphor = paint()
    private val cap = paint()
    private val outer = RectF(); private val inner = RectF(); private val r = RectF()

    private val eyeR get() = 10 * d
    private val eyeX get() = inner.centerX()
    private val eyeY get() = inner.top + eyeR * 1.5f + 6 * d

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        outer.set(0f, 0f, w.toFloat(), h.toFloat())
        inner.set(4 * d, 4 * d, w - 4 * d, h - 4 * d)
        // One weave cell: two short bars, alternating light/dark, tiled. The gaps between
        // the threads are part see-through, so the valves' glow shows through the cloth.
        val s = (3 * d).toInt().coerceAtLeast(3)
        val cell = android.graphics.Bitmap.createBitmap(s * 2, s * 2, android.graphics.Bitmap.Config.ARGB_8888)
        val cc = Canvas(cell)
        val light = Paint().apply { color = Color.rgb(0x2A, 0x28, 0x26) }
        val dark = Paint().apply { color = Color.rgb(0x0E, 0x0D, 0x0C) }
        cc.drawColor(Color.argb(0x90, 0x16, 0x15, 0x14))
        for (gy in 0..1) for (gx in 0..1) {
            val odd = (gx + gy) % 2 == 0
            val x = gx * s.toFloat(); val y = gy * s.toFloat()
            cc.drawRect(x, y, x + s * 0.9f, y + s * 0.42f, if (odd) light else dark)
            cc.drawRect(x, y + s * 0.5f, x + s * 0.9f, y + s * 0.92f, if (odd) dark else light)
        }
        weave.shader = BitmapShader(cell, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        shade.shader = RadialGradient(w * 0.35f, h * 0.3f, w * 0.9f, Color.argb(0x0D, 255, 255, 255),
            Color.argb(0x59, 0, 0, 0), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawRoundRect(outer, 10 * d, 10 * d, recess)
        // The valves behind the cloth: a warm pool, low in the cabinet.
        val g = glow
        if (g > 0.01f) {
            valveGlow.shader = RadialGradient(inner.centerX(), inner.bottom - inner.height() * 0.32f, maxOf(inner.width(), inner.height()) * 0.62f,
                intArrayOf(Color.argb((0xF0 * g).toInt(), 0xFF, 0xA8, 0x48), Color.argb((0x90 * g).toInt(), 0xE0, 0x6A, 0x1C),
                    Color.argb(0, 0x80, 0x30, 0x08)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(inner, 8 * d, 8 * d, valveGlow)
        }
        c.drawRoundRect(inner, 8 * d, 8 * d, weave)
        // A little of the glow catches the threads themselves.
        if (g > 0.01f) { valveGlow.alpha = (0x40 * g).toInt().coerceIn(0, 255); c.drawRoundRect(inner, 8 * d, 8 * d, valveGlow); valveGlow.alpha = 255 }
        c.drawRoundRect(inner, 8 * d, 8 * d, shade)
        // Years of dust settled into the cloth, and a paler patch where it's been brushed.
        c.save(); c.clipRect(inner)
        Wear.dust(c, inner, 3, (inner.width() * inner.height() / (d * d) / 30).toInt(), d, alpha = 60)
        Wear.scuffs(c, inner, 3, 3, d, alpha = 16, colour = Color.rgb(0xC8, 0xC0, 0xB0))
        c.restore()
        c.drawRoundRect(inner, 8 * d, 8 * d, rim)
        magicEye(c)
    }

    /**
     * The magic eye: a round green phosphor disc seen end-on through a chrome escutcheon,
     * pulsing in brightness with the music.
     */
    private fun magicEye(c: Canvas) {
        val x = eyeX; val y = eyeY; val er = eyeR
        // Chrome escutcheon, worn to brass in places, with a screw either side.
        ring.shader = LinearGradient(x - er * 1.5f, y - er * 1.5f, x + er * 1.5f, y + er * 1.5f,
            intArrayOf(Color.rgb(0xF0, 0xF0, 0xEC), Radio.CHROME_DARK, Color.rgb(0xDA, 0xDA, 0xD4), Color.rgb(0x50, 0x50, 0x4C)), null, Shader.TileMode.CLAMP)
        r.set(x - er * 2.6f, y - er * 1.45f, x + er * 2.6f, y + er * 1.45f)
        c.drawRoundRect(r, er * 1.45f, er * 1.45f, ring)
        c.save(); c.clipRect(r); Wear.chips(c, r, 57, 5, d, Color.argb(160, 0xB8, 0x8A, 0x40)); Wear.scratches(c, r, 58, 4, d, alpha = 50); c.restore()
        Hardware.screw(c, x - er * 2.0f, y, d, 1.7f)
        Hardware.screw(c, x + er * 2.0f, y, d, 1.7f)
        c.drawCircle(x, y, er * 1.08f, tube)
        // A full green phosphor disc that pulses with the music: brighter on loud passages,
        // softer on quiet ones, dark when the set is off.
        val k = pulse()
        val bright = Radio.mix(Color.rgb(0x0C, 0x1E, 0x10), EYE_GREEN, k)
        val deep = Radio.mix(Color.rgb(0x05, 0x10, 0x08), Color.rgb(0x1E, 0x9A, 0x4A), k)
        phosphor.shader = RadialGradient(x, y, er, intArrayOf(Radio.mix(bright, Color.WHITE, 0.35f * k), bright, deep),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, er, phosphor)
        // Its light spilling onto the tube's glass round the disc.
        if (k > 0.02f) {
            cap.shader = RadialGradient(x, y, er * 1.08f, intArrayOf(Color.argb(0, 0x7C, 0xF0, 0x8E), Color.argb(0, 0x7C, 0xF0, 0x8E),
                Color.argb((0x90 * k).toInt(), 0x7C, 0xF0, 0x8E)), floatArrayOf(0f, 0.85f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, er * 1.08f, cap)
        }
        Screen.glass(c, RectF(x - er, y - er, x + er, y + er), 59, d, dust = 6)
    }

    override fun glows(): List<Glow> {
        if (inner.isEmpty) return emptyList()
        val out = ArrayList<Glow>()
        if (glow > 0.01f) {
            val cy = inner.bottom - inner.height() * 0.32f
            out += Glow(RectF(inner.left + inner.width() * 0.15f, cy - inner.height() * 0.3f, inner.right - inner.width() * 0.15f, cy + inner.height() * 0.25f),
                Mood.AMBER, 0.55f * glow)
        }
        out += Glow(RectF(eyeX - eyeR, eyeY - eyeR, eyeX + eyeR, eyeY + eyeR), EYE_GREEN, 0.15f + 0.7f * pulse())
        return out
    }

    /** How bright the eye is: lit with the valves, pulsing with the meter's reading. */
    private fun pulse() = glow * (0.4f + 0.6f * eye.coerceIn(0f, 1f))

    private companion object {
        val EYE_GREEN = Color.rgb(0x7C, 0xF0, 0x8E)
    }
}

/**
 * Narrow chrome plate of big, well-used rocker switches (CLOSE, CAST), each with a jewel
 * lamp, the MOOD LIGHT knob, and the SLEEP timer knob in the second socket. The rockers
 * and the mood knob are real toggles; tapping the sleep knob turns it to the next mark,
 * and its pointer winds back towards OFF as the time runs down, like a clockwork timer.
 */
class TonePlateView(ctx: Context) : CabinetView(ctx), MoodGlow {
    var switches: List<Pair<String, Boolean>> = emptyList()
        set(v) { field = v; invalidate() }
    var onToggle: ((Int, Boolean) -> Unit)? = null
    var mood = false
        set(v) { field = v; invalidate() }
    var onMood: ((Boolean) -> Unit)? = null
    /** Minutes left on the sleep timer (0 = off); drives the knob's pointer and lamp. */
    var sleepMinutes = 0f
        set(v) { if (v != field) { field = v; invalidate() } }
    var onSleep: (() -> Unit)? = null

    private val label = paint(Radio.INK) { typeface = Radio.SERIF; textSize = 9f * t; letterSpacing = 0.12f; textAlign = Paint.Align.CENTER }
    private val tiny = paint(Color.rgb(0x2A, 0x28, 0x24)) { typeface = Radio.SERIF; textSize = 5f * t; textAlign = Paint.Align.CENTER }
    /** The knobs' engraved marks (MOOD LIGHT's OFF/ON, the SLEEP minutes): big enough to read at arm's length. */
    private val marks = paint(Color.rgb(0x22, 0x20, 0x1C)) { typeface = Radio.SERIF; textSize = 7.5f * t; textAlign = Paint.Align.CENTER }
    private val markH get() = marks.textSize
    private val bezel = paint()
    private val slotPaint = paint(Color.rgb(0x0E, 0x0C, 0x0A))
    private val rocker = paint()
    private val thumb = paint()
    private val jewel = paint()
    private val jewelRim = paint(Color.rgb(0x5A, 0x58, 0x52)) { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val knob = paint()
    private val ridge = paint(Color.argb(0xAA, 0, 0, 0)) { strokeWidth = 1f * d }
    private val socket = paint()
    private val r = RectF()

    private val rowH get() = 9 * t + 34 * d
    private fun rowTop(i: Int) = 14 * d + i * rowH
    private val moodY get() = rowTop(switches.size) + 8 * d + 5 * t + 17 * d
    private val moodR get() = 15 * d
    /** The sleep knob sits under the mood knob, clear of its OFF/ON marks and with its own scale round it. */
    /** Baseline of MOOD LIGHT's OFF and ON, below the knob either side. */
    private val moodMarkY get() = moodY + moodR + 3 * d + markH * 0.8f
    private val sleepMarkR get() = moodR * 1.2f + 4 * d + markH * 0.55f
    private val sleepY get() = moodMarkY + 5 * d + sleepMarkR + markH

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h)
        Hardware.plate(c, r, 4 * d, d, seed = 11)
        Hardware.screw(c, w / 2, 7 * d, d)
        for ((i, s) in switches.withIndex()) rockerSwitch(c, w, rowTop(i), s.first, s.second, i)
        moodKnob(c, w / 2, moodY)
        sleepKnob(c, w / 2, sleepY)
    }

    /**
     * The sleep timer: a bakelite knob with OFF, 15, 30, 60 and 90 engraved round it and
     * SLEEP underneath. The pointer shows the minutes left, so it creeps back towards OFF.
     */
    private fun sleepKnob(c: Canvas, x: Float, y: Float) {
        tiny.color = Color.rgb(0x2A, 0x28, 0x24)
        for ((k, m) in SLEEP_MARKS.withIndex()) {
            val a = Math.toRadians(SLEEP_ANGLES[k].toDouble())
            val mx = x + sleepMarkR * kotlin.math.cos(a).toFloat(); val my = y + sleepMarkR * kotlin.math.sin(a).toFloat()
            c.drawText(if (m == 0) "OFF" else "$m", mx, my + markH * 0.36f, marks)
        }
        fitText(c, "SLEEP", x, y + sleepMarkR * 0.64f + markH * 0.5f + 4 * d + 7 * t, label.apply { textSize = 7f * t }, width - 8 * d)
        label.textSize = 9f * t
        socket.shader = LinearGradient(x - moodR * 1.25f, y - moodR * 1.25f, x + moodR * 1.25f, y + moodR * 1.25f,
            Color.rgb(0xE0, 0xDE, 0xD6), Color.rgb(0x6A, 0x68, 0x62), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, moodR * 1.2f, socket)
        knob.shader = RadialGradient(x - moodR * 0.3f, y - moodR * 0.35f, moodR * 1.3f, intArrayOf(Color.rgb(0x5A, 0x40, 0x2E), Color.rgb(0x2A, 0x1C, 0x14), Color.rgb(0x0E, 0x08, 0x05)),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, moodR, knob)
        for (k in 0 until 24) {   // knurled edge
            val a = k / 24.0 * Math.PI * 2
            c.drawLine(x + moodR * 0.82f * kotlin.math.cos(a).toFloat(), y + moodR * 0.82f * kotlin.math.sin(a).toFloat(),
                x + moodR * kotlin.math.cos(a).toFloat(), y + moodR * kotlin.math.sin(a).toFloat(), ridge)
        }
        // A thumb-polished patch on the grip, where it's been turned every night for years.
        Wear.scuffs(c, RectF(x - moodR, y - moodR, x + moodR, y + moodR), 77, 1, d, alpha = 26)
        val a = Math.toRadians(sleepAngle(sleepMinutes).toDouble())
        val px = kotlin.math.cos(a).toFloat(); val py = kotlin.math.sin(a).toFloat()
        ridge.color = Color.rgb(0xE8, 0xDC, 0xC0); ridge.strokeWidth = 2f * d
        c.drawLine(x + px * moodR * 0.25f, y + py * moodR * 0.25f, x + px * moodR * 0.8f, y + py * moodR * 0.8f, ridge)
        ridge.color = Color.argb(0xAA, 0, 0, 0); ridge.strokeWidth = 1f * d
        val on = sleepMinutes > 0f
        jewel.shader = RadialGradient(x, y, 3.5f * d, if (on) intArrayOf(Color.WHITE, Mood.LAMP, Color.rgb(0xB0, 0x50, 0x10))
            else intArrayOf(Color.rgb(0x70, 0x50, 0x38), Color.rgb(0x40, 0x28, 0x18), Color.rgb(0x20, 0x14, 0x0C)), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, 3f * d, jewel)
    }

    /** Pointer angle for the minutes left: in step with the engraved marks, and in between them. */
    private fun sleepAngle(min: Float): Float {
        val m = min.coerceIn(0f, SLEEP_MARKS.last().toFloat())
        for (k in 1 until SLEEP_MARKS.size) if (m <= SLEEP_MARKS[k]) {
            val f = (m - SLEEP_MARKS[k - 1]) / (SLEEP_MARKS[k] - SLEEP_MARKS[k - 1])
            return SLEEP_ANGLES[k - 1] + (SLEEP_ANGLES[k] - SLEEP_ANGLES[k - 1]) * f
        }
        return SLEEP_ANGLES.last()
    }

    private companion object {
        /** Engraved marks (minutes) and their angles, clockwise from lower left round the top to lower right. */
        val SLEEP_MARKS = Sleep.MARKS
        val SLEEP_ANGLES = listOf(140f, 205f, 270f, 335f, 400f)
    }

    /** A big bakelite rocker in a chrome escutcheon, with a jewel lamp above it. */
    private fun rockerSwitch(c: Canvas, w: Float, top: Float, name: String, on: Boolean, i: Int) {
        val cx = w / 2
        val base = top + 9 * t
        fitText(c, name, cx, base, label, w - 10 * d)
        Wear.rub(c, RectF(cx - w * 0.3f, base - 9 * t, cx + w * 0.3f, base), 40 + i, d, Color.rgb(0xC8, 0xC5, 0xBA), 2)
        // Jewel lamp, lit when the switch is on.
        val jy = base + 7 * d
        jewel.shader = RadialGradient(cx - 0.8f * d, jy - 0.8f * d, 4 * d,
            if (on) intArrayOf(Color.WHITE, Mood.LAMP, Color.rgb(0xB0, 0x50, 0x10)) else intArrayOf(Color.rgb(0x70, 0x50, 0x38), Color.rgb(0x40, 0x28, 0x18), Color.rgb(0x20, 0x14, 0x0C)),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, jy, 3.2f * d, jewel); c.drawCircle(cx, jy, 3.2f * d, jewelRim)
        // Chrome escutcheon with two screws.
        val ey = jy + 6 * d
        r.set(cx - 24 * d, ey, cx + 24 * d, ey + 20 * d)
        bezel.shader = LinearGradient(r.left, r.top, r.right, r.bottom, intArrayOf(Color.rgb(0xEC, 0xEA, 0xE2), Color.rgb(0x80, 0x7E, 0x76), Color.rgb(0xC4, 0xC2, 0xBA)),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 4 * d, 4 * d, bezel)
        Hardware.screw(c, r.left + 5 * d, r.centerY(), d, 1.8f)
        Hardware.screw(c, r.right - 5 * d, r.centerY(), d, 1.8f)
        tiny.color = Color.rgb(0x2A, 0x28, 0x24)
        c.drawText("I", cx - 13 * d, r.top - 1.5f * d, tiny); c.drawText("O", cx + 13 * d, r.top - 1.5f * d, tiny)
        // Slot and paddle; the paddle tips towards whichever side is pressed.
        val slot = RectF(cx - 14 * d, r.top + 3 * d, cx + 14 * d, r.bottom - 3 * d)
        c.drawRoundRect(slot, 2 * d, 2 * d, slotPaint)
        val pad = RectF(slot.left + 1.5f * d, slot.top + 1.5f * d, slot.right - 1.5f * d, slot.bottom - 1.5f * d)
        val mid = pad.centerX()
        // Pressed half darker and lower, raised half catching the light.
        rocker.shader = LinearGradient(pad.left, 0f, pad.right, 0f,
            if (on) intArrayOf(Color.rgb(0x0A, 0x07, 0x05), Color.rgb(0x16, 0x10, 0x0C), Color.rgb(0x4A, 0x3A, 0x2E), Color.rgb(0x22, 0x18, 0x12))
            else intArrayOf(Color.rgb(0x22, 0x18, 0x12), Color.rgb(0x4A, 0x3A, 0x2E), Color.rgb(0x16, 0x10, 0x0C), Color.rgb(0x0A, 0x07, 0x05)),
            floatArrayOf(0f, 0.48f, 0.52f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(pad, 2 * d, 2 * d, rocker)
        // Shiny thumb-worn patch on the raised half.
        val tx = if (on) mid + pad.width() * 0.22f else mid - pad.width() * 0.22f
        thumb.shader = RadialGradient(tx, pad.centerY(), pad.height() * 0.6f, Color.argb(60, 0xFF, 0xE8, 0xD0), Color.argb(0, 0xFF, 0xE8, 0xD0), Shader.TileMode.CLAMP)
        c.drawCircle(tx, pad.centerY(), pad.height() * 0.6f, thumb)
        c.save(); c.clipRect(r)
        Wear.scratches(c, r, 70 + i, 6, d, alpha = 50)
        c.restore()
    }

    /** Bakelite knob with a pointer and a jewel; turned to ON, the room light goes down. */
    private fun moodKnob(c: Canvas, x: Float, y: Float) {
        val top = y - moodR - 8 * d - 5 * t
        fitText(c, "MOOD LIGHT", x, top + 4 * t, label.apply { textSize = 7f * t }, width - 8 * d)
        label.textSize = 9f * t
        tiny.color = Color.rgb(0x2A, 0x28, 0x24)
        c.drawText("OFF", x - moodR - 9 * d, moodMarkY, marks)
        c.drawText("ON", x + moodR + 9 * d, moodMarkY, marks)
        socket.shader = LinearGradient(x - moodR * 1.25f, y - moodR * 1.25f, x + moodR * 1.25f, y + moodR * 1.25f,
            Color.rgb(0xE0, 0xDE, 0xD6), Color.rgb(0x6A, 0x68, 0x62), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, moodR * 1.2f, socket)
        knob.shader = RadialGradient(x - moodR * 0.3f, y - moodR * 0.35f, moodR * 1.3f, intArrayOf(Color.rgb(0x5A, 0x40, 0x2E), Color.rgb(0x2A, 0x1C, 0x14), Color.rgb(0x0E, 0x08, 0x05)),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, moodR, knob)
        for (k in 0 until 24) {   // knurled edge
            val a = k / 24.0 * Math.PI * 2
            c.drawLine(x + moodR * 0.82f * kotlin.math.cos(a).toFloat(), y + moodR * 0.82f * kotlin.math.sin(a).toFloat(),
                x + moodR * kotlin.math.cos(a).toFloat(), y + moodR * kotlin.math.sin(a).toFloat(), ridge)
        }
        // Pointer: left for OFF, right for ON.
        val a = Math.toRadians(if (mood) 225.0 + 90 else 225.0)
        val px = kotlin.math.cos(a).toFloat(); val py = kotlin.math.sin(a).toFloat()
        ridge.color = Color.rgb(0xE8, 0xDC, 0xC0); ridge.strokeWidth = 2f * d
        c.drawLine(x + px * moodR * 0.25f, y + py * moodR * 0.25f, x + px * moodR * 0.8f, y + py * moodR * 0.8f, ridge)
        ridge.color = Color.argb(0xAA, 0, 0, 0); ridge.strokeWidth = 1f * d
        jewel.shader = RadialGradient(x, y, 3.5f * d, if (mood) intArrayOf(Color.WHITE, Mood.LAMP, Color.rgb(0xB0, 0x50, 0x10))
            else intArrayOf(Color.rgb(0x70, 0x50, 0x38), Color.rgb(0x40, 0x28, 0x18), Color.rgb(0x20, 0x14, 0x0C)), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, 3f * d, jewel)
    }

    override fun glows(): List<Glow> {
        val out = ArrayList<Glow>()
        val w = width.toFloat()
        for ((i, s) in switches.withIndex()) if (s.second) {
            val jy = rowTop(i) + 9 * t + 7 * d
            out += Glow(RectF(w / 2 - 6 * d, jy - 6 * d, w / 2 + 6 * d, jy + 6 * d), Mood.LAMP, 0.9f)
        }
        if (mood) out += Glow(RectF(w / 2 - 8 * d, moodY - 8 * d, w / 2 + 8 * d, moodY + 8 * d), Mood.LAMP, 1f)
        if (sleepMinutes > 0f) out += Glow(RectF(w / 2 - 8 * d, sleepY - 8 * d, w / 2 + 8 * d, sleepY + 8 * d), Mood.LAMP, 0.8f)
        return out
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP) {
            if (kotlin.math.hypot(e.x - width / 2f, e.y - moodY) < moodR * 1.6f) {
                mood = !mood; onMood?.invoke(mood)
            } else if (kotlin.math.hypot(e.x - width / 2f, e.y - sleepY) < moodR * 1.8f) {
                onSleep?.invoke()
            } else {
                val i = ((e.y - rowTop(0)) / rowH).toInt()
                if (i in switches.indices && e.y >= rowTop(0)) {
                    val on = !switches[i].second
                    switches = switches.toMutableList().also { it[i] = it[i].first to on }
                    onToggle?.invoke(i, on)
                }
            }
            performClick()
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/**
 * The backlit screen of the broadcast set's dial, shared by every lit screen on the set:
 * a black face behind old glass, lit warm amber by bulbs behind it, in a worn chrome
 * bezel. [g] is how far the lamps are up (0 cold, 1 full).
 */
object Screen {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val PALE_GOLD = Color.rgb(0xFF, 0xE0, 0xA0)
    val PALE_CREAM = Color.rgb(0xFF, 0xEE, 0xCC)

    fun bezel(c: Canvas, outer: RectF, radius: Float, seed: Int, d: Float) {
        p.shader = LinearGradient(outer.left, outer.top, outer.right, outer.bottom,
            intArrayOf(Color.rgb(0xF0, 0xF0, 0xEC), Radio.CHROME_DARK, Color.rgb(0xDA, 0xDA, 0xD4), Color.rgb(0x50, 0x50, 0x4C)),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(outer, radius, radius, p)
        p.shader = null
        // Chrome worn through to brass where hands have gripped it.
        c.save(); c.clipRect(outer)
        Wear.chips(c, outer, seed, 14, d, Color.argb(170, 0xB8, 0x8A, 0x40))
        Wear.scratches(c, outer, seed + 1, 10, d, alpha = 60)
        c.restore()
    }

    private val m = android.graphics.Matrix()

    /**
     * The lit face, exactly as on the broadcast set: one bulb low in the middle, its pool
     * scaled to the screen in the broadcast dial's proportions, so the brightness and
     * vignette match on every screen whatever its shape. Call inside a clip to the screen.
     */
    fun face(c: Canvas, r: RectF, g: Float) {
        val h = r.height()
        m.setScale(r.width() / h / BROADCAST_ASPECT, 1f, r.centerX(), r.bottom)
        p.shader = RadialGradient(r.centerX(), r.bottom, h * 1.4f,
            intArrayOf(Radio.mix(Color.rgb(0x1A, 0x14, 0x0E), Color.rgb(0xB0, 0x78, 0x30), g),
                Radio.mix(Color.rgb(0x10, 0x0D, 0x0A), Color.rgb(0x5A, 0x38, 0x14), g),
                Radio.mix(Color.rgb(0x08, 0x08, 0x08), Color.rgb(0x22, 0x14, 0x08), g)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        c.drawRect(r, p)
        // The bulb itself, glowing through the glass.
        p.shader = RadialGradient(r.centerX(), r.bottom, h * 0.9f,
            Color.argb((150 * g).toInt(), 0xFF, 0xD8, 0x90), Color.argb(0, 0xFF, 0xC0, 0x60), Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(m) }
        c.drawRect(r, p)
        p.shader = null
    }
    private const val BROADCAST_ASPECT = 2.25f

    /** Gold scales brightening with the lamp, to a pale lit gold at full. */
    fun gold(g: Float) = if (g < 0.6f) Radio.mix(Color.rgb(0x6A, 0x54, 0x30), Radio.GOLD, g / 0.6f)
        else Radio.mix(Radio.GOLD, PALE_GOLD, (g - 0.6f) / 0.4f)

    fun cream(g: Float) = if (g < 0.6f) Radio.mix(Color.rgb(0x6A, 0x5C, 0x44), Radio.CREAM, g / 0.6f)
        else Radio.mix(Radio.CREAM, PALE_CREAM, (g - 0.6f) / 0.4f)

    /** Old glass over the face (still inside the clip): dust and a scratch or two, as on the broadcast set. */
    fun glass(c: Canvas, r: RectF, seed: Int, d: Float, dust: Int = 60) {
        Wear.dust(c, r, seed, dust, d, alpha = 60)
        Wear.scratches(c, r, seed + 1, 2, d, alpha = 40)
    }
}


/**
 * A nameplate lit from behind like the broadcast screen: pale gold lettering on a
 * backlit amber strip under old glass, in a worn chrome bezel.
 */
class LitNameplate(ctx: Context, text: String) : CabinetView(ctx), MoodGlow {
    var text = text
        set(v) { if (v != field) { field = v; invalidate() } }
    private val outer = RectF()
    private val inner = RectF()
    private val clip = Path()
    private val ink = paint(Screen.PALE_GOLD) { typeface = Radio.SERIF; textSize = 10 * t; letterSpacing = 0.3f; textAlign = Paint.Align.CENTER }
    private val rim get() = 5 * d
    /** How far the lamp behind the strip is up (0 cold, 1 full). */
    var glow = 1f
        set(v) { if (v != field) { field = v; invalidate() } }

    override fun glows(): List<Glow> = if (inner.isEmpty) emptyList() else
        listOf(Glow(RectF(inner), Mood.AMBER, 0.4f + 0.6f * glow, screen = true, radius = inner.height() / 2))

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (2 * rim + 10 * t + 12 * d).toInt())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        outer.set(0f, 0f, w.toFloat(), h.toFloat())
        inner.set(rim, rim, w - rim, h - rim)
        clip.reset(); clip.addRoundRect(inner, inner.height() / 2, inner.height() / 2, Path.Direction.CW)
    }

    override fun onDraw(c: Canvas) {
        Screen.bezel(c, outer, outer.height() / 2, 51, d)
        c.save(); c.clipPath(clip)
        Screen.face(c, inner, glow)
        ink.color = Screen.gold(glow)
        fitText(c, text.uppercase(), inner.centerX(), inner.centerY() + ink.textSize * 0.36f, ink, inner.width() - inner.height())
        Screen.glass(c, inner, 53, d, dust = 25)
        c.restore()
    }
}
