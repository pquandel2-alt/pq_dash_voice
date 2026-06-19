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
