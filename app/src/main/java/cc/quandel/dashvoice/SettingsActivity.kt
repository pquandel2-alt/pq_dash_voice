package cc.quandel.dashvoice

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
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
        haToken.setText(prefs.haToken)
        sensors.setText(prefs.screensaverSensors)

        // ── Animation style ──
        val animSpinner = findViewById<Spinner>(R.id.animationStyle)
        val animOptions = listOf("Ripple Orb", "Frequenz", "Neural", "Vortex", "Geometrie", "Aurora")
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
            prefs.ttsVolume           = volumeBar.progress
            prefs.animationStyle      = animSpinner.selectedItemPosition
            Toast.makeText(this, "Gespeichert – App neu starten", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
