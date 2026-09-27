# Außen-LLM ↔ Sisa-App (Emulator) — Betrieb

Gespräch zweier KIs: App im Emu (E2B-Gehirn via llama-server, Piper-Stimme Kerstin
on-device) gegen Außen-Partner auf dem Mac (gleicher Server, andere Persona +
sherpa-STT + Piper-Kerstin). Bit-perfekt über TCP, kein Mikrofon nötig.

## Voraussetzungen (einmalig)

- Emulator läuft: `emulator -avd Pixel_7_Live_Demo ...` → `emulator-5554`
- Benchmark-APK installiert (TcpAudioSource + Server-Fallback):
  `./gradlew assembleBenchmark` →
  `adb -s emulator-5554 install -r app/build/outputs/apk/benchmark/app-benchmark.apk`
- Port-Weiterleitung: `adb -s emulator-5554 forward tcp:4567 tcp:4567`
- Live-Log (für Sync): `adb -s emulator-5554 logcat -v time -s BENCH:I LiveMode:I \
  E2BAIService:I SisaVoice:I LocalGemma:I > /tmp/app_live.log`
- llama-server (E2B, ohne Reasoning):
  `llama-server -m /Users/user/models/gguf/gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf \
  --port 8080 -c 4096 -t 8 --n-gpu-layers 99 --reasoning off`
- Modelle außen: `tools/outside_llm/models/sherpa-onnx-whisper-tiny/` (STT),
  Piper Kerstin direkt aus `app/src/main/assets/` (TTS).

## Benutzung (Lautsprecher bleibt aus — alles stumm per Datei/STT)

```bash
# Ein Satz von außen sprechen (Piper -> TCP-Inject + Broadcast-Inhalt):
python3 tools/outside_llm/bridge.py talk --text "Hallo Sisa, ..."

# App-Antwort abhören (neuestes tee_*.pcm pullen + STT):
python3 tools/outside_llm/bridge.py listen

# Vollautomatisches Gespräch, N Turns ab Seed-Satz:
python3 tools/outside_llm/bridge.py loop --turns 3 --seed "Hallo Sisa, ..."

# Mensch tippt (frei reden per Text, ohne Zeitverlust):
python3 tools/outside_llm/bridge.py human
```

## Wie es funktioniert

1. `outside_say`: Broadcast `ACTION_USER_INPUT` (exakter Wortlaut, App antwortet
   darauf) + Piper-WAV realtime via TCP 4567 (echtes VAD + Barge-In).
2. App: Silero-VAD → llama-server (10.0.2.2:8080, E2B) → Piper on-device →
   `tee_*.pcm` in `/sdcard/Android/data/com.sisa.app.live/files/bench/`.
3. `listen_app`: log-synchronisiert (Received → tts_chunk), pullt, transkribiert
   nur den neuen Audio-Anhang per Whisper-tiny (de).
4. `loop`: Antwort → llama-server (Außen-Persona) → nächster Turn.

## Hinweise

- `MainActivity`: VAD-Hardcode wird übersprungen, wenn <10s zuvor ein Broadcast
  kam oder App denkt/spricht (Anti-Doppel für externen Loop).
- Whisper-tiny kann nur 30s am Stück; lange Antworten werden gesliced.
  STT hat tiny-typische Wobbler (Sisa→Siesa), Inhalt bleibt verständlich.
- Emulator-Uhr geht ~5min nach; Device-Zeitstempel nicht mit Host vergleichen.
- EGL-`app_time_stats` flutet logcat (2M-Puffer) → immer mit `-s` filtern.
