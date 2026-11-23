package uk.dx.oldtimesradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Plays the set in the background, so the music carries on while the phone does
 * other things. Shows a notification with a Stop button while it plays. Uses
 * Media3 ExoPlayer, which copes with Shoutcast/Icecast MP3 and AAC streams, takes
 * audio focus (pausing for calls and other apps) and stops if headphones are pulled out.
 * It also measures how loud the stream is, for the meter on the broadcast set.
 *
 * A media session tells the rest of the phone what's on: Bluetooth car stereos and
 * headphones (AVRCP), the lock screen and the media controls all get the song, the
 * performer and the station ([Tags]), plus the set's picture, and they update with every
 * new song. A car's next/previous buttons step through the station log.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class StreamService : Service() {

    /** What the set is doing, for the dial on screen. */
    enum class State { OFF, TUNING, PLAYING, ERROR }

    companion object {
        private const val ACTION_PLAY = "uk.dx.oldtimesradio.radio.PLAY"
        private const val ACTION_STOP = "uk.dx.oldtimesradio.radio.STOP"
        private const val ACTION_SOUND = "uk.dx.oldtimesradio.radio.SOUND"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val CHANNEL = "radio"
        private const val NOTE_ID = 7

        // Shared with the UI (main thread only).
        var state = State.OFF; private set
        /** The stream playing (or tuning), so the UI can find it in the log even after reordering. */
        var url: String? = null; private set
        /** What the station says is on ("Artist - Title"), from the stream's ICY metadata. */
        var nowPlaying: String? = null; private set
        var error: String? = null; private set
        private val listeners = mutableSetOf<() -> Unit>()
        fun listen(l: () -> Unit) { listeners += l }
        fun unlisten(l: () -> Unit) { listeners -= l }
        private fun set(s: State, stream: String? = url, err: String? = null) {
            if (stream != url) nowPlaying = null
            state = s; url = stream; error = err
            listeners.toList().forEach { it() }
        }

        private fun title(t: String?) {
            val v = t?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
            if (v == nowPlaying) return
            nowPlaying = v
            listeners.toList().forEach { it() }
        }

        fun play(ctx: Context, s: RadioStation) {
            set(State.TUNING, s.url)
            nowPlaying = null
            ContextCompat.startForegroundService(ctx, Intent(ctx, StreamService::class.java)
                .setAction(ACTION_PLAY).putExtra(EXTRA_URL, s.url).putExtra(EXTRA_NAME, s.name))
        }

        /**
         * The SOUND switch changed: mute or unmute the set. It keeps playing silently while
         * muted, so it comes straight back without re-tuning.
         */
        fun soundChanged(ctx: Context) {
            if (state == State.OFF || state == State.ERROR) return
            ctx.startService(Intent(ctx, StreamService::class.java).setAction(ACTION_SOUND))
        }

        // The meter: loudness (RMS, 0..1) of the decoded audio, by stream position in ms.
        // Written on the playback thread, read on the main thread.
        private const val METER_SIZE = 512
        private val meterLock = Any()
        private val meterMs = LongArray(METER_SIZE)
        private val meterLevel = FloatArray(METER_SIZE)
        private var meterHead = 0
        private var meterCount = 0
        private var current: StreamService? = null

        private fun record(ms: Long, level: Float) = synchronized(meterLock) {
            meterMs[meterHead] = ms; meterLevel[meterHead] = level
            meterHead = (meterHead + 1) % METER_SIZE
            meterCount = minOf(meterCount + 1, METER_SIZE)
        }

        private fun clearMeter() = synchronized(meterLock) { meterCount = 0; meterHead = 0 }

        /**
         * How loud the stream is right now (RMS, 0..1), before the SOUND switch's volume.
         * Audio is decoded ahead of the speaker, so this looks up the level at the position
         * actually playing. Main thread only.
         */
        fun level(): Float {
            val p = current?.player ?: return 0f
            if (!p.isPlaying) return 0f
            val pos = p.currentPosition
            synchronized(meterLock) {
                for (k in 1..meterCount) {
                    val i = (meterHead - k + METER_SIZE) % METER_SIZE
                    if (meterMs[i] <= pos) return meterLevel[i]
                }
            }
            return 0f
        }

        // ---- The sleep timer: counts down on the main thread while the set plays, fades
        // the sound out over the last half-minute, then switches the set off.

        /** When the set goes to sleep (elapsedRealtime ms), or 0 if the timer is off. */
        var sleepAt = 0L; private set
        private val clock = Handler(Looper.getMainLooper())
        private var appCtx: Context? = null

        fun sleepLeftMs(): Long = if (sleepAt == 0L) 0L else (sleepAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

        /** Sets the timer to [minutes] from now (0 turns it off). Main thread only. */
        fun setSleep(ctx: Context, minutes: Int) {
            appCtx = ctx.applicationContext
            clock.removeCallbacks(sleepTick)
            sleepAt = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
            current?.applySound()
            if (sleepAt != 0L) clock.post(sleepTick)
            listeners.toList().forEach { it() }
        }

        private val sleepTick = object : Runnable {
            override fun run() {
                if (sleepAt == 0L) return
                val left = sleepLeftMs()
                current?.applySound()   // the fade
                if (left <= 0L) {
                    sleepAt = 0L
                    appCtx?.let { stop(it) }
                    Caster.stop()   // or the speaker it's casting to
                    listeners.toList().forEach { it() }
                    return
                }
                clock.postDelayed(this, if (left <= Sleep.FADE_MS) 250L else minOf(left - Sleep.FADE_MS, 15_000L))
            }
        }

        /** The set went off some other way: the timer goes back to OFF. */
        private fun clearSleep() { sleepAt = 0L; clock.removeCallbacks(sleepTick) }

        fun stop(ctx: Context) {
            if (state == State.OFF) return
            ctx.startService(Intent(ctx, StreamService::class.java).setAction(ACTION_STOP))
        }
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var name = ""
    private val main = Handler(Looper.getMainLooper())

    /** Measures each decoded buffer on its way to the speaker. */
    private class Meter : TeeAudioProcessor.AudioBufferSink {
        private var rate = 44_100
        private var channels = 2
        private var encoding = C.ENCODING_PCM_16BIT
        private var frames = 0L

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            rate = sampleRateHz.coerceAtLeast(1); channels = channelCount.coerceAtLeast(1); this.encoding = encoding
            frames = 0L
            clearMeter()
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            val b = buffer.duplicate().order(ByteOrder.nativeOrder())
            var sum = 0.0; var samples = 0; var n = 0
            when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    n = b.remaining() / 2
                    var i = 0
                    while (i < n) { val v = b.getShort(b.position() + i * 2) / 32768.0; sum += v * v; samples++; i += 4 }
                }
                C.ENCODING_PCM_FLOAT -> {
                    n = b.remaining() / 4
                    var i = 0
                    while (i < n) { val v = b.getFloat(b.position() + i * 4).toDouble(); sum += v * v; samples++; i += 4 }
                }
                else -> return
            }
            if (samples == 0) return
            record(frames * 1000 / rate, sqrt(sum / samples).toFloat())
            frames += n / channels
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); current = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                name = intent.getStringExtra(EXTRA_NAME) ?: "Radio"
                startInForeground("Tuning in…")
                tune(url)
            }
            ACTION_STOP -> shutDown()
            ACTION_SOUND -> { applySound(); update() }
            else -> shutDown()
        }
        return START_NOT_STICKY
    }

    private fun tune(url: String) {
        val p = player ?: ExoPlayer.Builder(this, metered()).build().also { p ->
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)   // handle audio focus
            p.setHandleAudioBecomingNoisy(true)                              // headphones out: pause
            p.setWakeMode(C.WAKE_MODE_NETWORK)                               // keep streaming with the screen off
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) = update()
                override fun onIsPlayingChanged(playing: Boolean) = update()
                // Shoutcast/Icecast "StreamTitle": the song or programme on now. Read from the
                // raw stream metadata, since the player's own metadata carries our tags.
                override fun onMetadata(m: Metadata) {
                    var t: String? = null
                    for (i in 0 until m.length()) {
                        val b = MediaMetadata.Builder()
                        m.get(i).populateMediaMetadata(b)
                        b.build().title?.toString()?.let { t = it }
                    }
                    val icy = t ?: return
                    main.post { if (player != null) { title(icy); retag(); update() } }
                }
                override fun onPlayerError(e: PlaybackException) {
                    main.post {
                        set(State.ERROR, err = "Couldn't tune in: " + (e.cause?.message ?: e.errorCodeName))
                        shutDown(keepError = true)
                    }
                }
            })
            player = p
            // The media session: Bluetooth, the lock screen and media controls see the set.
            session = MediaSession.Builder(this, SetPlayer(p))
                .setSessionActivity(PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build()
        }
        set(State.TUNING, url)
        p.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(meta(Tags.of(name, null))).build())
        p.prepare()
        p.playWhenReady = true
        applySound()
    }

    /**
     * The tags as the media session publishes them. The media item's metadata takes
     * precedence over what the stream itself says, so cars see exactly these.
     */
    private fun meta(t: Tags): MediaMetadata = MediaMetadata.Builder()
        .setTitle(t.title)
        .setArtist(t.artist)
        .setAlbumTitle(t.album)
        .setDisplayTitle(t.title)
        .setSubtitle(t.artist)
        .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
        .build()

    /** A new song: swap in fresh tags without interrupting the stream. */
    private fun retag() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val m = meta(Tags.of(name, nowPlaying))
        if (m.title == item.mediaMetadata.title && m.artist == item.mediaMetadata.artist) return
        p.replaceMediaItem(p.currentMediaItemIndex, item.buildUpon().setMediaMetadata(m).build())
    }

    /** The set's own picture (its launcher icon), for car displays that show cover art. */
    private val artwork: ByteArray? by lazy {
        runCatching {
            val dr = ContextCompat.getDrawable(this, R.mipmap.ic_launcher) ?: return@runCatching null
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            dr.setBounds(0, 0, 512, 512); dr.draw(Canvas(bmp))
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }.getOrNull()
    }

    /** A car's (or headphones') next/previous: the next or previous station in the log. */
    private fun step(dir: Int) {
        val list = Store.stations(this)
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.url == url }
        val st = list[((if (i < 0) 0 else i + dir) % list.size + list.size) % list.size]
        name = st.name
        startInForeground("Tuning in…")
        tune(st.url)
    }

    /**
     * The player as the media session sees it: next/previous change station (a live
     * stream has no tracks to skip), and stop switches the set off.
     */
    private inner class SetPlayer(p: Player) : ForwardingPlayer(p) {
        private val extra = intArrayOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*extra).build()
        override fun isCommandAvailable(command: Int) = command in extra || super.isCommandAvailable(command)
        override fun seekToNext() = step(1)
        override fun seekToNextMediaItem() = step(1)
        override fun seekToPrevious() = step(-1)
        override fun seekToPreviousMediaItem() = step(-1)
        override fun stop() { main.post { shutDown() } }
    }

    /** ExoPlayer's usual renderers, with the [Meter] tapped into the audio path. */
    private fun metered() = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                    enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(Meter())))
                .build()
    }

    private fun update() {
        val p = player ?: return
        when {
            p.isPlaying -> { set(State.PLAYING); startInForeground(if (!Store.sound(this)) "Muted — SOUND is off" else nowPlaying ?: "Playing") }
            p.playbackState == Player.STATE_BUFFERING -> { set(State.TUNING); startInForeground("Tuning in…") }
            // Paused for a phone call or another app: keep the set "on" so it resumes.
        }
    }

    /** Follows the SOUND switch (full volume when on, silent when off), and the sleep timer's fade. */
    private fun applySound() {
        val fade = if (sleepAt == 0L) 1f else Sleep.fade(sleepLeftMs())
        player?.volume = if (Store.sound(this)) fade else 0f
    }

    private fun shutDown(keepError: Boolean = false) {
        session?.release(); session = null
        player?.release(); player = null
        if (!Caster.connected) clearSleep()   // handed over to a cast speaker: keep counting
        if (!keepError) set(State.OFF, null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (current === this) current = null
        session?.release(); session = null
        player?.release(); player = null
        if (!Caster.connected) clearSleep()   // handed over to a cast speaker: keep counting
        if (state != State.ERROR) set(State.OFF, null)
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "The set while it's playing" })
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Song and performer when the station sends them, else the station and what the set's doing.
        val tags = Tags.of(name, nowPlaying)
        val song = tags.title != name
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(tags.title)
            .setContentText(if (song && Store.sound(this)) "${tags.artist} · $name" else text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stop)
        session?.let { b.setStyle(MediaStyleNotificationHelper.MediaStyle(it).setShowActionsInCompactView(0)) }
        val n: Notification = b.build()
        ServiceCompat.startForeground(this, NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
}
