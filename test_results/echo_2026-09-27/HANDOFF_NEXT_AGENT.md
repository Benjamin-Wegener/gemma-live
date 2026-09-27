# HANDOFF & PROMPT FÜR FOLGE-AGENTEN (Gemma-Live Echo & Audio-Pipeline)
**Stand:** 27. September 2026

---

## 🚀 PROMPT FÜR DEN NÄCHSTEN AGENTEN (Lies diesen Abschnitt zuerst)

```text
Du übernimmst das Projekt Gemma-Live (/Users/user/Gemma-Live) auf einem Pixel 9a (Geräte-ID: 58281JEBF17302).
Die App com.sisa.app.live bietet eine 100% lokale, on-device Full-Duplex Sprachassistentin (Sisa/Gemma).

AKTUELLES SET-UP & SYSTEM-STATUS:
1. Projekt-Umgebung & Build:
   - Pfad: /Users/user/Gemma-Live
   - Build: ./gradlew assembleDebug (oder via Gradle Tool app:assembleDebug)
   - Install: adb install -r app/build/outputs/apk/debug/app-debug.apk
   - On-Device Modell: gemma-4-E2B-it-gpu.litertlm (1,87 GB) in /sdcard/Android/data/com.sisa.app.live/files/models/
   - LiteRT-LM Version: 0.17.1 AAR (app/libs/litertlm-android-0.17.1.aar) mit GPU Backend auf Mali-G715.

2. Behobene kritische Probleme in dieser Session:
   - Echo-Schleife & AudioTrack-Marker: In TeeAudioTrack.kt & SisaVoiceService.kt behoben. isSpeaking bleibt exakt bis zum physischen Ende des Audiosignals + 500ms Echo-Tail wahr.
   - Audio-Verlust bei Chunk-Switch (Prio 1 Bug): Beendet! In SisaVoiceService.kt läuft die Playback-Schleife & Channel nun langlebig durch. stop("chunk_switch") verwirft keine anstehenden Audio-Chunks mehr.
   - Steuerzeichen-Leak (<end_of_turn>): In LocalGemmaAssistant.kt (sanitizeLlmOutput) & SisaVoiceService.kt (germanize) gefiltert. Es leaken keine Control-Tokens mehr in TTS oder UI.
   - Whisper Medium Auto-Detection: AppModelManager.kt erkennt medium-encoder.int8.onnx / medium-decoder.int8.onnx automatisch und nutzt 4 Threads.
   - Native Gemma E2B Audio-Eingabe (InputData.Audio): In EngineConfig ist audioBackend = Backend.GPU() aktiviert. InputData.Audio(pcmBytes) speist 16kHz PCM-Audio nativ ein.

3. PERFORMANCE-ANALYSE & LATENZ-UNTERSCHEIDUNG ("denkt ewig"):
   - Pfad A: Whisper ONNX STT (CPU) + Gemma LLM Text (GPU) [EMPFOHLEN FÜR LIVE-MODE]
     - Whisper STT transkribiert Sprache in ~150-200ms auf der CPU.
     - Gemma LLM generiert den ersten Antwort-Token auf der GPU in ~800ms.
     - Gesamtlatenz bis zur Sprachausgabe: ~1,0s (sub-second, echtes Live-Feeling).
   - Pfad B: Direct Gemma E2B Multimodal Audio Input (InputData.Audio)
     - Wenn rohe PCM-Bytes direkt über InputData.Audio an das Gemma E2B Backend gesendet werden, muss das Modell auf der Smartphone-GPU die kompletten Audio-Attention-Embeddings berechnen.
     - In LiteRT-LM 0.17.1 dauert die Audio-Token-Präambel auf der Mali-GPU ~8 bis 15 Sekunden ("denkt ewig").
     - EMPFEHLUNG: Für den täglichen Live-Einsatz Whisper STT als Standard-STT nutzen (sttEngineMode = "whisper"). Für reine Experimente mit nativer Gemma-Audio-Inferenz sttEngineMode = "gemma_e2b" nutzen.

4. WICHTIGE TEST-SKRIPTE:
   - Multi-Turn Dialog-Test mit Thorsten auf Mac:
     /Users/user/.local/share/uv/python/cpython-3.12.14-macos-aarch64-none/bin/python3 test_results/echo_2026-09-27/run_thorsten_dialog.py --turns 5
     (Spielt Thorsten-Stimme über Mac-Lautsprecher auf Pixel-Mic ab, prüft Logcat und führt N-Turn Dialoge via Qwen 3.6 auf localhost:1234).
   - Einzelner Turn Test:
     bash test_results/echo_2026-09-27/run_one_turn.sh test_results/echo_2026-09-27/frage1.wav

Lies das HANDOFF_NEXT_AGENT.md für Details zu allen Dateien und setze deine Arbeit darauf auf.
```

---

## 📁 ÜBERSICHT DER RELEVANTEN DATEIEN

| Datei | Pfad | Beschreibung |
|---|---|---|
| `TeeAudioTrack.kt` | `app/src/main/java/com/sisa/app/tts/TeeAudioTrack.kt` | Low-Latency FastTrack AudioTrack mit exakter `writtenFrames` Marker-Mechanik & Safety-Timer. |
| `SisaVoiceService.kt` | `app/src/main/java/com/sisa/app/tts/SisaVoiceService.kt` | Langlebige Playback-Loop, Sentinel `EndOfPlayback`, Echo-Tail (500ms), Text-Sanitizing (`germanize`). |
| `LocalGemmaAssistant.kt` | `app/src/main/java/com/sisa/app/ai/LocalGemmaAssistant.kt` | LiteRT-LM 0.17.1 Inferenz, `InputData.Audio` MTP GPU Audio, `sanitizeLlmOutput` Control-Token Filter. |
| `WhisperSttManager.kt` | `app/src/main/java/com/sisa/app/stt/WhisperSttManager.kt` | On-Device Sherpa-ONNX STT mit automatischer Whisper Medium / Tiny Erkennung. |
| `AppModelManager.kt` | `app/src/main/java/com/sisa/app/download/AppModelManager.kt` | Modell-Verwaltung, Scoped Storage Migration & `getAvailableWhisperModel()`. |
| `MainActivity.kt` | `app/src/main/java/com/sisa/app/ui/MainActivity.kt` | Full-Duplex VAD Loop, UI mit `FilterChip` Schalter für STT-Engine (`whisper` vs `gemma_e2b`), Broadcasts. |
| `run_thorsten_dialog.py` | `test_results/echo_2026-09-27/run_thorsten_dialog.py` | Automatisierter Multi-Turn Gesprächstest (Qwen 3.6 + Piper Thorsten + Logcat Monitor). |

---

## 🔧 BEFEHLE FÜR SCHNELLE ÜBERPRÜFUNG

### App bauen & installieren
```bash
# Build
./gradlew assembleDebug

# Installieren auf Pixel 9a
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Multi-Turn Dialog-Test auf dem Mac starten
```bash
/Users/user/.local/share/uv/python/cpython-3.12.14-macos-aarch64-none/bin/python3 test_results/echo_2026-09-27/run_thorsten_dialog.py --turns 5
```

### STT-Engine per Broadcast umschalten
```bash
# Whisper STT aktivieren (Fast <1s Mode):
adb shell am broadcast -a com.sisa.app.live.ACTION_SET_STT_ENGINE --es mode whisper

# Gemma E2B Direct Audio STT aktivieren (Native MTP GPU Audio Input):
adb shell am broadcast -a com.sisa.app.live.ACTION_SET_STT_ENGINE --es mode gemma_e2b
```

---

## 🎯 VERIFIZIERTE LOGCAT-BENCHMARKS

Ein erfolgreicher Turn zeigt in `adb logcat` folgende Abfolge:
1. `BENCH speech_started` (Nutzer/Thorsten beginnt zu sprechen)
2. `BENCH stt_final` (Whisper STT erkennt Text in ~150-200ms)
3. `BENCH llm_first_token` (Gemma LLM liefert ersten Token nach ~800ms)
4. `BENCH tts_chunk_first` (Piper TTS startet sofort Sprachausgabe)
5. `BENCH playback_marker_set` & `playback_complete` (Audio wird bis zum letzten Sample abgespielt)
6. `SisaVoice: playback_complete_callback -> Echo-Fenster 500ms` (Echo-Drop schützt vor eigenem Nachhall)
7. `BENCH echo_drop startedDuringAi=true` (Eigenes Echo wird verworfen, kein Selbst-Gespräch!)
