package cc.quandel.dashvoice

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Minimal configuration screen (long-press the mic button to open). */
class SettingsActivity : AppCompatActivity() {

    private val screensaverOptions = listOf(
        "30 Sekunden"  to  30_000L,
        "1 Minute"     to  60_000L,
        "2 Minuten"    to 120_000L,
        "5 Minuten"    to 300_000L,
        "10 Minuten"   to 600_000L,
        "30 Minuten"   to 1_800_000L,
        "Nie"          to Long.MAX_VALUE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = Prefs(this)

        // ── existing fields ──
        val url  = findViewById<EditText>(R.id.url)
        val port = findViewById<EditText>(R.id.port)
        val name = findViewById<EditText>(R.id.name)
        val wake = findViewById<EditText>(R.id.wake)

        url.setText(prefs.dashboardUrl)
        port.setText(prefs.satellitePort.toString())
        name.setText(prefs.satelliteName)
        wake.setText(prefs.wakeWord)

        // ── screensaver delay spinner ──
        val spinner = findViewById<Spinner>(R.id.screensaverDelay)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            screensaverOptions.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val selectedIdx = screensaverOptions.indexOfFirst { it.second == prefs.screensaverDelayMs }
        spinner.setSelection(if (selectedIdx >= 0) selectedIdx else 2)

        // ── HA token + sensor IDs ──
        val haToken = findViewById<EditText>(R.id.haToken)
        val sensors = findViewById<EditText>(R.id.screensaverSensors)
        val brainUrl = findViewById<EditText>(R.id.screensaverBrainUrl)
        val brainZoom = findViewById<EditText>(R.id.screensaverZoomDistance)
        val clockFrom = findViewById<EditText>(R.id.screensaverClockFrom)
        val clockTo = findViewById<EditText>(R.id.screensaverClockTo)
        val clockEntity = findViewById<EditText>(R.id.screensaverClockEntity)
        val gestures = findViewById<CheckBox>(R.id.screensaverGestures)
        gestures.isChecked = prefs.screensaverGesturesEnabled
        haToken.setText(prefs.haToken)
        sensors.setText(prefs.screensaverSensors)
        brainUrl.setText(prefs.screensaverBrainUrl)
        brainZoom.setText(prefs.screensaverZoomDistance)
        clockFrom.setText(prefs.screensaverClockFrom)
        clockTo.setText(prefs.screensaverClockTo)
        clockEntity.setText(prefs.screensaverClockEntity)

        // ── Klingel-Einstellungen ──
        val doorbellEntity = findViewById<EditText>(R.id.doorbellEntity)
        val doorbellUrl = findViewById<EditText>(R.id.doorbellCameraUrl)
        val doorbellDismiss = findViewById<EditText>(R.id.doorbellAutoDismissSec)
        doorbellEntity.setText(prefs.doorbellEntity)
        doorbellUrl.setText(prefs.doorbellCameraUrl)
        doorbellDismiss.setText(prefs.doorbellAutoDismissSec.toString())

        // ── Mic-Gain seekbar (Far-field): 0..500 → 1.0..6.0× ──
        val micGainLabel = findViewById<TextView>(R.id.micGainLabel)
        val micGainBar   = findViewById<SeekBar>(R.id.micGain)
        micGainBar.progress = ((prefs.micGain - 1.0f) * 100f).toInt().coerceIn(0, 500)
        micGainLabel.text = "Mikrofon-Verstärkung: ${"%.1f".format(prefs.micGain)}×"
        micGainBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                micGainLabel.text = "Mikrofon-Verstärkung: ${"%.1f".format(progress / 100f + 1.0f)}×"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ── Wake-Empfindlichkeit seekbar: progress 0..60, RECHTS = empfindlicher ──
        // Anzeige = Empfindlichkeit in % (rechts=100%); intern invertiert auf wakeThreshold 0.80..0.20.
        val wakeThLabel = findViewById<TextView>(R.id.wakeThresholdLabel)
        val wakeThBar   = findViewById<SeekBar>(R.id.wakeThreshold)
        fun wakeThLabelText(progress: Int) =
            "Wake-Empfindlichkeit: ${progress * 100 / 60}% (Schwelle ${"%.2f".format(0.80f - progress / 100f)})"
        wakeThBar.progress = ((0.80f - prefs.wakeThreshold) * 100f).toInt().coerceIn(0, 60)
        wakeThLabel.text = wakeThLabelText(wakeThBar.progress)
        wakeThBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                wakeThLabel.text = wakeThLabelText(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ── NoiseSuppressor toggle ──
        val noiseSuppression = findViewById<CheckBox>(R.id.noiseSuppression)
        noiseSuppression.isChecked = prefs.noiseSuppressionEnabled

        // ── Animation style ──
        val animSpinner = findViewById<Spinner>(R.id.animationStyle)
        val animOptions = listOf("Ripple Orb", "Frequenz", "Neural", "Vortex", "Geometrie", "Aurora", "Gesicht (Blase)")
        animSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, animOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        animSpinner.setSelection(prefs.animationStyle.coerceIn(0, animOptions.size - 1))

        // ── TTS volume seekbar ──
        val volumeLabel = findViewById<TextView>(R.id.ttsVolumeLabel)
        val volumeBar   = findViewById<SeekBar>(R.id.ttsVolume)
        volumeBar.progress = prefs.ttsVolume
        volumeLabel.text = "TTS-Lautstärke: ${prefs.ttsVolume}%"
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                volumeLabel.text = "TTS-Lautstärke: $progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ── KI-Partikel-Screensaver ──
        val particleEnabled = findViewById<CheckBox>(R.id.particleEnabled)
        particleEnabled.isChecked = prefs.enableParticleScreensaver

        val particleQualitySpinner = findViewById<Spinner>(R.id.particleQuality)
        val qualityOptions = listOf("AUTO", "LOW", "MEDIUM", "HIGH")
        particleQualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualityOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        particleQualitySpinner.setSelection(qualityOptions.indexOf(prefs.particleQuality).coerceAtLeast(0))

        val particleAssemblyAnim = findViewById<CheckBox>(R.id.particleAssemblyAnim)
        particleAssemblyAnim.isChecked = prefs.particleAssemblyAnimEnabled

        // Geschwindigkeit: progress 0..150 → 0.5×..2.0×
        val particleSpeedLabel = findViewById<TextView>(R.id.particleSpeedLabel)
        val particleSpeedBar = findViewById<SeekBar>(R.id.particleSpeed)
        particleSpeedBar.progress = ((prefs.particleAnimationSpeed - 0.5f) * 100f).toInt().coerceIn(0, 150)
        particleSpeedLabel.text = "Animationsgeschwindigkeit: ${"%.1f".format(prefs.particleAnimationSpeed)}×"
        particleSpeedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                particleSpeedLabel.text = "Animationsgeschwindigkeit: ${"%.1f".format(progress / 100f + 0.5f)}×"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val particleShowTranscript = findViewById<CheckBox>(R.id.particleShowTranscript)
        particleShowTranscript.isChecked = prefs.particleShowTranscript
        val particleShowResponse = findViewById<CheckBox>(R.id.particleShowResponse)
        particleShowResponse.isChecked = prefs.particleShowResponse
        val useHomeIntent = findViewById<CheckBox>(R.id.useHomeIntent)
        useHomeIntent.isChecked = prefs.useHomeIntent
        val instantCommandsEnabled = findViewById<CheckBox>(R.id.instantCommandsEnabled)
        instantCommandsEnabled.isChecked = prefs.instantCommandsEnabled

        // ── default launcher button ──
        findViewById<Button>(R.id.setDefaultLauncher).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (_: Exception) {
                val chooser = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                startActivity(Intent.createChooser(chooser, "Standard-Startbildschirm wählen"))
            }
        }

        // ── save ──
        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.dashboardUrl        = url.text.toString().trim()
            prefs.satellitePort       = port.text.toString().trim().toIntOrNull() ?: Prefs.DEFAULT_PORT
            prefs.satelliteName       = name.text.toString().trim()
            prefs.wakeWord            = wake.text.toString().trim()
            prefs.screensaverDelayMs  = screensaverOptions[spinner.selectedItemPosition].second
            prefs.haToken             = haToken.text.toString().trim()
            prefs.screensaverSensors  = sensors.text.toString().trim()
            prefs.screensaverBrainUrl = brainUrl.text.toString().trim()
            prefs.screensaverZoomDistance = brainZoom.text.toString().trim()
            prefs.screensaverClockFrom = validTimeOrEmpty(clockFrom.text.toString().trim())
            prefs.screensaverClockTo   = validTimeOrEmpty(clockTo.text.toString().trim())
            prefs.screensaverClockEntity = clockEntity.text.toString().trim()
            prefs.screensaverGesturesEnabled = gestures.isChecked
            prefs.doorbellEntity      = doorbellEntity.text.toString().trim()
            prefs.doorbellCameraUrl   = doorbellUrl.text.toString().trim()
            prefs.doorbellAutoDismissSec = doorbellDismiss.text.toString().trim().toIntOrNull() ?: 30
            prefs.ttsVolume           = volumeBar.progress
            prefs.micGain             = micGainBar.progress / 100f + 1.0f
            prefs.wakeThreshold       = 0.80f - wakeThBar.progress / 100f
            prefs.noiseSuppressionEnabled = noiseSuppression.isChecked
            prefs.animationStyle      = animSpinner.selectedItemPosition
            prefs.enableParticleScreensaver = particleEnabled.isChecked
            prefs.particleQuality     = qualityOptions[particleQualitySpinner.selectedItemPosition]
            prefs.particleAssemblyAnimEnabled = particleAssemblyAnim.isChecked
            prefs.particleAnimationSpeed = particleSpeedBar.progress / 100f + 0.5f
            prefs.particleShowTranscript = particleShowTranscript.isChecked
            prefs.particleShowResponse = particleShowResponse.isChecked
            prefs.useHomeIntent       = useHomeIntent.isChecked
            prefs.instantCommandsEnabled = instantCommandsEnabled.isChecked
            // Audio-Einstellungen (Empfindlichkeit/Gain/NS) sofort an den laufenden Dienst übergeben.
            VoiceService.applySettings(this)
            Toast.makeText(this, "Gespeichert – Audio sofort aktiv", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Gibt "HH:mm" zurück, falls gültig, sonst leer (= Zeitfenster deaktiviert). */
    private fun validTimeOrEmpty(s: String): String {
        val m = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$").find(s) ?: return ""
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return "%02d:%02d".format(h, min)
    }
}
