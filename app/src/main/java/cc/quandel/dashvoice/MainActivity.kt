package cc.quandel.dashvoice

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cc.quandel.dashvoice.particle.ParticleAssistantController
import cc.quandel.dashvoice.util.AppLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Kiosk shell: full-screen Lovelace WebView, tap-to-talk, clock screensaver with HA sensors. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var screensaver: View
    private lateinit var saverWebView: WebView
    private lateinit var clockBlock: View
    private lateinit var clock: TextView
    private lateinit var screensaverDate: TextView
    private lateinit var sensor1: TextView
    private lateinit var sensor2: TextView
    private lateinit var sensor3: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView
    private lateinit var statusDot: View
    private lateinit var timerChip: TextView

    private lateinit var voiceOverlay: View
    private lateinit var voiceMicIcon: ImageView
    private lateinit var voiceStateText: TextView
    private lateinit var voiceTranscriptText: TextView
    private lateinit var voiceResponseText: TextView
    private lateinit var voiceAnimation: VoiceAnimationView
    private var micPulseAnimator: ObjectAnimator? = null

    private var sensorFetcher: HaSensorFetcher? = null
    private var particleController: ParticleAssistantController? = null

    private lateinit var doorbellOverlay: View
    private lateinit var doorbellWebView: WebView
    @Volatile private var doorbellWasOn = false
    private var doorbellLastLoadedAt = 0L

    private val ui = Handler(Looper.getMainLooper())

    private val dismissDoorbell = Runnable { hideDoorbell() }

    private val showSaver = Runnable { showScreensaver() }
    private val periodicReload = object : Runnable {
        override fun run() {
            AppLog.i("UI", "Periodischer WebView-Reload (Stabilität)")
            webView.reload()
            ui.postDelayed(this, WEBVIEW_RELOAD_MS)
        }
    }
    private val tickTimer = object : Runnable {
        override fun run() {
            val rem = prefs.timerEndAt - System.currentTimeMillis()
            if (prefs.timerEndAt > 0L && rem > 0L) {
                timerChip.text = "⏲ ${fmtRemaining(rem)}"
                timerChip.visibility = View.VISIBLE
                ui.postDelayed(this, 1000)
            } else {
                timerChip.visibility = View.GONE
            }
        }
    }
    private val tickClock = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            screensaverDate.text = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(now)
            ui.postDelayed(this, 10_000)
        }
    }

    /** Zuletzt bekannter Zustand von prefs.screensaverClockEntity ("on" = Uhr erzwingen). */
    @Volatile private var clockForced = false
    private val pollClockEntity = object : Runnable {
        override fun run() {
            val entity = prefs.screensaverClockEntity
            val token = prefs.haToken
            if (entity.isNotEmpty() && token.isNotBlank()) {
                fetchEntityState(prefs.dashboardUrl, token, entity) { state ->
                    clockForced = state == "on"
                    maybeSwitchScreensaverMode()
                }
            } else {
                maybeSwitchScreensaverMode()
            }
            ui.postDelayed(this, CLOCK_ENTITY_POLL_MS)
        }
    }

    private val pollDoorbellEntity = object : Runnable {
        override fun run() {
            val entity = prefs.doorbellEntity
            val token = prefs.haToken
            if (entity.isNotEmpty() && token.isNotBlank()) {
                fetchEntityState(prefs.dashboardUrl, token, entity) { state ->
                    val isOn = state == "on"
                    if (isOn && !doorbellWasOn) showDoorbell()
                    if (!isOn) doorbellWasOn = false
                }
            }
            ui.postDelayed(this, DOORBELL_ENTITY_POLL_MS)
        }
    }

    /**
     * Prüft, ob der Screensaver gerade läuft und der Modus (Graph/Uhr) noch zum
     * Zeitfenster bzw. der Erzwingen-Entität passt — nötig, weil showScreensaver()
     * sonst nur beim ersten Anzeigen entscheidet und ein Wechsel des Zeitfensters
     * (z. B. 23:59 Uhr-Start) während eines bereits laufenden Graph-Screensavers
     * sonst nie greifen würde.
     */
    private fun maybeSwitchScreensaverMode() {
        if (screensaver.visibility != View.VISIBLE) return
        if (prefs.screensaverBrainUrl.isEmpty()) return
        val entityConfigured = prefs.screensaverClockEntity.isNotEmpty()
        val desiredShowClock = if (entityConfigured) clockForced else isWithinClockWindow()
        val currentlyShowingClock = clockBlock.visibility == View.VISIBLE
        if (desiredShowClock != currentlyShowingClock) showScreensaver()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        webView             = findViewById(R.id.webview)
        screensaver         = findViewById(R.id.screensaver)
        saverWebView        = findViewById(R.id.saverWebView)
        clockBlock          = findViewById(R.id.clockBlock)
        clock               = findViewById(R.id.clock)
        screensaverDate     = findViewById(R.id.screensaverDate)
        sensor1             = findViewById(R.id.sensor1)
        sensor2             = findViewById(R.id.sensor2)
        sensor3             = findViewById(R.id.sensor3)
        logScroll           = findViewById(R.id.logScroll)
        logText             = findViewById(R.id.logText)
        statusDot           = findViewById(R.id.statusDot)
        timerChip           = findViewById(R.id.timerChip)
        voiceOverlay        = findViewById(R.id.voiceOverlay)
        voiceMicIcon        = findViewById(R.id.voiceMicIcon)
        voiceStateText      = findViewById(R.id.voiceStateText)
        voiceTranscriptText = findViewById(R.id.voiceTranscriptText)
        voiceResponseText   = findViewById(R.id.voiceResponseText)
        voiceAnimation      = findViewById(R.id.voiceAnimation)
        voiceAnimation.style = prefs.animationStyle
        doorbellOverlay     = findViewById(R.id.doorbellOverlay)
        doorbellWebView     = findViewById(R.id.doorbellWebView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = WebViewClient()
        webView.loadUrl(prefs.dashboardUrl)

        // Initialisiere Partikel-Screensaver (wird später geladen, wenn nötig)
        if (prefs.enableParticleScreensaver) {
            particleController = ParticleAssistantController(saverWebView)
            AppLog.i("UI", "Particle Screensaver initialized")
        }

        // Screensaver-Graph-WebView (Brain-Graph-Add-on, ?kiosk). Lädt erst beim Anzeigen.
        saverWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        saverWebView.webViewClient = WebViewClient()
        saverWebView.setBackgroundColor(Color.BLACK)
        // Kamera nur für die Gestensteuerung im Graph-Screensaver — nur gewähren,
        // wenn der Nutzer sie in den Settings aktiviert hat (Default aus).
        saverWebView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                if (prefs.screensaverGesturesEnabled &&
                    request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                ) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    request.deny()
                }
            }
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                AppLog.i("SaverJS", "${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                return true
            }
        }
        // Kiosk: jede Berührung des Graphen beendet den Screensaver.
        saverWebView.setOnTouchListener { v, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                v.performClick()
                dismissScreensaver()
            }
            true
        }

        findViewById<ImageButton>(R.id.micButton).apply {
            setOnClickListener {
                AppLog.i("UI", "Tap-to-Talk gedrückt")
                VoiceService.talk(this@MainActivity)
            }
            setOnLongClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                true
            }
        }

        findViewById<Button>(R.id.doorbellClose).setOnClickListener {
            hideDoorbell()
        }

        // Klingel-WebView vorladen und im Hintergrund WEITERLAUFEN lassen (nicht pausieren!),
        // damit showDoorbell() nur noch die Sichtbarkeit umschalten muss statt die HA-Lovelace-
        // Seite komplett neu zu laden. WebView.onPause() würde auch den Live-Stream (und dessen
        // Token-Refresh-Timer) einfrieren – beim Resume bleibt dann nur ein Standbild übrig.
        doorbellWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        doorbellWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                doorbellLastLoadedAt = System.currentTimeMillis()
            }
        }
        loadDoorbellCamera()

        findViewById<Button>(R.id.logToggle).setOnClickListener {
            logScroll.visibility =
                if (logScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (logScroll.visibility == View.VISIBLE) refreshLog()
        }
        AppLog.listener = { refreshLog() }

        screensaver.setOnClickListener { dismissScreensaver() }

        subscribeVoiceEvents()
        ensurePermissions()
        ensureBatteryOptimizationDisabled()
        AppLog.i("UI", "MainActivity gestartet – Dashboard=${prefs.dashboardUrl}")
        VoiceService.start(this)
        resetScreensaverTimer()
        ui.postDelayed(periodicReload, WEBVIEW_RELOAD_MS)
        ui.post(tickTimer)   // läuft ein Timer noch (auch nach App-Neustart)? → Chip zeigen
        ui.post(pollClockEntity)
        ui.post(pollDoorbellEntity)
    }

    /** Verbleibende Zeit als mm:ss bzw. h:mm:ss (aufgerundet auf Sekunden). */
    private fun fmtRemaining(ms: Long): String {
        val s = (ms + 999) / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    companion object {
        private const val WEBVIEW_RELOAD_MS = 6L * 60 * 60 * 1000  // alle 6h Dashboard neu laden
        private const val CLOCK_ENTITY_POLL_MS = 15_000L
        private const val DOORBELL_ENTITY_POLL_MS = 1_000L
        private const val DOORBELL_STALE_MS = 10L * 60 * 1000  // danach vor dem Anzeigen neu laden statt nur zu resumen
    }

    /** Fragt einmalig den Zustand einer HA-Entität per REST ab (Ergebnis kommt im Main-Thread an). */
    private fun fetchEntityState(haUrl: String, token: String, entityId: String, callback: (String?) -> Unit) {
        thread(name = "ha-clock-entity-fetch") {
            val state = try {
                val base = haUrl.trimEnd('/')
                val conn = URL("$base/api/states/$entityId").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                json.optString("state", null)
            } catch (e: Exception) {
                AppLog.w("ClockEntity", "fetch $entityId: ${e.message}")
                null
            }
            ui.post { callback(state) }
        }
    }

    private fun subscribeVoiceEvents() {
        // Status-Dot sofort aus dem echten Zustand setzen — eine nach dem 04:00-Relaunch neu
        // erzeugte Activity bekommt sonst kein onConnected-Event (Verbindung bestand durchgehend)
        // und bliebe fälschlich auf Default-Rot.
        setStatusDot(connected = VoiceEvents.connected)

        // Analog: VoiceAnimationView aus dem echten Zustand initialisieren. Nach dem 04:00-Relaunch
        // hat die neue Activity ohne Initialisierung kein onWake-Event bekommen (wenn gerade Sprachinteraktion lief),
        // und der Avatar bliebe unsichtbar. Deshalb hier den Service-State abfragen.
        when (VoiceEvents.voiceState) {
            VoiceService.State.RECOGNIZING -> {
                voiceAnimation.visibility = View.VISIBLE  // WICHTIG: aus GONE zurücksetzen nach Relaunch
                voiceAnimation.setState(VoiceAnimationView.State.LISTENING)
                voiceAnimation.startAnimation()
                voiceOverlay.visibility = View.VISIBLE
            }
            VoiceService.State.STREAMING -> {
                voiceAnimation.visibility = View.VISIBLE  // WICHTIG: aus GONE zurücksetzen nach Relaunch
                voiceAnimation.setState(VoiceAnimationView.State.LISTENING)
                voiceAnimation.startAnimation()
                voiceOverlay.visibility = View.VISIBLE
            }
            VoiceService.State.SPEAKING -> {
                voiceAnimation.visibility = View.VISIBLE  // WICHTIG: aus GONE zurücksetzen nach Relaunch
                voiceAnimation.setState(VoiceAnimationView.State.SPEAKING)
                voiceAnimation.startAnimation()
                voiceOverlay.visibility = View.VISIBLE
            }
            else -> {} // IDLE: Avatar bleibt unsichtbar
        }

        VoiceEvents.onConnected = {
            setStatusDot(connected = true)
        }
        VoiceEvents.onDisconnected = {
            setStatusDot(connected = false)
        }
        VoiceEvents.onWake = {
            val screensaverActive = screensaver.visibility == View.VISIBLE

            if (screensaverActive && prefs.enableParticleScreensaver) {
                // Screensaver aktiv → Partikel als Voice-UI verwenden
                particleController?.transitionTo(
                    cc.quandel.dashvoice.particle.ParticleState.LISTENING
                )
                AppLog.i("UI", "Wake detected during particle screensaver → LISTENING")
            } else {
                // Dashboard aktiv → klassisches Voice-Overlay
                if (screensaverActive) {
                    dismissScreensaver()
                }
                voiceTranscriptText.visibility = View.GONE
                voiceResponseText.visibility = View.GONE
                voiceTranscriptText.text = ""
                voiceResponseText.text = ""
                voiceStateText.text = "Zuhören…"
                voiceOverlay.animate().cancel()
                voiceOverlay.alpha = 1f
                voiceOverlay.visibility = View.VISIBLE
                startMicPulse()
                voiceAnimation.setState(VoiceAnimationView.State.LISTENING)
                voiceAnimation.startAnimation()
                setWebViewBlur(true)
            }
        }
        VoiceEvents.onTranscript = { text ->
            particleController?.transitionTo(
                cc.quandel.dashvoice.particle.ParticleState.THINKING
            )
            if (text.isNotEmpty()) {
                voiceTranscriptText.text = text
                voiceTranscriptText.visibility = View.VISIBLE
            }
            voiceStateText.text = "Verarbeite…"
            stopMicPulse()
            voiceAnimation.setState(VoiceAnimationView.State.THINKING)
        }
        VoiceEvents.onResponse = { text ->
            particleController?.transitionTo(
                cc.quandel.dashvoice.particle.ParticleState.SPEAKING
            )
            if (text.isNotEmpty()) {
                voiceResponseText.text = text
                voiceResponseText.visibility = View.VISIBLE
            }
            voiceStateText.text = "Antwortet…"
            voiceAnimation.setState(VoiceAnimationView.State.SPEAKING)
        }
        VoiceEvents.onTtsLevel = { level ->
            particleController?.setAudioLevel(level)
            voiceAnimation.setMouthLevel(level)
        }
        VoiceEvents.onCommandDone = { _ ->
            // Sofortbefehl ausgeführt: grüne „Erledigt"-Animation kurz zeigen, dann ausblenden (stumm).
            particleController?.transitionTo(
                cc.quandel.dashvoice.particle.ParticleState.SUCCESS
            )
            stopMicPulse()
            voiceTranscriptText.visibility = View.GONE
            voiceResponseText.visibility = View.GONE
            voiceStateText.text = "✓ Erledigt"
            voiceAnimation.setState(VoiceAnimationView.State.DONE)
            setWebViewBlur(false)
            if (screensaver.visibility != View.VISIBLE) {
                // Overlay nur bei Dashboard-Modus
                voiceOverlay.animate()
                    .alpha(0f)
                    .setStartDelay(1200)
                    .setDuration(450)
                    .withEndAction {
                        voiceAnimation.stopAnimation()
                        voiceOverlay.visibility = View.GONE
                        voiceOverlay.alpha = 1f
                    }
                    .start()
            }
            resetScreensaverTimer()
        }
        VoiceEvents.onTimerSet = { label ->
            ui.post(tickTimer)   // Restzeit-Chip starten
            stopMicPulse()
            voiceTranscriptText.visibility = View.GONE
            voiceResponseText.visibility = View.GONE
            voiceStateText.text = "⏲ Timer: $label"
            voiceAnimation.setState(VoiceAnimationView.State.DONE)
            setWebViewBlur(false)
            voiceOverlay.animate()
                .alpha(0f).setStartDelay(1500).setDuration(450)
                .withEndAction {
                    voiceAnimation.stopAnimation()
                    voiceOverlay.visibility = View.GONE
                    voiceOverlay.alpha = 1f
                }.start()
            resetScreensaverTimer()
        }
        VoiceEvents.onTimerRinging = {
            ui.removeCallbacks(tickTimer)
            timerChip.visibility = View.GONE
            dismissScreensaver()
            voiceTranscriptText.visibility = View.GONE
            voiceResponseText.visibility = View.GONE
            voiceStateText.text = "⏰ Timer abgelaufen — tippen zum Stoppen"
            voiceAnimation.setState(VoiceAnimationView.State.SPEAKING)
            voiceAnimation.startAnimation()
            voiceOverlay.animate().cancel()
            voiceOverlay.alpha = 1f
            voiceOverlay.visibility = View.VISIBLE
            setWebViewBlur(true)
            voiceOverlay.setOnClickListener { VoiceService.stopTimer(this@MainActivity) }
        }
        VoiceEvents.onTimerStopped = {
            voiceOverlay.setOnClickListener(null)
            stopMicPulse()
            voiceAnimation.stopAnimation()
            setWebViewBlur(false)
            voiceOverlay.visibility = View.GONE
            voiceOverlay.alpha = 1f
            resetScreensaverTimer()
        }
        VoiceEvents.onIdle = {
            particleController?.transitionTo(
                cc.quandel.dashvoice.particle.ParticleState.IDLE
            )
            stopMicPulse()
            voiceAnimation.stopAnimation()
            setWebViewBlur(false)
            if (screensaver.visibility != View.VISIBLE) {
                // Overlay nur bei Dashboard-Modus
                voiceOverlay.animate()
                    .alpha(0f)
                    .setStartDelay(2000)
                    .setDuration(500)
                    .withEndAction {
                        voiceOverlay.visibility = View.GONE
                        voiceOverlay.alpha = 1f
                    }
                    .start()
            }
            resetScreensaverTimer()
        }
        VoiceEvents.onNetworkAvailable = {
            webView.reload()
        }
    }

    private fun setStatusDot(connected: Boolean) {
        val color = if (connected) Color.parseColor("#4CAF50") else Color.parseColor("#E53935")
        statusDot.background?.let {
            (it as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        } ?: statusDot.setBackgroundColor(color)
    }

    private fun setWebViewBlur(blur: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            webView.setRenderEffect(
                if (blur) RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
                else null
            )
        }
    }

    private fun startMicPulse() {
        micPulseAnimator?.cancel()
        micPulseAnimator = ObjectAnimator.ofFloat(voiceMicIcon, "alpha", 1f, 0.2f).apply {
            duration = 600
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopMicPulse() {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        voiceMicIcon.alpha = 1f
    }

    private fun refreshLog() {
        if (!::logText.isInitialized || logScroll.visibility != View.VISIBLE) return
        logText.text = AppLog.snapshot().joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun resetScreensaverTimer() {
        ui.removeCallbacks(showSaver)
        val delayMs = prefs.screensaverDelayMs
        if (delayMs < Long.MAX_VALUE) ui.postDelayed(showSaver, delayMs)
    }

    /** Hängt &zoom=<Wert> (bzw. ?zoom= falls die URL noch keine Query hat) an, wenn gesetzt. */
    private fun withZoomParam(url: String, zoom: String): String {
        if (zoom.isEmpty()) return url
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}zoom=$zoom"
    }

    /**
     * Hängt &gestures=1 an, wenn die Gestensteuerung aktiviert UND die CAMERA-Permission
     * bereits gewährt ist (sonst würde die WebView den getUserMedia-Zugriff nur ablehnen).
     */
    private fun withGesturesParam(url: String, enabled: Boolean): String {
        if (!enabled || !hasCameraPermission()) return url
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}gestures=1"
    }

    /**
     * Leitet die Brain-Graph-URL über einen lokalen 127.0.0.1-Proxy um, wenn die Gesten-
     * steuerung aktiviert ist. Grund: Chromium erlaubt getUserMedia nur in einem "secure
     * context" (https oder Host localhost/127.0.0.1) — eine rohe LAN-IP wie
     * 192.168.178.101 zählt selbst über http nie als secure, die Kamera bliebe sonst
     * unerreichbar. Ohne Gestensteuerung bleibt die Original-URL unverändert (kein Umweg
     * nötig).
     */
    private fun withLocalProxy(url: String, gesturesEnabled: Boolean): String {
        if (!gesturesEnabled || !hasCameraPermission()) return url
        val uri = Uri.parse(url)
        val host = uri.host ?: return url
        val port = if (uri.port != -1) uri.port else 80
        val localPort = LocalProxy.ensureStarted(host, port)
        val builder = Uri.Builder()
            .scheme("http")
            .encodedAuthority("127.0.0.1:$localPort")
            .encodedPath(uri.path ?: "/")
        if (uri.query != null) builder.encodedQuery(uri.query)
        return builder.build().toString()
    }

    /** Prüft, ob "jetzt" innerhalb des konfigurierten Uhr-Zeitfensters liegt (überspringt Mitternacht korrekt). */
    private fun isWithinClockWindow(): Boolean {
        val fromMin = parseTimeToMinutes(prefs.screensaverClockFrom) ?: return false
        val toMin = parseTimeToMinutes(prefs.screensaverClockTo) ?: return false
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (fromMin <= toMin) nowMin in fromMin until toMin
        else nowMin >= fromMin || nowMin < toMin
    }

    private fun parseTimeToMinutes(s: String): Int? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun showScreensaver() {
        val brainUrl = prefs.screensaverBrainUrl
        val entityConfigured = prefs.screensaverClockEntity.isNotEmpty()
        val showClock = if (entityConfigured) clockForced else isWithinClockWindow()
        val lp = window.attributes

        // Neuer Modus: Partikel-Screensaver (Priorität: höher als Brain-Graph)
        if (prefs.enableParticleScreensaver && !showClock) {
            // Partikel-KI-Screensaver
            clockBlock.visibility = View.GONE
            saverWebView.visibility = View.VISIBLE
            saverWebView.onResume()

            // Laden der lokalen HTML-Datei mit Partikel-Szene
            val assetUrl = "file:///android_asset/particle-screensaver.html"
            AppLog.i("Saver", "Lade Partikel-Screensaver: $assetUrl")
            saverWebView.loadUrl(assetUrl)

            // Partikel-Scene initialisieren und starten
            particleController?.resetScene()
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else if (brainUrl.isNotEmpty() && !showClock) {
            // Brain-Graph-Modus: Live-3D-Graph in Vollbild, normale Helligkeit (soll sichtbar sein).
            clockBlock.visibility = View.GONE
            saverWebView.visibility = View.VISIBLE
            saverWebView.onResume()
            val saverUrl = withGesturesParam(
                withZoomParam(
                    withLocalProxy(brainUrl, prefs.screensaverGesturesEnabled),
                    prefs.screensaverZoomDistance
                ),
                prefs.screensaverGesturesEnabled
            )
            AppLog.i("Saver", "Lade Brain-Graph-URL: $saverUrl (gesturesEnabled=${prefs.screensaverGesturesEnabled}, camPerm=${hasCameraPermission()})")
            saverWebView.loadUrl(saverUrl)
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            // Uhr-Modus: Uhr + Datum + Sensoren, Bildschirm auf ~3% dimmen.
            saverWebView.visibility = View.GONE
            clockBlock.visibility = View.VISIBLE
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            screensaverDate.text = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(now)
            lp.screenBrightness = 0.03f
            ui.post(tickClock)
            startSensorFetcher()
        }
        window.attributes = lp

        screensaver.alpha = 0f
        screensaver.visibility = View.VISIBLE
        screensaver.animate().alpha(1f).setDuration(600).start()
    }

    private fun dismissScreensaver() {
        ui.removeCallbacks(tickClock)
        resetScreensaverTimer()
        sensorFetcher?.stop()
        sensorFetcher = null
        particleController?.pause()

        screensaver.animate().alpha(0f).setDuration(400).withEndAction {
            screensaver.visibility = View.GONE
            // Graph-WebView stoppen (about:blank beendet Three.js/rAF) → schont Akku/GPU.
            if (saverWebView.visibility == View.VISIBLE) {
                saverWebView.loadUrl("about:blank")
                saverWebView.onPause()
                saverWebView.visibility = View.GONE
            }
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }.start()
    }

    private fun startSensorFetcher() {
        sensorFetcher?.stop()
        val token = prefs.haToken
        val sensors = prefs.screensaverSensors
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
        if (token.isBlank() || sensors.isEmpty()) return

        sensorFetcher = HaSensorFetcher(
            haUrl = prefs.dashboardUrl,
            token = token,
            entityIds = sensors
        ) { values ->
            val views = listOf(sensor1, sensor2, sensor3)
            views.forEachIndexed { i, tv ->
                val v = values.getOrNull(i)
                if (v != null) {
                    tv.text = "${v.name}: ${v.state}${if (v.unit.isNotEmpty()) " ${v.unit}" else ""}"
                    tv.visibility = View.VISIBLE
                } else {
                    tv.visibility = View.GONE
                }
            }
        }
        sensorFetcher?.start()
    }

    /** Lädt die Klingel-Kamera-URL (neu). Setzt doorbellLastLoadedAt beim Fertigladen (onPageFinished). */
    private fun loadDoorbellCamera() {
        if (prefs.doorbellCameraUrl.isBlank()) return
        doorbellWebView.loadUrl(prefs.doorbellCameraUrl)
    }

    private fun showDoorbell() {
        doorbellWasOn = true
        dismissScreensaver()
        val staleMs = System.currentTimeMillis() - doorbellLastLoadedAt
        if (doorbellLastLoadedAt == 0L || staleMs > DOORBELL_STALE_MS) {
            loadDoorbellCamera()
        }
        doorbellOverlay.alpha = 0f
        doorbellOverlay.visibility = View.VISIBLE
        doorbellOverlay.animate().alpha(1f).setDuration(400).start()
        ui.removeCallbacks(dismissDoorbell)
        ui.postDelayed(dismissDoorbell, prefs.doorbellAutoDismissSec * 1000L)
        AppLog.i("Doorbell", "Klingel angezeigt – Auto-Dismiss in ${prefs.doorbellAutoDismissSec}s")
    }

    private fun hideDoorbell() {
        ui.removeCallbacks(dismissDoorbell)
        doorbellOverlay.animate().alpha(0f).setDuration(350).withEndAction {
            doorbellOverlay.visibility = View.GONE
        }.start()
    }

    private fun ensureBatteryOptimizationDisabled() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("Hintergrund-Betrieb erlauben")
            .setMessage(
                "Damit der Sprachassistent dauerhaft laeuft:\n\n" +
                "Bitte 'Nicht optimieren' waehlen, damit EMUI die App nicht beendet."
            )
            .setPositiveButton("Einstellungen") { _, _ ->
                try {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (prefs.screensaverGesturesEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.CAMERA)
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (screensaver.visibility != View.VISIBLE) resetScreensaverTimer()
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    @Deprecated("Kiosk: swallow back navigation")
    override fun onBackPressed() { /* intentionally ignored */ }

    override fun onDestroy() {
        AppLog.listener = null
        voiceOverlay.animate().cancel()
        doorbellOverlay.animate().cancel()
        VoiceEvents.onWake = null
        VoiceEvents.onTranscript = null
        VoiceEvents.onResponse = null
        VoiceEvents.onTtsLevel = null
        VoiceEvents.onCommandDone = null
        VoiceEvents.onTimerSet = null
        VoiceEvents.onTimerRinging = null
        VoiceEvents.onTimerStopped = null
        VoiceEvents.onIdle = null
        VoiceEvents.onConnected = null
        VoiceEvents.onDisconnected = null
        VoiceEvents.onNetworkAvailable = null
        stopMicPulse()
        voiceAnimation.stopAnimation()
        sensorFetcher?.stop()
        particleController?.destroy()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
