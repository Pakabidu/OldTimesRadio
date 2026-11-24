package uk.dx.oldtimesradio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Single screen, built in code so there's no layout XML to maintain: a 1940s portable
 * wireless set. Handle, speaker grille and tone plate, the broadcast set with its meter
 * and station buttons, the now-playing window, and an open lid holding the station log.
 */
class MainActivity : Activity() {

    private lateinit var radioSet: BroadcastSetView
    private lateinit var grille: GrilleView
    private lateinit var nowPlaying: NowPlayingView
    private lateinit var tonePlate: TonePlateView
    private lateinit var moodLight: MoodLightView
    private lateinit var contentRoot: View
    private lateinit var log: LinearLayout
    private lateinit var favBox: LinearLayout
    /** The song on now (from the station's titles), for the heart button; null if none. */
    private var song: FavouriteSong? = null
    private var exporting: AlertDialog? = null
    private lateinit var updateBtn: Button
    private var stations: List<RadioStation> = emptyList()
    private var downloadReceiver: android.content.BroadcastReceiver? = null
    private var pendingInstall = -1L   // build waiting for the user to allow installs
    /** Winds the sleep knob's pointer back as the minutes run down, while the timer is on. */
    private val sleepClock = object : Runnable {
        override fun run() {
            showSleep()
            if (StreamService.sleepAt != 0L) tonePlate.postDelayed(this, 5_000L)
        }
    }

    /** Keeps the set in step with the phone or the cast speaker, and reports a failed tune-in. */
    private val streamListener: () -> Unit = {
        showState()
        if (playState() == StreamService.State.ERROR) playError()?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
        // A cast speaker that wouldn't connect.
        if (!Caster.connected && Caster.state == StreamService.State.ERROR) Caster.error?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
    }

    // Whoever's playing: the cast speaker when one is connected, otherwise the phone.
    private fun playState() = if (Caster.connected) Caster.state else StreamService.state
    private fun playUrl() = if (Caster.connected) Caster.url else StreamService.url
    private fun playError() = if (Caster.connected) Caster.error else StreamService.error
    private fun play(st: RadioStation) { if (Caster.connected) Caster.play(st) else StreamService.play(this, st) }
    private fun stopPlaying() { if (Caster.connected) Caster.stop() else StreamService.stop(this) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        Sfx.init(this)
        Caster.init(this)
        Mood.on = Store.moodLight(this)
        stations = Store.stations(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        buildFront(root)

        // The open lid: the station log and the set's paperwork, on a black instruction panel.
        val lid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(24), dp(26), dp(26))
            background = Radio.LidDrawable(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        root.addView(lid)
        lid.addView(LitNameplate(this, "Station Log").apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) }
        })
        lid.addView(TextView(this).apply {
            textSize = 15f; typeface = Typeface.SERIF; setTextColor(Radio.CREAM); alpha = 0.9f; setPadding(0, dp(2), 0, dp(8))
            text = "Tap a station to play it. The first five sit on the set's buttons. " +
                "Long-press a station (or its button) to change, move or remove it. " +
                "Tap the SLEEP knob to have the set fade out and switch itself off after 15, 30, 60 or 90 minutes. " +
                "Flip CAST to play on a Chromecast, Google/Nest speaker, or a DLNA TV, Sonos or receiver on your Wi-Fi."
        })
        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lid.addView(log)
        lid.addView(radioButton("Add a station") { editStation(-1) })

        // Favourite songs: saved with the heart, taken to Spotify or YouTube.
        lid.addView(LitNameplate(this, "Favourite Songs").apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(22), 0, dp(8)) }
        })
        lid.addView(TextView(this).apply {
            textSize = 15f; typeface = Typeface.SERIF; setTextColor(Radio.CREAM); alpha = 0.9f; setPadding(0, dp(2), 0, dp(8))
            text = "Press the heart beside the now-playing window to save the song that's on (when the station " +
                "sends song titles). Tap a saved song to find it on YouTube or Spotify."
        })
        favBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lid.addView(favBox)
        lid.addView(radioButton("Export to Spotify") { exportSpotify() }.apply {
            setOnLongClickListener { askSpotifyClientId(); true }
        })
        lid.addView(radioButton("Export to YouTube") { exportYouTube() })
        lid.addView(radioButton("Share the list") { shareFavourites() })
        renderFavourites()

        lid.addView(section("The set"))
        lid.addView(TextView(this).apply {
            textSize = 15f; typeface = Typeface.SERIF; setTextColor(Radio.CREAM); alpha = 0.9f; setPadding(0, dp(2), 0, dp(6))
            text = "Plays internet radio streams (Shoutcast, Icecast and similar, MP3 or AAC). It keeps playing " +
                "with the screen off; stop it from the notification or by pressing the lit button again. " +
                "The meter's needle swings with the station's loudness. SOUND off mutes the set without losing the station."
        })
        updateBtn = radioButton("Check for updates") { checkForUpdate() }
        lid.addView(updateBtn)
        lid.addView(TextView(this).apply {
            textSize = 13f; typeface = Radio.MONO; setTextColor(Radio.BRASS); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            text = "OLD TIMES RADIO ${Updater.currentName(this@MainActivity)} · BUILD ${Updater.currentBuild(this@MainActivity)}"
        })

        val scroll = ScrollView(this).apply {
            background = Radio.background(this@MainActivity)
            isFillViewport = true
            addView(root)
        }
        // The mood light sits over everything, fixed to the screen like the room's lighting.
        moodLight = MoodLightView(this, scroll)
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> moodLight.invalidate() }
        scroll.viewTreeObserver.addOnGlobalLayoutListener { moodLight.invalidate() }
        setContentView(FrameLayout(this).apply {
            addView(scroll)
            addView(moodLight, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
        contentRoot = root
        if (Mood.on) Mood.styleText(root, resources.displayMetrics.density)

        refreshStations()
        handleCallback(intent)
        StreamService.listen(streamListener)
        Caster.listen(streamListener)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC   // volume keys turn the set up and down

        // Offer to install an update as soon as its download finishes.
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                val id = i.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == Store.updateDownloadId(this@MainActivity)) onUpdateDownloaded()
            }
        }
        downloadReceiver = receiver
        androidx.core.content.ContextCompat.registerReceiver(this, receiver,
            android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }

    /** The case front: handle, then grille and tone plate, the broadcast set, and the now-playing window. */
    private fun buildFront(root: LinearLayout) {
        root.addView(HandleView(this))
        val front = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(0x40, 0, 0, 0)); cornerRadius = dp(16).toFloat(); setStroke(dp(3), Color.rgb(0x05, 0x04, 0x04))
            }
        }
        val upper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(256))
        }
        grille = GrilleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.6f)
        }
        upper.addView(grille)
        tonePlate = TonePlateView(this).apply {
            switches = toneSwitches()
            onToggle = { i, on -> if (i == 0) setSound(on) else castSwitch() }
            mood = Mood.on
            onMood = { on -> setMood(on) }
            onSleep = { turnSleepKnob() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(10) }
        }
        upper.addView(tonePlate)
        front.addView(upper)
        radioSet = BroadcastSetView(this).apply {
            onPress = { i -> pressStation(i) }
            // The valves behind the grille and the magic eye follow the set.
            onLight = { valves, level ->
                // With the mood light on, the room's halos follow the lamps warming and dimming.
                if (Mood.on && ::moodLight.isInitialized && kotlin.math.abs(valves - grille.glow) > 0.02f) moodLight.invalidate()
                grille.glow = valves; grille.eye = level
            }
            onLongPress = { i -> if (i < this@MainActivity.stations.size) stationMenu(i) else editStation(-1) }
            muted = !Store.sound(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250)).apply { topMargin = dp(12) }
        }
        front.addView(radioSet)
        nowPlaying = NowPlayingView(this).apply {
            onHeart = { pressHeart() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        front.addView(nowPlaying)
        root.addView(front)
    }

    // ---- Playing ----

    /** Where the stream that's playing sits in the log, or -1. */
    private fun playingIndex(): Int =
        if (playState() == StreamService.State.OFF) -1 else stations.indexOfFirst { it.url == playUrl() }

    private fun isOn(i: Int) = i >= 0 && playingIndex() == i &&
        (playState() == StreamService.State.PLAYING || playState() == StreamService.State.TUNING)

    /** Follows the phone or the cast speaker on the set, the now-playing window and the log. */
    private fun showState() {
        val i = playingIndex()
        val state = playState()
        radioSet.show(state, i)
        tonePlate.switches = toneSwitches()
        val st = stations.getOrNull(i)
        nowPlaying.glow = when (state) {
            StreamService.State.PLAYING -> 1f; StreamService.State.TUNING -> 0.6f; else -> 0.3f
        }
        val speaker = Caster.device?.takeIf { Caster.connected }
        when (state) {
            StreamService.State.OFF -> {
                nowPlaying.heading = if (speaker != null) "CASTING TO ${speaker.uppercase()}" else "THE SET IS OFF"
                nowPlaying.text = "Press a station button"
            }
            StreamService.State.TUNING -> { nowPlaying.heading = "TUNING IN"; nowPlaying.text = st?.name ?: "…" }
            StreamService.State.ERROR -> { nowPlaying.heading = "NO SIGNAL"; nowPlaying.text = st?.name ?: "Try another station" }
            StreamService.State.PLAYING -> if (speaker != null) {
                nowPlaying.heading = "ON ${speaker.uppercase()}"
                nowPlaying.text = st?.name ?: ""
            } else {
                val title = StreamService.nowPlaying
                nowPlaying.heading = if (title != null) (st?.name ?: "NOW PLAYING").uppercase() else "ON THE AIR"
                nowPlaying.text = title ?: st?.name ?: ""
            }
        }
        // The heart: lit for a saved song, ready for a new one, dull when there's no title.
        song = if (speaker == null && state == StreamService.State.PLAYING)
            Favourites.fromStream(st?.name ?: "Radio", StreamService.nowPlaying, System.currentTimeMillis()) else null
        nowPlaying.heart = song?.let { if (Favourites.contains(Store.favourites(this), it)) NowPlayingView.Heart.SAVED
            else NowPlayingView.Heart.SONG } ?: NowPlayingView.Heart.NONE
        renderLog()
        showSleep()
    }

    /** The sleep knob follows the timer; its clock runs only while the timer is on. */
    private fun showSleep() {
        tonePlate.sleepMinutes = StreamService.sleepLeftMs() / 60_000f
        tonePlate.removeCallbacks(sleepClock)
        if (StreamService.sleepAt != 0L) tonePlate.postDelayed(sleepClock, 5_000L)
    }

    /** A tap on the SLEEP knob turns it on to the next mark (15, 30, 60, 90 minutes, then OFF). */
    private fun turnSleepKnob() {
        val on = playState() == StreamService.State.PLAYING || playState() == StreamService.State.TUNING
        if (!on && StreamService.sleepAt == 0L) {
            Toast.makeText(this, "Switch a station on first, then set the sleep timer.", Toast.LENGTH_SHORT).show()
            return
        }
        Sfx.detent(this)
        val minutes = Sleep.next(StreamService.sleepLeftMs() / 60_000f)
        StreamService.setSleep(this, minutes)
        Toast.makeText(this, if (minutes == 0) "Sleep timer off"
            else "The set will fade out and switch off in $minutes minutes", Toast.LENGTH_SHORT).show()
    }

    /** A station: play it, or switch off if it's the one playing. An empty button offers to add one. */
    private fun pressStation(i: Int) {
        Sfx.detent(this)
        if (i >= stations.size) { editStation(-1); return }
        if (isOn(i)) stopPlaying() else play(stations[i])
    }

    private fun toneSwitches() = listOf("SOUND" to Store.sound(this), "CAST" to Caster.connected)

    private fun setSound(on: Boolean) {
        Store.saveSound(this, on)
        StreamService.soundChanged(this)
        Caster.mute(!on)
        radioSet.muted = !on
        Sfx.click(this)
    }

    /**
     * The mood light: the room goes dark around a table lamp and the dials, meters, lamps
     * and button lettering glow warm orange. Fades in place; no rebuild.
     */
    private fun setMood(on: Boolean) {
        Store.saveMoodLight(this, on)
        Mood.on = on
        tonePlate.mood = on
        Mood.styleText(contentRoot, resources.displayMetrics.density)
        Mood.invalidateAll(contentRoot)
        moodLight.set(on)
        Sfx.detent(this)
    }

    // ---- Casting ----

    /**
     * The CAST rocker: not casting, it opens the chooser of speakers and TVs on the Wi-Fi;
     * casting, it offers to bring the sound back to the phone. The rocker itself only ever
     * shows whether the set is casting.
     */
    private fun castSwitch() {
        Sfx.click(this)
        tonePlate.switches = toneSwitches()
        if (Caster.connected) AlertDialog.Builder(this)
            .setTitle("Casting to ${Caster.device ?: "a speaker"}")
            .setMessage("Stop casting? The set goes quiet on the speaker; press a station to play it on the phone.")
            .setPositiveButton("Stop casting") { _, _ -> Caster.disconnect() }
            .setNegativeButton("Keep casting", null)
            .show()
        else chooseSpeaker()
    }

    /**
     * Lists the speakers and TVs on the Wi-Fi as they're found, Google Cast and DLNA
     * alike; picking one connects.
     */
    private fun chooseSpeaker() {
        val found = ArrayList<Caster.Speaker>()
        val names = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        val status = TextView(this).apply { textSize = 15f; setPadding(dp(24), dp(4), dp(24), dp(8)) }
        val list = android.widget.ListView(this).apply { adapter = names }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(status); addView(list) }
        fun refresh() {
            found.clear(); found += Caster.speakers()
            names.clear(); names.addAll(found.map { "${it.name}   ·   ${it.kind}" })
            status.text = if (found.isEmpty()) "Looking for speakers and TVs on your Wi-Fi…\n\n" +
                "Make sure the phone is on the same Wi-Fi as your Chromecast, Google/Nest speaker, " +
                "or DLNA TV, Sonos, receiver or other media renderer." +
                (if (!Caster.castAvailable) "\n\n(Google Cast needs Google Play services; DLNA speakers will still show up.)" else "")
                else "Choose where to play:"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Cast to a speaker or TV")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { Caster.stopLooking() }
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            found.getOrNull(pos)?.let {
                Caster.connect(it)
                Toast.makeText(this, "Connecting to ${it.name}…", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        refresh()
        Caster.startLooking { refresh() }
        dialog.show()
    }

    // ---- The station log ----

    private fun refreshStations() {
        radioSet.stations = stations
        showState()
    }

    private fun save(list: List<RadioStation>) {
        stations = list
        Store.saveStations(this, list)
        refreshStations()
    }

    /** Ruled cream-on-black table, like the station list printed in a set's lid. */
    private fun renderLog() {
        log.removeAllViews()
        val rule = Color.argb(0xB0, 0xF0, 0xE4, 0xC4)
        fun line() = View(this).apply {
            setBackgroundColor(rule)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        }
        fun row(a: String, b: String, header: Boolean, lit: Boolean = false) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(if (header) 4 else 12), dp(10), dp(if (header) 4 else 12))
            if (header) setBackgroundColor(Radio.CREAM)
            addView(TextView(context).apply {
                text = a; textSize = if (header) 13f else 17f
                typeface = if (header) Radio.SERIF else Typeface.SERIF
                letterSpacing = if (header) 0.3f else 0f
                setTextColor(when { header -> Radio.INK; lit -> Mood.LAMP; else -> Radio.CREAM })
                if (lit) setShadowLayer(8f * resources.displayMetrics.density, 0f, 0f, Mood.AMBER)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = b; textSize = 13f; typeface = Radio.SERIF; letterSpacing = 0.1f; gravity = Gravity.END
                setTextColor(if (header) Radio.INK else Radio.GOLD)
                layoutParams = LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), rule) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(6)) }
        }
        box.addView(row("STATION", "BUTTON", header = true))
        val playing = playingIndex()
        for ((i, s) in stations.withIndex()) {
            box.addView(line())
            val lit = i == playing
            box.addView(row((if (lit) "▶  " else "▸  ") + s.name,
                if (i < Stations.PRESETS) "№ ${i + 1}" else "", header = false, lit = lit).apply {
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(Color.argb(0x40, 0xF0, 0xE4, 0xC4)))
                }
                isClickable = true
                setOnClickListener { pressStation(i) }
                setOnLongClickListener { stationMenu(i); true }
            })
        }
        if (stations.isEmpty()) {
            box.addView(line())
            box.addView(row("No stations yet — add one below", "", header = false))
        }
        log.addView(box)
    }

    /** Change, move or remove a station. */
    private fun stationMenu(i: Int) {
        val s = stations.getOrNull(i) ?: return
        Sfx.click(this)
        val items = buildList<Pair<String, () -> Unit>> {
            add("Change…" to { editStation(i) })
            if (i > 0) add("Move to the first button" to { save(Stations.toTop(stations, i)) })
            if (i > 0) add("Move up one" to { save(Stations.up(stations, i)) })
            add("Remove" to { confirmRemove(i) })
        }
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, k -> items[k].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemove(i: Int) {
        val s = stations.getOrNull(i) ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove ${s.name}?")
            .setMessage("It comes off the station log" + if (i < Stations.PRESETS) " and its button." else ".")
            .setPositiveButton("Remove") { _, _ ->
                if (isOn(i)) stopPlaying()
                save(stations.filterIndexed { k, _ -> k != i })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks for a station's name, button label and stream address; [i] -1 adds a new one. */
    private fun editStation(i: Int, prefill: RadioStation? = null) {
        val old = prefill ?: stations.getOrNull(i)
        fun field(hint: String, value: String?, type: Int) = EditText(this).apply {
            this.hint = hint; inputType = type; setText(value ?: ""); textSize = 18f
        }
        val nameField = field("Name, e.g. Radio Swiss Jazz", old?.name,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val labelField = field("Button label (optional, short)", old?.label,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val urlField = field("http://… stream address", old?.url,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameField); addView(labelField); addView(urlField)
        }
        AlertDialog.Builder(this)
            .setTitle(if (i < 0) "Add a station" else "Change station")
            .setMessage("Enter the stream address of an internet radio station (Shoutcast, Icecast or similar).")
            .setView(box)
            .setPositiveButton("Save & play") { _, _ ->
                val url = urlField.text.toString().trim()
                val name = nameField.text.toString().trim().ifEmpty { "My station" }
                val st = RadioStation(name, labelField.text.toString().trim().uppercase(), url)
                if (!Stations.valid(url)) {
                    Toast.makeText(this, "That doesn't look like a stream address (it should start http:// or https://).",
                        Toast.LENGTH_LONG).show()
                    editStation(i, st)
                    return@setPositiveButton
                }
                save(if (i in stations.indices) stations.toMutableList().also { it[i] = st } else stations + st)
                play(st)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Styled widgets ----

    /** Bakelite push-button. */
    private fun radioButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        typeface = Radio.SERIF
        letterSpacing = 0.12f
        setTextColor(Radio.CREAM)
        background = Radio.buttonBg(this@MainActivity)
        stateListAnimator = null
        textSize = 17f
        minHeight = dp(60)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        setOnClickListener { Sfx.click(this@MainActivity); onClick() }
    }

    private fun section(t: String) = TextView(this).apply {
        textSize = 13.5f; letterSpacing = 0.22f; gravity = Gravity.CENTER
        setPadding(dp(8), dp(3), dp(8), dp(3))
        text = t.uppercase(); typeface = Radio.SERIF
        setTextColor(Radio.INK)
        background = GradientDrawable().apply { setColor(Radio.CREAM); cornerRadius = dp(1).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(20), 0, dp(8)) }
    }

    // ---- Favourite songs ----

    /** The heart button: saves the song that's on, or takes it off the list again. */
    private fun pressHeart() {
        val f = song ?: run {
            Toast.makeText(this, if (Caster.connected) "Song titles aren't available while casting."
                else "No song title from the station right now. The heart works when the station sends what's playing.",
                Toast.LENGTH_LONG).show()
            return
        }
        Sfx.click(this)
        val (list, added) = Favourites.toggle(Store.favourites(this), f)
        Store.saveFavourites(this, list)
        Toast.makeText(this, if (added) "♥ Saved: ${Favourites.line(f)}" else "Taken off your favourites", Toast.LENGTH_SHORT).show()
        renderFavourites()
        showState()
    }

    /** The saved songs, ruled like the station log, newest first. */
    private fun renderFavourites() {
        favBox.removeAllViews()
        val favs = Store.favourites(this)
        val rule = Color.argb(0xB0, 0xF0, 0xE4, 0xC4)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), rule) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(6)) }
        }
        box.addView(TextView(this).apply {
            text = "SONG  ·  ${favs.size} SAVED"; textSize = 13f; typeface = Radio.SERIF; letterSpacing = 0.3f
            setTextColor(Radio.INK); setBackgroundColor(Radio.CREAM); setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        if (favs.isEmpty()) box.addView(TextView(this).apply {
            text = "No songs saved yet"; textSize = 17f; typeface = Typeface.SERIF; setTextColor(Radio.CREAM); alpha = 0.8f
            setPadding(dp(10), dp(12), dp(10), dp(12))
        })
        val fmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
        for (f in favs) {
            box.addView(View(this).apply {
                setBackgroundColor(rule)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(TextView(context).apply {
                    text = "♥  " + Favourites.line(f).replace(" - ", " — ")
                    textSize = 17f; typeface = Typeface.SERIF; setTextColor(Radio.CREAM)
                })
                addView(TextView(context).apply {
                    text = f.station + (if (f.savedAt > 0) " · " + fmt.format(java.util.Date(f.savedAt)) else "")
                    textSize = 13f; typeface = Radio.SERIF; letterSpacing = 0.05f; setTextColor(Radio.GOLD)
                    setPadding(dp(26), dp(2), 0, 0)
                })
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(Color.argb(0x40, 0xF0, 0xE4, 0xC4)))
                }
                isClickable = true
                setOnClickListener { favouriteMenu(f) }
            })
        }
        favBox.addView(box)
    }

    private fun favouriteMenu(f: FavouriteSong) {
        Sfx.detent(this)
        val items = buildList<Pair<String, () -> Unit>> {
            add("Find on YouTube" to { open(Favourites.youtubeSearch(f)) })
            add("Find on Spotify" to { open(Favourites.spotifySearch(f)) })
            add("Remove" to {
                Store.saveFavourites(this@MainActivity, Store.favourites(this@MainActivity).filterNot { Favourites.key(it) == Favourites.key(f) })
                renderFavourites(); showState()
            })
        }
        AlertDialog.Builder(this)
            .setTitle(Favourites.line(f))
            .setItems(items.map { it.first }.toTypedArray()) { _, k -> items[k].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "Nothing on this phone can open that link.", Toast.LENGTH_SHORT).show() }
    }

    /** The list as text, to any app: notes, email, or a playlist-transfer service. */
    private fun shareFavourites() {
        val favs = Store.favourites(this)
        if (favs.isEmpty()) { Toast.makeText(this, "No songs saved yet.", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, Favourites.PLAYLIST)
            .putExtra(Intent.EXTRA_TEXT, Favourites.text(favs)), "Share your favourite songs"))
    }

    // ---- Export to Spotify: the user's own Spotify app (Client ID), signed in with PKCE ----

    private fun exportSpotify() {
        if (Store.favourites(this).isEmpty()) { Toast.makeText(this, "No songs saved yet.", Toast.LENGTH_SHORT).show(); return }
        val id = Store.spotifyClientId(this) ?: run { askSpotifyClientId(); return }
        val verifier = Favourites.verifier()
        val state = Favourites.verifier().take(16)
        Store.saveSpotifyPending(this, verifier to state)
        open(Favourites.spotifyAuthUrl(id, Favourites.challenge(verifier), state))
    }

    /**
     * Spotify lets an app make playlists only once it's registered, so the set uses the
     * user's own (free) Spotify developer app: this asks for its Client ID, with the steps.
     */
    private fun askSpotifyClientId() {
        val field = EditText(this).apply {
            hint = "Client ID (32 letters and digits)"; textSize = 18f
            setText(Store.spotifyClientId(this@MainActivity) ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        AlertDialog.Builder(this)
            .setTitle("Connect Spotify (once)")
            .setMessage("To make playlists, Spotify needs you to register the set as your own app. It's free and takes a few minutes:\n\n" +
                "1. On developer.spotify.com, log in and open the Dashboard.\n" +
                "2. Create app: any name. Redirect URI: ${Favourites.SPOTIFY_REDIRECT}\n" +
                "3. Tick \"Web API\", agree, and save.\n" +
                "4. Open the app's settings, copy the Client ID and paste it below.\n\n" +
                "(Long-press Export to Spotify to change it later.)")
            .setView(box)
            .setPositiveButton("Save & export") { _, _ ->
                val id = field.text.toString().trim()
                if (!Favourites.validClientId(id)) {
                    Toast.makeText(this, "That doesn't look like a Spotify Client ID.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Store.saveSpotifyClientId(this, id)
                exportSpotify()
            }
            .setNeutralButton("Open Spotify dashboard") { _, _ -> open("https://developer.spotify.com/dashboard") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Back from Spotify's sign-in page with a code (or a refusal). */
    private fun handleCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "oldtimesradio" || uri.host != "spotify-callback") return
        intent.data = null   // handle it once
        val (verifier, state) = Store.spotifyPending(this) ?: return
        Store.saveSpotifyPending(this, null)
        if (uri.getQueryParameter("state") != state) return
        val code = uri.getQueryParameter("code")
        if (code == null) {
            Toast.makeText(this, "Spotify export cancelled.", Toast.LENGTH_SHORT).show()
            return
        }
        val id = Store.spotifyClientId(this) ?: return
        runExport("Spotify") { progress ->
            val token = Exporter.spotifyToken(id, code, verifier)
            Exporter.toSpotify(token, Store.favourites(this), progress)
        }
    }

    // ---- Export to YouTube: Google sign-in for the YouTube scope ----

    private fun exportYouTube() {
        if (Store.favourites(this).isEmpty()) { Toast.makeText(this, "No songs saved yet.", Toast.LENGTH_SHORT).show(); return }
        // First time: the one-time Google Cloud setup comes before Google's sign-in.
        if (!Store.youtubeReady(this)) { youtubeSetup(null, firstTime = true); return }
        signInYouTube()
    }

    private fun signInYouTube() {
        val req = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
            .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/youtube")))
            .build()
        runCatching {
            com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).authorize(req)
                .addOnSuccessListener { res ->
                    if (res.hasResolution()) runCatching {
                        startIntentSenderForResult(res.pendingIntent!!.intentSender, REQ_YOUTUBE, null, 0, 0, 0, null)
                    }.onFailure { youtubeSetup(it.message) }
                    else res.accessToken?.let { runYouTube(it) } ?: youtubeSetup(null)
                }
                .addOnFailureListener { youtubeSetup(googleReason(it)) }
        }.onFailure { youtubeSetup(googleReason(it)) }
    }

    /**
     * Google's answer in plain words. Its sign-in reports a missing or mismatched Cloud
     * setup as an error code after the account is chosen, not as a message.
     */
    private fun googleReason(e: Throwable?): String {
        val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
        return when (code) {
            10 -> "Google doesn't recognise this copy of the set (code 10). The Android OAuth client's package name or " +
                "SHA-1 doesn't match the ones below, or it's in a different project from the YouTube Data API."
            16, 12501 -> "The sign-in was closed before Google gave access (code $code). If Google showed \"Access blocked\" " +
                "or \"app not verified\", add your Google account as a test user on the OAuth consent screen."
            7 -> "No connection to Google (code 7). Check the internet connection."
            null -> e?.message ?: "Google didn't give access to YouTube after the account was chosen. This usually " +
                "means the setup below isn't finished: most often the test user or the SHA-1."
            else -> "Google said: code $code ${e.message.orEmpty()}".trim()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_YOUTUBE) return
        // Read Google's answer whatever the result code: a refusal after the account picker
        // comes back as "cancelled" with the real reason inside.
        val res = runCatching { com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data) }
        val token = res.getOrNull()?.accessToken
        when {
            token != null -> runYouTube(token)
            data == null && resultCode == RESULT_CANCELED -> youtubeSetup(googleReason(null))
            else -> youtubeSetup(googleReason(res.exceptionOrNull()))
        }
    }

    private fun runYouTube(token: String) = runExport("YouTube") { progress ->
        Exporter.toYouTube(token, Store.favourites(this), progress).also { Store.saveYoutubeReady(this, true) }
    }

    /**
     * Google lets the set ask for YouTube access only once it's registered in the user's own
     * Google Cloud project: the steps, with this copy of the set's package and fingerprint.
     */
    private fun youtubeSetup(error: String?, firstTime: Boolean = false) {
        val sha1 = signingSha1()
        val b = AlertDialog.Builder(this)
            .setTitle(if (error != null) "YouTube didn't connect" else "Connect YouTube (once)")
            .setMessage("To make YouTube playlists, Google needs the set registered in your own Google Cloud project (free):\n\n" +
                "1. On console.cloud.google.com, create a project.\n" +
                "2. APIs & Services → Library: enable \"YouTube Data API v3\".\n" +
                "3. Google Auth Platform (OAuth consent screen): Audience External, status Testing; " +
                "under Test users, add the Google account you'll choose.\n" +
                "4. Clients (Credentials) → Create client → Android, in the same project:\n" +
                "   Package name: $packageName\n" +
                "   SHA-1: $sha1\n\n" +
                "A new client can take a few minutes to start working. YouTube's free daily allowance covers about 60 songs a day." +
                (error?.let { "\n\nWhat went wrong: $it" } ?: ""))
            .setNeutralButton("Copy SHA-1") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("SHA-1", sha1))
                Toast.makeText(this, "SHA-1 copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
        if (firstTime || error != null) b.setPositiveButton(if (error != null) "Try again" else "It's set up: sign in") { _, _ ->
            Store.saveYoutubeReady(this, true)
            signInYouTube()
        }
        b.show()
    }

    /** The SHA-1 fingerprint of the key this copy of the set is signed with. */
    private fun signingSha1(): String = runCatching {
        val sig = if (Build.VERSION.SDK_INT >= 28)
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners[0]
        else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures!![0]
        java.security.MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("(unknown)")

    /** Runs an export in the background with a progress dialog, then offers the playlist. */
    private fun runExport(service: String, work: ((Int) -> Unit) -> Exporter.Result) {
        val total = Store.favourites(this).size
        val dialog = AlertDialog.Builder(this).setTitle("Export to $service")
            .setMessage("Signing in…").setCancelable(false).show()
        exporting = dialog
        Thread {
            val res = runCatching { work { i -> runOnUiThread { dialog.setMessage("Finding songs on $service… ${i + 1} of $total") } } }
            runOnUiThread {
                dialog.dismiss(); exporting = null
                if (isFinishing) return@runOnUiThread
                res.onSuccess { r ->
                    val missed = if (r.missing.isEmpty()) "" else "\n\nNot found (${r.missing.size}):\n" +
                        r.missing.take(12).joinToString("\n") { "• " + Favourites.line(it) } + (if (r.missing.size > 12) "\n…" else "")
                    val b = AlertDialog.Builder(this).setTitle("Export to $service")
                    if (r.playlistUrl == null) b.setMessage("None of the songs could be found on $service.$missed").setPositiveButton("OK", null)
                    else b.setMessage("Made a private playlist, \"${Favourites.PLAYLIST}\", with ${r.added} song${if (r.added == 1) "" else "s"}.$missed")
                        .setPositiveButton("Open playlist") { _, _ -> open(r.playlistUrl) }
                        .setNegativeButton("Close", null)
                    b.show()
                }.onFailure { e ->
                    if (service == "Spotify" && e.message?.contains("Client ID") == true) Store.saveSpotifyClientId(this, null)
                    AlertDialog.Builder(this).setTitle("Export to $service")
                        .setMessage("That didn't work: ${e.message ?: e.javaClass.simpleName}")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    // ---- Lifecycle ----

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        showState()
        // Look for a new build every time the app opens, and again on coming back to it
        // after a while (at most once an hour).
        if (System.currentTimeMillis() - lastUpdateCheck > 60 * 60_000L) {
            lastUpdateCheck = System.currentTimeMillis()
            autoUpdate()
        }
        // Back from allowing Old Times Radio to install apps: carry on with the install.
        if (pendingInstall > 0 && Updater.canInstall(this)) {
            val build = pendingInstall
            pendingInstall = -1
            Updater.downloaded(this, build)?.let { startActivity(Updater.installIntent(this, it)) }
        }
    }

    override fun onDestroy() {
        tonePlate.removeCallbacks(sleepClock)
        StreamService.unlisten(streamListener)
        Caster.unlisten(streamListener)
        downloadReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    // ---- Updates: checked on every start, downloaded in the background, installed on request ----

    /** Quietly checks for a newer build at startup and, if there is one, fetches it. */
    private fun autoUpdate() {
        Thread {
            runCatching { Updater.cleanUp(this) }
            val info = runCatching { Updater.check(this) }.getOrNull() ?: return@Thread
            if (info.newer) runOnUiThread { if (!isFinishing) handleNewBuild(info, fromUser = false) }
        }.start()
    }

    /** A newer build exists: offer it if it's downloaded, otherwise start downloading it. */
    private fun handleNewBuild(info: Updater.Info, fromUser: Boolean) {
        val file = Updater.downloaded(this, info.latest)
        when {
            file != null -> offerInstall(info.latest, file)
            Updater.downloading(this, info.latest) -> if (fromUser)
                Toast.makeText(this, "Already downloading build ${info.latest}…", Toast.LENGTH_SHORT).show()
            else -> runCatching { Updater.download(this, info.latest) }
                .onSuccess {
                    Toast.makeText(this, "New version found (build ${info.latest}). Downloading…", Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    if (fromUser) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.APK_URL)))
                }
        }
    }

    private fun onUpdateDownloaded() {
        if (isFinishing) return
        val build = Store.updateBuild(this)
        val file = Updater.downloaded(this, build)
        if (file != null && build > Updater.currentBuild(this)) offerInstall(build, file)
        else if (Updater.status(this) == android.app.DownloadManager.STATUS_FAILED)
            Toast.makeText(this, "The update didn't download. Try Check for updates later.", Toast.LENGTH_LONG).show()
    }

    private fun offerInstall(build: Long, file: java.io.File) {
        AlertDialog.Builder(this)
            .setTitle("Update ready")
            .setMessage("Old Times Radio build $build has downloaded (you have build ${Updater.currentBuild(this)}).\n\n" +
                "Install it now? Your stations are kept.")
            .setPositiveButton("Install") { _, _ -> install(build, file) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun install(build: Long, file: java.io.File) {
        if (!Updater.canInstall(this)) {
            pendingInstall = build
            Toast.makeText(this, "Allow Old Times Radio to install updates, then come back.", Toast.LENGTH_LONG).show()
            startActivity(Updater.allowInstallsIntent(this))
            return
        }
        startActivity(Updater.installIntent(this, file))
    }

    private fun checkForUpdate() {
        updateBtn.isEnabled = false
        updateBtn.text = "Checking…"
        Thread {
            val res = runCatching { Updater.check(this) }
            runOnUiThread {
                updateBtn.isEnabled = true
                updateBtn.text = "Check for updates"
                if (isFinishing) return@runOnUiThread
                res.onSuccess { info ->
                    if (info.newer) handleNewBuild(info, fromUser = true)
                    else Toast.makeText(this, "You're up to date (build ${info.current})", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Couldn't check for updates: ${it.message ?: it.javaClass.simpleName}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private companion object {
        const val REQ_YOUTUBE = 7
        /** Last update check this run (wall clock ms); kept across screen rotations. */
        var lastUpdateCheck = 0L
    }
}
