# DashVoice

Native Kiosk-Voice-App fürs Wand-Tablet (HUAWEI MatePad DBY-W09). Open-Source-Nachbau des
proprietären `dash-voice` als **Wyoming-`assist_satellite`** mit On-Device-Wake-Word und
Lovelace-Dashboard im Kiosk-Modus. Thin Client: STT/Intent/TTS laufen serverseitig in Home
Assistant.

## Status (MVP-Scaffold)

| Bereich | Stand |
|---|---|
| Wyoming-Satellit (TCP-Server :10700, Describe/Info, run-pipeline, Audio-Streaming, TTS-Empfang) | implementiert, **gegen Live-HA zu verifizieren** |
| Mikro-Capture (16 kHz PCM), Foreground-Service + Wakelock | implementiert |
| TTS-Playback (AudioTrack) | implementiert |
| Kiosk-WebView + Screensaver + Tap-to-Talk | implementiert |
| On-Device Wake Word (openWakeWord/ONNX) | Code vorhanden, **Modelle + Tuning fehlen** (WP2) |
| Config-Screen, Boot-Autostart | implementiert |

> ⚠️ Das Projekt kompiliert zu einer Debug-APK, ist aber noch **nicht auf dem Gerät getestet**.
> Wyoming-Event-Reihenfolge und Wake-Word-Erkennung müssen am MatePad validiert werden.

## Bauen

Toolchain liegt unter `~/android-buildtools` (JDK 17, Gradle 8.7) und `~/Android/Sdk`.

```bash
source ~/android-buildtools/env.sh
cd ~/pq_dash_voice
./gradlew assembleDebug
# Artefakt: app/build/outputs/apk/debug/app-debug.apk
```

## Aufs MatePad bringen

```bash
source ~/android-buildtools/env.sh
adb connect 192.168.178.188:5555      # ADB-over-WLAN am Tablet aktivieren
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Wake Word aktivieren (WP2)

openWakeWord-Modelle nach `app/src/main/assets/openwakeword/` legen:
`melspectrogram.onnx`, `embedding_model.onnx`, `ok_nabu.onnx`. Ohne Modelle läuft die App im
Tap-to-Talk-Modus (Mikro-Button unten rechts).

## In Home Assistant einbinden

Einstellungen → Geräte & Dienste → Integration „Wyoming Protocol" → Host = Tablet-IP, Port 10700.
Der Satellit erscheint als `assist_satellite`.

## KI-Video-Avatar

Animierter Avatar als Overlay über Dashboard und Screensaver. Er erscheint, sobald **irgendein**
`assist_satellite` in Home Assistant aktiv ist, und blendet nach einem Nachlauf (Standard 4 s)
an einer Clipgrenze wieder aus.

**Clips** liegen auf dem HA-Server unter `/config/www/avatar/` (→ `<HA>/local/avatar/`, Pfad in
den Einstellungen änderbar): `home.png` (Poster), `idle_1.mp4`, `idle_2.mp4`, `listening_1.mp4`,
`thinking_1.mp4`, `speaking_a_1.mp4` … `speaking_c_1.mp4`, `happy_1.mp4`, `alert_1.mp4`,
`look_left_1.mp4`, `look_right_1.mp4`, `sleep_in_1.mp4`, `sleep_1.mp4`, `wake_up_1.mp4`.
Weitere Varianten `<szene>_<n>.mp4` werden automatisch gefunden; neue Szenen-Basisnamen oder
Dateinamen lassen sich unter „Weitere Szenen/Clips“ ergänzen. Zur Szene `speaking` gehören alle
`speaking_<buchstabe>_<n>.mp4`.

Die App lädt alle Clips beim Start in den App-Speicher (`files/avatar/`) und prüft sie danach nur
noch per ETag/Last-Modified (alle 6 h und nach Änderung der Einstellungen). Abgespielt wird
ausschließlich lokal.

**Zustände** (HA-WebSocket, `subscribe_entities`): responding → `speaking`, processing → `thinking`,
listening → `listening`, idle → `idle`. Bei mehreren Satelliten gilt die höchste Priorität.

**HA-Helfer** (Namen in den Einstellungen änderbar):

```yaml
input_boolean:
  avatar_nacht:
    name: Avatar Nachtmodus      # on + alles idle → sleep_in, dann sleep im Loop
input_text:
  avatar_clip:
    name: Avatar Clip            # "<szene> <zeitstempel>" → Szene einmal abspielen
    max: 100
```

One-Shot auslösen (nur wirksam, wenn alles idle und kein Nachtmodus):

```yaml
action: input_text.set_value
target:
  entity_id: input_text.avatar_clip
data:
  value: "happy {{ now().timestamp() }}"
```

**Lebendigkeit:** Im idle-Loop mischt der Avatar zufällig Bewegungen (`look_left`, `look_right`,
`happy`, in den Einstellungen änderbar) unter die idle-Clips: nie zwei Bewegungen hintereinander,
nie dieselbe Bewegung zweimal in Folge, Häufigkeit einstellbar (aus/selten/normal/oft).
Zustandsclips (listening, thinking, speaking) folgen dem Satellitenzustand sofort per Crossfade –
bei ~10 s langen Clips würden kurze Zustände sonst übersprungen und der Mund würde nach Ende der
Antwort weitersprechen. Der wahrscheinlichste Folgeclip wird im zweiten Player vorgeladen.

**Übergänge:** Alle Wach-Clips beginnen und enden auf dem Home-Frame. Der nächste Clip ist
deshalb immer schon im ExoPlayer vorgeladen und folgt gapless an der Clipgrenze (harter Schnitt,
das Clipende wird um 1 Frame gekürzt, damit der Home-Frame nicht doppelt steht). Nur listening
und wake_up wechseln bei mehr als 600 ms Restlaufzeit sofort per kurzem Crossfade über einen
zweiten, bereits vorbereiteten Player. Ist das Overlay ausgeblendet, werden beide Player
freigegeben.
