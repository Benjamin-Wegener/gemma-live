# HANDOFF: Gemma-Live (27.09.2026)

**Projekt:** `Gemma-Live` (`/Users/user/Gemma-Live`)  
**Gerät:** Google Pixel 9a (ADB-Serial: `58281JEBF17302`)  
**App-ID:** `com.sisa.app.live` (Namespace: `com.sisa.app`)  
**Build-Status:** GRÜN (`BUILD SUCCESSFUL`, Gradle 8.9, Kotlin 2.4.20, AGP 8.6.1)

---

## 1. Vision & Kernauftrag

**Gemma-Live** ist ein autonomer, **100% On-Device** Full-Duplex Sprachassistent für Android.  
Die Interaktion erfolgt **wie bei einem natürlichen Telefonat**:
- Der Nutzer spricht einfach drauflos (kein Tastendruck nötig, VAD erkennt Sprachbeginn und -ende).
- Die Antwort erfolgt in natürlicher deutscher Sprache über die lokale Sprachausgabe.
- **Barge-In:** Fällt der Nutzer der KI ins Wort, bricht die Sprachausgabe sofort ab (< 50 ms).
- **Vollständig offline:** Keine externen Server, keine Cloud-APIs, Flugmodus-kompatibel.

---

## 2. Architektur & Komponenten

### 📱 Android-App (`com.sisa.app.live`)

1. **Audio-Erfassung & Turn-Detection:**
   - `AudioLoop.kt`: Kontinuierliches Mikrofon-Streaming (16 kHz Mono, 512 Samples / Frame) mit aktivem Hardware `AcousticEchoCanceler`.
   - `TurnDetector.kt`: ONNX Silero-VAD (`assets/vad/silero_vad.onnx`).
     - Erkennung Sprachstart: ~150 ms.
     - Erkennung Turn-Ende: 200–300 ms Sprechpause.
     - Echo-Schutz: Erkennung und Verwerfen von Lautsprecher-Bleed / Nachhall der eigenen TTS.

2. **Multimodale Inferenz (Mandatorisches Gemma 4 E2B Direct Audio):**
   - **Whisper wurde vollständig entfernt.**
   - `LocalGemmaAssistant.kt`: LiteRT-LM 0.17.1 native Engine (`gemma-4-E2B-it.litertlm` bzw. GPU-spezifisch auf Mali-G715).
   - **Direct Audio Pipeline:** Das Audiosignal wird als 16 kHz Mono WAV mit RIFF-Header direkt an `Contents.of(Content.AudioFile(...), Content.Text(...))` übergeben.
   - **Chunking (> 30s):** Da Gemma maximal 30s Audio pro Encoder-Durchlauf unterstützt, werden längere Aufnahmen automatisch in Abschnitte von $\le 30\,\text{s}$ (480.000 Samples) geschnitten und sequentiell verarbeitet.
   - **10-Runden Gedächtnis:** Eine persistente `Conversation` hält den echten KV-Cache über bis zu 10 Dialogrunden (20 Turns) aktiv, ohne dass alte Audio-Dateien erneut eingespielt werden müssen.

3. **Sprachausgabe (On-Device TTS):**
   - `SisaVoiceService.kt`: sherpa-onnx Offline-TTS mit deutschem Piper VITS Modell (`de_DE-kerstin-low.onnx`, 16 kHz, in APK-Assets integriert).
   - Streaming: Erster Chunk startet bereits ab 2–3 Wörtern des LLM.
   - `TeeAudioTrack.kt`: Resampling von 16 kHz auf native 48 kHz FastTrack-Ausgabe.

---

### 💻 Mac-Gegenstelle (Akustischer Prüfstand über die Luftstrecke)

Der Mac agiert als realer akustischer Gesprächspartner und Tester über Mikrofon und Lautsprecher:
- **Mund (Mac):** Piper TTS mit Thorsten-Stimme (`de_DE-thorsten-medium.onnx` via `piper` Binary) spielt Audio über die Mac-Lautsprecher (`afplay`) in den Raum ab.
- **Ohr (Mac):** MacBook-Mikrofon nimmt das gesamte Raumgespräch synchron auf (`ffmpeg -f avfoundation`).
- **Orchestrierung (`acoustic_duplex.py`):**
  - Spricht Satz für Satz.
  - Wartet, bis Gemma fertig gesprochen hat (Logcat-Monitoring auf `playback_complete` / `turn_completed`).
  - **Reine Luftstrecke:** Keine Injektion über TCP oder ADB — 100 % echter Raumklang.

---

## 3. Aktueller Status & Nächste Schritte

### ✅ Erledigt:
1. Whisper STT und veraltete Helfer restlos aus Codebase und Gradle entfernt.
2. E2B Direct Audio (WAV mit RIFF-Header) als alleiniger Audio-Pfad etabliert.
3. 30s-Chunking in `LocalGemmaAssistant.kt` implementiert.
4. Persistente Conversation für bis zu 10 Dialogrunden im Speicher aufgesetzt.
5. UI aufgeräumt: Textbox am unteren Rand entfernt, dezentes **`?`**-Piktogramm oben rechts in der TopAppBar platziert (öffnet bei Tap / Long-Press ein sauberes Lizenz-Overlay).
6. Repository komplett bereinigt: Veraltete Dokumente, alte Stresstest-Skripte und Submodule (`outsidevoice`) gelöscht, saubere `.gitignore` erstellt.
7. Build kompiliert fehlerfrei (`./gradlew assembleDebug`), auf Pixel 9a installiert und verifiziert.

### ⏳ Nächste Prioritäten:
1. **Turn-Detector & Audio-Buffer Feinschliff:**
   - Sicherstellen, dass bei schnellen Dialogwechseln nach der TTS-Ausgabe das Echo-Fenster (`ECHO_TAIL_MS`) nicht den Satzanfang von Thorsten als Echo verwirft.
2. **10-Runden Luftstrecken-Test:**
   - Ausführen des Stresstests via `acoustic_duplex.py` oder gezielten Einzelsätzen:
     1. Name nennen: *"Ich heiße Paul. Merk dir bitte meinen Namen!"*
     2. Nachfragen: *"Wie heiße ich?"* $\rightarrow$ Erwartung: *"Du heißt Paul."*
3. **Latenz-Optimierung:**
   - Messung von TTFT (`llm_first_token`) und TTFA (`tts_chunk_first`) im reinen E2B-Audio-Modus auf dem Pixel 9a.

---

## 4. Wichtige Pfade & Befehle

- **Projektverzeichnis:** `/Users/user/Gemma-Live`
- **Build & Install:**
  ```bash
  ./gradlew assembleDebug && adb -s 58281JEBF17302 install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **App Starten / Stoppen:**
  ```bash
  adb -s 58281JEBF17302 shell am force-stop com.sisa.app.live
  adb -s 58281JEBF17302 shell am start -n com.sisa.app.live/com.sisa.app.ui.MainActivity
  ```
- **Logcat Live-Monitor:**
  ```bash
  PID=$(adb -s 58281JEBF17302 shell pidof com.sisa.app.live)
  adb -s 58281JEBF17302 logcat --pid=$PID | grep -E "BENCH|LiveMode|LocalGemma|SisaVoice"
  ```
- **Thorsten-Sprachausgabe (Mac $\rightarrow$ Raum):**
  ```bash
  echo "Hallo Gemma!" | /Users/user/.local/share/uv/python/cpython-3.12.14-macos-aarch64-none/bin/piper --model /Users/user/Gemma-Live/de_DE-thorsten-medium.onnx --output_file /tmp/thorsten.wav && afplay /tmp/thorsten.wav
  ```
