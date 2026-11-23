package uk.dx.oldtimesradio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Period radio sounds: a bakelite toggle snap for rocker switches and buttons, and a
 * lighter rotary detent for the station buttons. Respects the SOUND rocker.
 */
object Sfx {
    private var pool: SoundPool? = null
    private var click = 0
    private var detent = 0
    private val loaded = mutableSetOf<Int>()

    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        val app = ctx.applicationContext
        click = p.load(app, R.raw.click, 1)
        detent = p.load(app, R.raw.detent, 1)
        pool = p
    }

    private fun play(ctx: Context, id: Int, vol: Float) {
        if (!Store.sound(ctx)) return
        if (id in loaded) pool?.play(id, vol, vol, 1, 0, 1f)
    }

    fun click(ctx: Context) = play(ctx, click, 1f)
    fun detent(ctx: Context) = play(ctx, detent, 0.8f)
}
