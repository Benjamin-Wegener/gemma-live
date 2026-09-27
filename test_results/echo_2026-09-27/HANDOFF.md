# Handoff — Gemma-Live Echo-Schutz (Stand 27.09.2026, 15:48)

## TL;DR

Der behauptete Echo-Fix (Status-Text vom 27.09.) hat **nicht funktioniert**: Marker saß 60 ms
nach Chunk-Start statt am Audioende, Echo kam an Whisper, KI antwortete auf sich selbst
(2 Minuten Endlos-Schleife, sichtbar im Mitschnitt `tee_1790516396374.pcm`).

Habe ich behoben und verifiziert: **Echo-Schleife ist weg** (Build 3, installiert + getestet).
Ein neuer Bug ist dabei aufgetaucht und ist **noch nicht behoben** — deshalb dieser Handoff.

## Projekt / Umgebung

- Projekt: `/Users/user/Gemma-Live`, kein Git-Repo (`git status` geht nicht)
- App: `com.sisa.app.live`, Activity `com.sisa.app.ui.MainActivity`
- Gerät: Pixel 9a, `58281JEBF17302`, App installiert und laufend
- LLM: `qwen3.6-35b-a3b` auf `localhost:1234` (läuft, erreichbar)
  - braucht `chat_template_kwargs: {"enable_thinking": false}`
- TTS im Gerät: sherpa-onnx Piper/Kerstin (`Sisa-Stimme`, 16 kHz)
- Test-TTS auf dem Mac: `de_DE-thorsten-medium.onnx` (Projekt-Root), `python3 -m piper`
- Build: `JAVA_HOME=/opt/brew/opt/openjdk@17 ./gradlew assembleDebug` (ca. 2 s inkrementell)
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Projekt-Regeln (wichtig)

- **Kein Auto-Loop.** Immer EIN Satz sprechen, dann Logcat prüfen.
- **Auf beide Modelle warten** vor dem Test: Logcat braucht `LocalGemma bereit` UND
  `Sisa-Stimme bereit`. Ohne das Wissen ging ein Test verloren (Modell noch nicht geladen,
  Frage kam an niemanden). Helper: `test_results/echo_2026-09-27/run_one_turn.sh`
- Test-Artefakte gehören unter `test_results/`. Alte `air_*` / `run_*` Ordner sind Müll.
- Barge-in-Test: `test_results/echo_2026-09-27/run_bargein_after_end.sh`
- Tee-Analyse: `test_results/echo_2026-09-27/analyze_tee.py <file.pcm>`

## Geänderte Dateien (alle gebaut, installiert, verifiziert)

### 1. `app/src/main/java/com/sisa/app/tts/TeeAudioTrack.kt`

Marker-Mechanik korrigiert. Vorher stand der Marker auf `head + bufferSizeInFrames`
(2886 Frames = 60 ms) — das ist nur die *Puffergröße*, nicht das Ende der geschriebenen Daten.
Ein Chunk dauert 300–800 ms, also war `isSpeaking` nach 60 ms wieder false, während der
Lautsprecher noch sprach.

- neu: `writtenFrames: Long` — zählt in `write()` die tatsächlich geschriebenen Frames
- `flush()` setzt `writtenFrames = 0` **und** den Head (Android setzt Head bei flush auf 0)
- `triggerPlaybackComplete()` setzt den Marker auf `writtenFrames` (exaktes Audioende)
- `invalidatePendingComplete()` — neuer Sprechabschnitt entwertet den Marker des Vorgängers
- `firePlaybackComplete(reason)` — feuert einmalig, räumt Marker + Safety-Timer ab
- Safety-Timer `SAFETY_MARGIN_MS = 600` — falls der Marker wegen Underrun/Pause nicht
  erreicht wird, feuert er trotzdem; sonst bliebe `isSpeaking` dauerhaft true = App taub
- neue Logs: `BENCH playback_marker_set ... pos= head= pendingFrames= pendingMs=`
  und `BENCH playback_complete ... reason=marker|safety head= written=`

### 2. `app/src/main/java/com/sisa/app/tts/SisaVoiceService.kt`

- `awaitTrackDrained()` + Sentinel `EndOfPlayback` im Playback-Channel.
  **Das war der entscheidende Fund:** `playSamples()` legt nur in den Channel, der
  Playback-Job schreibt asynchron in den Track. Der Marker wurde gesetzt, *bevor* die Daten
  überhaupt im Track lagen (`pendingFrames=0` → sofort `isSpeaking=false`). Der Sentinel
  quittiert erst, wenn alle Chunks wirklich im Track stehen. Timeout 15 s mit Warnung.
- `stop("chunk_switch")` **flusht den Track nicht mehr** (vorher: Audio mitten im Satz
  abgeschnitten). Nur `barge_in` macht noch pause/flush/play.
- Echo-Fenster nach Audioende: `ECHO_TAIL_MS = 500`.
  `markSpeakingAfterEchoTail()` setzt `audioEndedNs` und hält `isSpeaking` 500 ms länger true.
  Grund: der VAD braucht nach dem letzten Lautsprecher-Sample ~100–400 ms bis er wieder
  "Stille" meldet (Nachhall). Dieser Nachhall erzeugte Segmente, die an STT gingen.
- `beginSpeaking()` entfernt einen ausstehenden Tail-Timer (sonst würde ein neuer Chunk
  `isSpeaking` vorzeitig löschen)

### 3. `app/src/main/java/com/sisa/app/ui/MainActivity.kt`

- `segmentHadAiAudio` / `segmentBargeInAccepted` / `segmentPeak` / `segmentRmsDb`
- Echo-Drop entscheidet jetzt über den **Segmentverlauf**, nicht nur den Endzustand:
  `aiAudioInSegment = segmentHadAiAudio || sisaVoice.isSpeaking`
- Ausnahme: angenommener Barge-In wird nie verworfen
- `inEchoTail`-Zweig: Segment startet nach physischem Audioende, aber innerhalb
  `ECHO_TAIL_MS` → nur behalten wenn `loudEnough` (sonst Nachhall)
- **`BENCH echo_PASS` entfernt** (war offener Punkt 1 aus dem Status-Text), `else`-Zweig
  sauber eingerückt, `} // end else`-Kommentar weg

## Verifikation Build 3 (App frisch gestartet, EIN Satz, Modell geladen)

```
15:44:09.901 speech_started (Frage, 3214ms)
15:44:10.366 stt_final "Nähen im Miegel noch eine Zahl zwischen 1 und 10."
15:44:11.335 llm_first_token
15:44:11.517 tts_chunk_first samples=4352
15:44:11.925 tts_chunk samples=19341
15:44:13.106 playback_marker_set pos=71079 head=68256 pendingFrames=2823 pendingMs=58
15:44:13.169 playback_complete reason=marker head=71079 written=71079   ✅ exakt
15:44:13.170 playback_complete_callback -> Echo-Fenster 500ms
15:44:13.516 speech_started (1646ms)
15:44:13.516 barge_ignored reason=echo_quiet peak=19 rmsDb=-73.1 tailMs=346
15:44:13.517 echo_drop startedDuringAi=true stillSpeaking=true peak=19   ✅
```

Ergebnis: **1** `stt_final`, **1** `llm_first_token`, **kein** `stt_final` aus Echo,
**kein** zweiter `llm_first_token`, **keine** Schleife. Nachhall (peak 19 = −73 dB) wird
korrekt verworfen.

## OFFEN — der neue Bug (Priorität 1)

**Symptom (vom User gemeldet):** „Gemma hört den ersten Satz, antwortet aber bevor Thorsten
fertig ist." Im Logcat sichtbar als TTS-Chunks, die mitten im Satz verschwinden, und als
5-Sekunden-Lücke zwischen `playback_marker_set`.

**Meine Analyse (nicht mehr verifiziert, muss geprüft werden):** Das ist eine Regression,
die ich mit Fix 2 eingebaut habe. `startPlaybackLoop()` wird bei **jedem** `speak()`
neu aufgerufen, also bei jedem LLM-Chunk. `playbackJob?.cancel()` wirft dabei den alten Job
weg — und mit ihm den Channel, in dem noch Audio wartet. Das heißt: **Audio im Channel geht
still verloren.** Vorher war das egal, weil `stop()` ohnehin flushte (der Audio-Verlust war
dort gewollt). Jetzt ohne Flush ist der Channel-Verlust ein *neuer* stiller Drop-Pfad.

Belege aus dem Log (Session 15:45–15:47, pid 18284):

```
15:46:21.115 tts_chunk samples=83192 head=663168      <- 5,2 s Audio, Write blockiert
15:46:23.72  llm_chunk words=12 chars=111              <- naechster Chunk kommt
15:46:25.x   SisaVoice: Write-Ack nach 15000ms nicht erhalten — Marker wird geschaetzt
15:46:36.79  BENCH barge_flush reason=barge_in head=860928
15:46:36.79  SisaVoice: Write-Ack fehlgeschlagen: Job was cancelled   (2x)
```

Der `barge_flush` schneidet die laufende Ausgabe ab → deshalb „antwortet bevor Thorsten
fertig ist". Ursache ist vermutlich die Kombination aus neuem Channel + `barge_in`-Flush
während ein Write blockiert.

**Empfohlene Richtung:** ein einziger langlebiger Playback-Loop für die ganze Session.
`chunk_switch` setzt nur `stopRequested` (Stopp der *Generator*-Schleife) und lässt den
Playback-Job + Channel weiterlaufen — damit kann nichts im Channel verloren gehen.
`stop("barge_in")` darf den Loop dann immer noch hart abbrechen.

**Danach prüfen:** ob der `barge_in` bei `head=860928` ein echter Barge-in war oder ein
Fehltrigger (Mic-Peak beim Test war sehr niedrig: peak 17–1068, Schwellen sind
`BARGE_MIN_PEAK=9000` / `BARGE_MIN_RMS_DB=-30f`).

## Zweiter offener Punkt (Priorität 2)

**Barge-In direkt nach Antwort-Ende** — nur eingeschränkt testbar. Der Mac-Speaker kommt am
Pixel-Mic mit peak 13–281 bzw. rmsDb −49…−78 dB an, also 20–30 dB **unter** der
Barge-Schwelle. Der Testpfad ist also erreichbar, aber mit realistischer Stimme nicht
verifizierbar — es braucht entweder den Mac näher ans Gerät, höhere Lautstärke oder ein
Testsignal direkt am Mic-Pin. Logik beider Zweige ist im Code, aber der `inEchoTail &&
loudEnough`-Pfad wurde noch nie mit echter Nutzerlautstärke durchlaufen.

Letzter Messlauf (15:45, `satz2.wav` 7 ms nach `playback_complete_callback`):
Segment kam 319 ms nach Audioende, `tailMs=319`, `peak=13` → korrekt als Echo verworfen
(`echo_drop`). Verhalten ist wie gedacht, nur mit zu leiser Anregung.

## Test-Artefakte

Alle in `test_results/echo_2026-09-27/`:

- `frage1.wav`, `satz2.wav` — Test-Sätze (Piper Thorsten)
- `run_one_turn.sh` — Ein-Satz-Test mit Modell-Wartezeit
- `run_bargein_after_end.sh` — Barge-In-Test nach Audioende
- `analyze_tee.py` — Tee-Mitschnitt-Zerlegung (Chunk-Timeline, Lücken, Peaks)
- `tee_1539_build2.pcm` (108 s Endlos-Schleife), `tee_1542_build3.pcm` (136 s, gleiche Ursache)

Mitschnitte liegen auf dem Gerät unter
`/sdcard/Android/data/com.sisa.app.live/files/bench/tee_<timestamp>.pcm`.
Format: 8 Byte `elapsedRealtimeNanos`, 4 Byte `sizeInShorts`, dann PCM 16 Bit LE @ 16 kHz mono.
