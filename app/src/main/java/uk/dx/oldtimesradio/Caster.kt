package uk.dx.oldtimesradio

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
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
 * Casting: plays the set's stations on another speaker on the Wi-Fi, either
 *  - Google Cast (Chromecast, Google/Nest speakers, Cast TVs), using Google's Default Media
 *    Receiver (no receiver app of our own), or
 *  - DLNA / UPnP media renderers (many smart TVs, Sonos, AV receivers, Kodi…) via [Upnp].
 * Finds devices, connects and disconnects, hands over a station that's playing on the
 * phone, and follows the device's own state for the dial. Main thread only (the DLNA
 * network calls run on a worker thread and report back here).
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

    /** A speaker to choose from: a Cast route or a DLNA renderer. */
    class Speaker(val name: String, val kind: String, internal val route: MediaRouter.RouteInfo?, internal val renderer: Upnp.Renderer?)

    // DLNA: the renderer in use, renderers found, and the worker that talks to them.
    private var renderer: Upnp.Renderer? = null
    private val found = LinkedHashMap<String, Upnp.Renderer>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var looking: (() -> Unit)? = null
    private var routeCb: MediaRouter.Callback? = null
    private var searchRound = 0
    private var dlnaSince = 0L
    private var dlnaMisses = 0

    private val listeners = mutableSetOf<() -> Unit>()
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    /** Google Cast needs Google Play services; DLNA works anywhere. */
    val castAvailable get() = cast != null
    private val castConnected get() = session?.isConnected == true
    val connected get() = renderer != null || castConnected
    /** The speaker's name, e.g. "Living Room speaker". */
    val device: String? get() = renderer?.name ?: session?.castDevice?.friendlyName

    fun init(ctx: Context) {
        if (appCtx != null) return
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

    // ---- Finding and choosing a speaker ----

    /** Speakers found so far: Cast devices, then DLNA renderers. */
    fun speakers(): List<Speaker> {
        val out = ArrayList<Speaker>()
        val r = router; val sel = selector
        if (r != null && sel != null) for (route in r.routes)
            if (!route.isDefault && route.isEnabled && route.matchesSelector(sel)) out += Speaker(route.name, "Google Cast", route, null)
        for (d in found.values) out += Speaker(d.name, "DLNA", null, d)
        return out
    }

    /** Looks for speakers while the chooser is open, calling [onChange] as they come and go. */
    fun startLooking(onChange: () -> Unit) {
        stopLooking()
        looking = onChange
        found.clear()   // only speakers that answer now, not ones switched off since
        val sel = selector
        val r = router
        if (sel != null && r != null) {
            val cb = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = onChange()
                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = onChange()
                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = onChange()
            }
            routeCb = cb
            r.addCallback(sel, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        }
        searchDlna(++searchRound)
    }

    fun stopLooking() {
        looking = null
        searchRound++
        routeCb?.let { router?.removeCallback(it) }
        routeCb = null
    }

    /**
     * An SSDP search for DLNA renderers, repeated while the chooser stays open. Holds a
     * Wi-Fi multicast lock while it listens, since some phones drop multicast otherwise.
     */
    private fun searchDlna(round: Int) {
        val ctx = appCtx ?: return
        Thread {
            val wifi = ctx.getSystemService(WifiManager::class.java)
            val lock = runCatching { wifi?.createMulticastLock("oldtimesradio-dlna")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
            try {
                val seen = HashSet<String>()
                runCatching {
                    Upnp.search { loc ->
                        if (seen.add(loc)) worker.execute {
                            val d = runCatching { Upnp.describe(loc) }.getOrNull() ?: return@execute
                            main.post { if (found.put(loc, d) == null) looking?.invoke() }
                        }
                    }
                }
            } finally { runCatching { lock?.release() } }
            main.postDelayed({ if (round == searchRound && looking != null) searchDlna(round) }, 6_000L)
        }.start()
    }

    /** Connects to [s]: a Cast session starts, or the DLNA renderer takes over at once. */
    fun connect(s: Speaker) {
        s.route?.let { if (renderer != null) dropRenderer(); router?.selectRoute(it); return }
        val d = s.renderer ?: return
        if (castConnected) cast?.sessionManager?.endCurrentSession(true)
        renderer = d
        state = StreamService.State.OFF; url = null; error = null
        dlnaMisses = 0
        moveHere()
        changed()
        poll()
    }

    /** Ends casting and stops the speaker playing. */
    fun disconnect() {
        if (renderer != null) {
            val d = renderer!!
            worker.execute { runCatching { Upnp.stop(d) } }
            dropRenderer()
            return
        }
        cast?.sessionManager?.endCurrentSession(true)
    }

    private fun dropRenderer() {
        renderer = null
        state = StreamService.State.OFF; url = null
        endSleepIfIdle()
        changed()
    }

    /**
     * Follows a DLNA renderer every few seconds: is it playing, still tuning, or stopped
     * (from its own remote, say)? Gives up after several unanswered calls.
     */
    private fun poll() {
        val d = renderer ?: return
        worker.execute {
            val st = runCatching { Upnp.state(d) }.getOrNull()
            main.post {
                if (renderer !== d) return@post
                if (st == null) {
                    if (++dlnaMisses >= 4) {
                        error = "Lost touch with ${d.name}"
                        state = StreamService.State.ERROR
                        changed()
                        dropRenderer()
                        return@post
                    }
                } else {
                    dlnaMisses = 0
                    val before = state to url
                    val tuning = System.currentTimeMillis() - dlnaSince < 15_000L
                    when (st) {
                        "PLAYING" -> if (url != null) state = StreamService.State.PLAYING
                        "TRANSITIONING" -> if (url != null) state = StreamService.State.TUNING
                        // Right after a station's sent, a renderer can say STOPPED for a moment.
                        "STOPPED", "NO_MEDIA_PRESENT", "PAUSED_PLAYBACK" -> if (!tuning || state == StreamService.State.PLAYING) {
                            if (state != StreamService.State.ERROR) { state = StreamService.State.OFF; url = null }
                        }
                    }
                    if (state to url != before) changed()
                }
                main.postDelayed({ poll() }, 3_000L)
            }
        }
    }

    // ---- Playing ----

    /** Plays [s] on the speaker. */
    fun play(s: RadioStation) {
        renderer?.let { d ->
            url = s.url; title = s.name; state = StreamService.State.TUNING; error = null
            dlnaSince = System.currentTimeMillis()
            changed()
            val mute = !(appCtx?.let { Store.sound(it) } ?: true)
            worker.execute {
                val e = runCatching { Upnp.play(d, s.name, s.url); if (mute) Upnp.mute(d, true) }.exceptionOrNull()
                if (e != null) main.post {
                    if (renderer === d && url == s.url) {
                        state = StreamService.State.ERROR
                        error = "${d.name} couldn't play ${s.name}" + (e.message?.let { " ($it)" } ?: "")
                        changed()
                    }
                }
            }
            return
        }
        val rmc = session?.remoteMediaClient ?: return
        url = s.url; title = s.name; state = StreamService.State.TUNING; error = null
        changed()
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, s.name)
            putString(MediaMetadata.KEY_ARTIST, "Old Times Radio")
        }
        val info = MediaInfo.Builder(s.url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(Upnp.mime(s.url))
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
        renderer?.let { d -> worker.execute { runCatching { Upnp.stop(d) } } }
        session?.remoteMediaClient?.stop()
        state = StreamService.State.OFF; url = null; error = null
        changed()
    }

    /** The SOUND rocker, on the cast device. */
    fun mute(on: Boolean) {
        renderer?.let { d -> worker.execute { runCatching { Upnp.mute(d, on) } }; return }
        runCatching { session?.isMute = on }
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
        if (!(handOver && moveHere())) readStatus()
        changed()
    }

    /** A station playing on the phone moves over to the speaker. True if one did. */
    private fun moveHere(): Boolean {
        val ctx = appCtx ?: return false
        val local = StreamService.url ?: return false
        if (StreamService.state != StreamService.State.PLAYING && StreamService.state != StreamService.State.TUNING) return false
        val st = Store.stations(ctx).firstOrNull { it.url == local } ?: RadioStation("Radio", "", local)
        StreamService.stop(ctx)
        play(st)
        return true
    }

    private fun detach() {
        session?.remoteMediaClient?.unregisterCallback(media)
        session = null
        if (renderer != null) return   // moved to a DLNA speaker
        state = StreamService.State.OFF; url = null
        endSleepIfIdle()
        changed()
    }

    /** The sleep timer was keeping time for the speaker; with nothing playing, it goes off. */
    private fun endSleepIfIdle() {
        appCtx?.let { if (StreamService.state == StreamService.State.OFF && StreamService.sleepAt != 0L) StreamService.setSleep(it, 0) }
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) { if (renderer != null) dropRenderer(); attach(s, handOver = true) }
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
