package uk.dx.oldtimesradio

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/**
 * Casting: plays the set's stations on a Chromecast, Google/Nest speaker or Cast-enabled
 * TV, using Google's Default Media Receiver (no receiver app of our own). Finds devices on
 * the Wi-Fi, starts and ends the session, hands over a station that's playing on the
 * phone, and follows the device's own state for the dial. Main thread only.
 *
 * Its state mirrors [StreamService]'s so the set can show either; [MainActivity] picks
 * whichever is in charge ([connected] means the cast device is).
 */
object Caster {
    var state = StreamService.State.OFF; private set
    /** The stream on the cast device. */
    var url: String? = null; private set
    var error: String? = null; private set

    private var cast: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var appCtx: Context? = null
    private var session: CastSession? = null
    private var title = ""

    private val listeners = mutableSetOf<() -> Unit>()
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    /** False where casting can't work (no Google Play services on the phone). */
    val available get() = cast != null
    val connected get() = session?.isConnected == true
    /** The cast device's name, e.g. "Living Room speaker". */
    val device: String? get() = session?.castDevice?.friendlyName

    fun init(ctx: Context) {
        if (cast != null) return
        val app = ctx.applicationContext
        appCtx = app
        @Suppress("DEPRECATION")
        val c = runCatching { CastContext.getSharedInstance(app) }.getOrNull() ?: return
        cast = c
        router = MediaRouter.getInstance(app)
        selector = MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
        c.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        c.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { attach(it, handOver = false) }
    }

    // ---- Finding and choosing a device ----

    /** Cast devices found so far on the Wi-Fi. */
    fun devices(): List<MediaRouter.RouteInfo> {
        val r = router ?: return emptyList()
        val sel = selector ?: return emptyList()
        return r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(sel) }
    }

    /** Looks for devices while [cb] is registered (the chooser is open). */
    fun startLooking(cb: MediaRouter.Callback) {
        val sel = selector ?: return
        router?.addCallback(sel, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
    }

    fun stopLooking(cb: MediaRouter.Callback) { router?.removeCallback(cb) }

    /** Connects to [route]; the Cast framework then starts the session. */
    fun connect(route: MediaRouter.RouteInfo) { router?.selectRoute(route) }

    /** Ends casting and stops the device playing. */
    fun disconnect() { cast?.sessionManager?.endCurrentSession(true) }

    // ---- Playing ----

    /** Plays [s] on the cast device. */
    fun play(s: RadioStation) {
        val rmc = session?.remoteMediaClient ?: return
        url = s.url; title = s.name; state = StreamService.State.TUNING; error = null
        changed()
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, s.name)
            putString(MediaMetadata.KEY_ARTIST, "Old Times Radio")
        }
        val info = MediaInfo.Builder(s.url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(contentType(s.url))
            .setMetadata(meta)
            .build()
        rmc.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
            .setResultCallback { r ->
                if (!r.status.isSuccess && url == s.url) {
                    state = StreamService.State.ERROR
                    error = "Couldn't play ${s.name} on ${device ?: "the speaker"}"
                    changed()
                }
            }
        mute(!(appCtx?.let { Store.sound(it) } ?: true))
    }

    fun stop() {
        if (!connected) return
        session?.remoteMediaClient?.stop()
        state = StreamService.State.OFF; url = null; error = null
        changed()
    }

    /** The SOUND rocker, on the cast device. */
    fun mute(on: Boolean) { runCatching { session?.isMute = on } }

    /** A best guess at the stream's audio type, for the receiver. */
    private fun contentType(u: String): String {
        val l = u.lowercase()
        return when {
            "aac" in l -> "audio/aac"
            ".ogg" in l || "opus" in l -> "audio/ogg"
            ".m3u8" in l -> "application/x-mpegURL"
            else -> "audio/mpeg"
        }
    }

    // ---- The session and the device's state ----

    private val media = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = readStatus()
    }

    /** Follows the device: playing, buffering, stopped or failed. */
    private fun readStatus() {
        val rmc = session?.remoteMediaClient ?: return
        when (rmc.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> state = StreamService.State.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> state = StreamService.State.OFF
            MediaStatus.PLAYER_STATE_IDLE -> when (rmc.idleReason) {
                MediaStatus.IDLE_REASON_INTERRUPTED -> return   // replaced by the next station
                MediaStatus.IDLE_REASON_ERROR -> {
                    state = StreamService.State.ERROR
                    error = "${device ?: "The speaker"} couldn't play $title"
                }
                else -> state = StreamService.State.OFF
            }
            MediaStatus.PLAYER_STATE_UNKNOWN -> return
            else -> state = StreamService.State.TUNING   // buffering or loading
        }
        rmc.mediaInfo?.contentId?.takeIf { it.isNotEmpty() }?.let { url = it }
        if (state == StreamService.State.OFF) url = null
        changed()
    }

    private fun attach(s: CastSession, handOver: Boolean) {
        session?.remoteMediaClient?.unregisterCallback(media)
        session = s
        s.remoteMediaClient?.registerCallback(media)
        val ctx = appCtx
        // A station playing on the phone moves over to the speaker.
        val local = StreamService.url
        if (handOver && ctx != null && local != null &&
            (StreamService.state == StreamService.State.PLAYING || StreamService.state == StreamService.State.TUNING)) {
            val st = Store.stations(ctx).firstOrNull { it.url == local } ?: RadioStation("Radio", "", local)
            StreamService.stop(ctx)
            play(st)
        } else readStatus()
        changed()
    }

    private fun detach() {
        session?.remoteMediaClient?.unregisterCallback(media)
        session = null
        state = StreamService.State.OFF; url = null
        // The sleep timer was keeping time for the speaker; nothing's playing now.
        appCtx?.let { if (StreamService.state == StreamService.State.OFF && StreamService.sleepAt != 0L) StreamService.setSleep(it, 0) }
        changed()
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) = attach(s, handOver = true)
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = attach(s, handOver = false)
        override fun onSessionEnded(s: CastSession, error: Int) = detach()
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            this@Caster.error = "Couldn't connect to the speaker"
            state = StreamService.State.ERROR
            changed()
            state = StreamService.State.OFF
        }
        override fun onSessionSuspended(s: CastSession, reason: Int) = changed()
        override fun onSessionStarting(s: CastSession) = changed()
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) = detach()
    }
}

/**
 * Tells the Cast framework which receiver to use (Google's Default Media Receiver) and
 * gives it a notification with controls that opens the set. Named in the manifest.
 */
class CastSetup : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(CastMediaOptions.Builder()
            .setNotificationOptions(NotificationOptions.Builder()
                .setTargetActivityClassName(MainActivity::class.java.name)
                .build())
            .build())
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
