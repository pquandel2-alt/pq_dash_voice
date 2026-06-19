package cc.quandel.dashvoice

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
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
import cc.quandel.dashvoice.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kiosk shell: full-screen Lovelace WebView, tap-to-talk, clock screensaver with HA sensors. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var screensaver: View
    private lateinit var clock: TextView
    private lateinit var screensaverDate: TextView
    private lateinit var sensor1: TextView
    private lateinit var sensor2: TextView
    private lateinit var sensor3: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView
    private lateinit var statusDot: View

    private lateinit var voiceOverlay: View
    private lateinit var voiceMicIcon: ImageView
    private lateinit var voiceStateText: TextView
    private lateinit var voiceTranscriptText: TextView
    private lateinit var voiceResponseText: TextView
    private lateinit var voiceAnimation: VoiceAnimationView
    private var micPulseAnimator: ObjectAnimator? = null

    private var sensorFetcher: HaSensorFetcher? = null

    private val ui = Handler(Looper.getMainLooper())

    private val showSaver = Runnable { showScreensaver() }
    private val tickClock = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            screensaverDate.text = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(now)
            ui.postDelayed(this, 10_000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        webView             = findViewById(R.id.webview)
        screensaver         = findViewById(R.id.screensaver)
        clock               = findViewById(R.id.clock)
        screensaverDate     = findViewById(R.id.screensaverDate)
        sensor1             = findViewById(R.id.sensor1)
        sensor2             = findViewById(R.id.sensor2)
        sensor3             = findViewById(R.id.sensor3)
        logScroll           = findViewById(R.id.logScroll)
        logText             = findViewById(R.id.logText)
        statusDot           = findViewById(R.id.statusDot)
        voiceOverlay        = findViewById(R.id.voiceOverlay)
        voiceMicIcon        = findViewById(R.id.voiceMicIcon)
        voiceStateText      = findViewById(R.id.voiceStateText)
        voiceTranscriptText = findViewById(R.id.voiceTranscriptText)
        voiceResponseText   = findViewById(R.id.voiceResponseText)
        voiceAnimation      = findViewById(R.id.voiceAnimation)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = WebViewClient()
        webView.loadUrl(prefs.dashboardUrl)

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
    }

    private fun subscribeVoiceEvents() {
        VoiceEvents.onConnected = {
            setStatusDot(connected = true)
        }
        VoiceEvents.onDisconnected = {
            setStatusDot(connected = false)
        }
        VoiceEvents.onWake = {
            dismissScreensaver()
            voiceTranscriptText.visibility = View.GONE
            voiceResponseText.visibility = View.GONE
            voiceTranscriptText.text = ""
            voiceResponseText.text = ""
            voiceStateText.text = "Zuhören…"
            voiceOverlay.visibility = View.VISIBLE
            startMicPulse()
            voiceAnimation.setState(VoiceAnimationView.State.LISTENING)
            voiceAnimation.startAnimation()
        }
        VoiceEvents.onTranscript = { text ->
            if (text.isNotEmpty()) {
                voiceTranscriptText.text = text
                voiceTranscriptText.visibility = View.VISIBLE
            }
            voiceStateText.text = "Verarbeite…"
            stopMicPulse()
            voiceAnimation.setState(VoiceAnimationView.State.THINKING)
        }
        VoiceEvents.onResponse = { text ->
            if (text.isNotEmpty()) {
                voiceResponseText.text = text
                voiceResponseText.visibility = View.VISIBLE
            }
            voiceStateText.text = "Antwortet…"
            voiceAnimation.setState(VoiceAnimationView.State.SPEAKING)
        }
        VoiceEvents.onIdle = {
            stopMicPulse()
            voiceAnimation.stopAnimation()
            voiceOverlay.visibility = View.GONE
            resetScreensaverTimer()
        }
    }

    private fun setStatusDot(connected: Boolean) {
        val color = if (connected) Color.parseColor("#4CAF50") else Color.parseColor("#E53935")
        statusDot.background?.let {
            (it as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        } ?: statusDot.setBackgroundColor(color)
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

    private fun showScreensaver() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        screensaverDate.text = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(now)
        screensaver.visibility = View.VISIBLE
        ui.post(tickClock)

        // Bildschirm auf ~3% dimmen
        val lp = window.attributes
        lp.screenBrightness = 0.03f
        window.attributes = lp

        // Sensordaten starten
        startSensorFetcher()
    }

    private fun dismissScreensaver() {
        ui.removeCallbacks(tickClock)
        screensaver.visibility = View.GONE
        resetScreensaverTimer()

        // Helligkeit zurücksetzen
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp

        sensorFetcher?.stop()
        sensorFetcher = null
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
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

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
        VoiceEvents.onWake = null
        VoiceEvents.onTranscript = null
        VoiceEvents.onResponse = null
        VoiceEvents.onIdle = null
        VoiceEvents.onConnected = null
        VoiceEvents.onDisconnected = null
        stopMicPulse()
        voiceAnimation.stopAnimation()
        sensorFetcher?.stop()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
