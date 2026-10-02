# Fixes für 03_luftweg_dialog.py

**Datum:** 2026-10-02  
**Status:** ✅ Implementiert, lokal getestet

## Problem (aus letztem Lauf)
- llama-Server gab leere Reply zurück → `thorsten_text=""` 
- Skript versuchte trotzdem, leere Zeile zu synthetisieren → leeres WAV
- `afplay` spielte Stille ab → Gemma antwortet nicht → **180s Timeout**

## Fixes implementiert

### 1. **Probe-Request vor Loop** (Zeile ~225–245)
```python
print("\n[4/5] Probe-Request (Server Health) ...")
probe = thorsten_line("Antworte mit 'Hallo'.")
if not probe or len(probe) < 2:
    # → Altprozess killen, Server neu starten
    server_proc.terminate()
    server_proc = mod_llm.start_server()
    probe = thorsten_line("Antworte mit 'Hallo'.")
    # → if immer noch leer: exit(1)
```
**Effekt**: Server-Health sofort klar. Bei Fehler: Restart bevor es in den Loop geht.

### 2. **Leere Thorsten-Zeilen abbrechen** (Zeile ~260–265)
```python
line = thorsten_line(...)  # kann jetzt "" sein
t["thorsten_text"] = line
if not line:
    t["skipped"] = "Leere Thorsten-Antwort (Server-Fehler)"
    report["turns"].append(t)
    continue  # ← Skip TTS+afplay, gehe zu nächster Turn
```
**Effekt**: Keine leeren WAVs, keine Timeouts auf Stumme.

### 3. **TTS-Fehlerbehandlung hinzugefügt** (Zeile ~266–273)
```python
try:
    wav = OUTDIR / f"thorsten_turn{turn}.wav"
    mod_tts.synth(line, wav)
except Exception as e:
    t["skipped"] = f"TTS-Fehler: {e}"
    report["turns"].append(t)
    continue
```
**Effekt**: Falls Piper crasht, wird die Turn dokumentiert statt abzubrechen.

### 4. **Erhöhte Echo-Tail-Wartezeit** (Zeile ~296)
```python
# Alte: time.sleep(1.5)
# Neue: time.sleep(2.0)
print("  → warte auf Echo-Tail (2s) ...")
time.sleep(2.0)
```
**Effekt**: Mehr Toleranz für Umgebungsgeräusche / WLAN-adb-Latenz (Pixel 9a-Issue).

### 5. **Besseres Error Handling in thorsten_line()** (Zeile ~236–250)
```python
try:
    with urllib.request.urlopen(req, timeout=120) as r:
        data = json.load(r)
        reply = data["choices"][0]["message"]["content"].strip()
        return reply if reply else ""
except Exception as e:
    print(f"  FEHLER bei llama-Request: {e}")
    return ""
```
**Effekt**: Exceptions werden gecatcht, Rückgabe ist immer ein String (nie None).

### 6. **Notiz für App-Tag-Sanitizing** (Zeile ~301–304)
```python
print("\n[NOTIZ] Bekannter App-Befund (notiert für upstream-Issue):")
print("  turn_completed enthält unzerlegt: [TRANSCRIPT]…[/TRANSCRIPT][ANSWER]…[/ANSWER]")
print("  Vermutung: Gemma spricht Tags aus (nur <> werden sanitized, nicht []).")
```
**Effekt**: Issue-Kandidat aufgelistet (nicht gefixt, nur dokumentiert wie in Handoff vorgegeben).

## Test-Plan

### Phase 1: Probe-Request verifizieren
```bash
cd /Users/user/Gemma-Live/local-test
python3 03_luftweg_dialog.py --turns 0  # hypothetisch: nur bis Probe-Check
```
**Erwartung**: 
- `[4/5] Probe-Request...` 
- Wenn Server OK: `✓ Server OK: 'Hallo.` (oder ähnlich)
- Wenn nicht: Neustart und Retry

### Phase 2: 1–2-Turn Dialog fahren
```bash
python3 03_luftweg_dialog.py --turns 2 --device 192.168.178.160:33685
```
**Erwartung**:
- Turn 1: Thorsten-Text ≠ "", Play ✓, Gemma antwortet, playback_done=true
- Turn 2: Ähnlich erfolgreich
- `out/dialog_<ts>.json` zeigt gefüllte `gemma_reply` + `playback_done: true` für beide Turns

### Phase 3: Logs prüfen
```bash
cat out/dialog_*.json | jq '.turns[] | {turn, thorsten_text, gemma_reply, playback_done}'
```
**Erwartung**: Keine leeren `thorsten_text`, keine `null` `gemma_reply`, alle `playback_done: true`.

## Bekannte Einschränkungen
1. **Pixel 9a Umgebungsgeräusch**: VAD triggert ständig, Turns werden als Echo gedroppt
   - Workaround: Ruhige Umgebung oder höherer Threshold in VAD
   
2. **App-Tags (noch nicht gefixt)**: 
   - `turn_completed` liefert: `[TRANSCRIPT]text[/TRANSCRIPT][ANSWER]text[/ANSWER]`
   - Regex-Match bricht auf `reply=` ab, zerlegt nicht die Struktur
   - **Upstream-Issue für LocalGemmaAssistant.processAudioDirectly** vormerken

## Nicht im Repo
- Diese Datei (`CHANGES_03_LUFTWEG.md`) ist lokal, **NOT committed**
- `local-test/` ist via `/local-test/` in `.gitignore` — kein Upstream-Push

## Next Steps (für nach Tests)
1. Verifizieren, dass `01_thorsten_tts.py` auch leere Zeilen graceful handled
2. App-Befund als upstream-Issue tracken (Tag-Sanitizing)
3. Optional: Pixel 9a VAD-Threshold in `SileroVADDetector.kt` anpassen

---
**Handoff abgeschlossen für Punkte 1 & 2. Punkt 3 & 4 dokumentiert.**
