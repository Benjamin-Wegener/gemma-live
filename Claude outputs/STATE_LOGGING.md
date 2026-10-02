# State Logging in Luftweg-Dialog

**Update:** State-Events jetzt in JSON protokolliert! 📊

## Was wird geloggt?

### Vorher (alt):
```json
{
  "turns": [
    {
      "turn": 1,
      "thorsten_text": "Hallo Gemma...",
      "gemma_reply": "Hallo zurück...",
      "playback_done": true,
      "play_ms": 5230,
      "turn_ms": 12450
    }
  ]
}
```

**Problem:** Keine Visibility in State-Übergänge (wann wurde LISTENING, SPEAKING, etc.)

---

### Jetzt (NEU):
```json
{
  "turns": [
    {
      "turn": 1,
      "thorsten_text": "Hallo Gemma...",
      "gemma_reply": "Hallo zurück...",
      "playback_done": true,
      "play_ms": 5230,
      "turn_ms": 12450,
      "state_events": [
        {
          "state": "LISTENING",
          "line": "BENCH live_state ... state=LISTENING"
        },
        {
          "state": "RECOGNIZING",
          "line": "BENCH live_state ... state=RECOGNIZING"
        },
        {
          "state": "SPEAKING",
          "line": "BENCH live_state ... state=SPEAKING"
        },
        {
          "state": "LISTENING",
          "line": "BENCH live_state ... state=LISTENING"
        }
      ]
    }
  ]
}
```

**Neu:** `state_events` Array zeigt den kompletten State-Verlauf pro Turn!

---

## Debugging-Use Cases

### 1. State-Wechsel prüfen
```bash
cat out/dialog_*.json | jq '.turns[0].state_events[]'
```

Output:
```
{
  "state": "LISTENING",
  "line": "BENCH live_state ... state=LISTENING"
}
{
  "state": "RECOGNIZING",
  "line": "BENCH live_state ... state=RECOGNIZING"
}
...
```

### 2. State-Timeline visualisieren
```bash
python3 << 'EOF'
import json
data = json.load(open('out/dialog_20261002_144530.json'))
for t in data['turns']:
    print(f"\nTurn {t['turn']}:")
    if 'state_events' in t:
        for evt in t['state_events']:
            print(f"  → {evt['state']}")
    else:
        print(f"  (keine state_events)")
EOF
```

Output:
```
Turn 1:
  → LISTENING
  → RECOGNIZING
  → PROCESSING
  → SPEAKING
  → LISTENING
```

### 3. State-Abweichungen debuggen
```bash
# Wenn Expected: LISTENING → RECOGNIZING → SPEAKING → LISTENING
# Aber Actual: LISTENING → TIMEOUT → LISTENING (Sprach-nicht-erkannt?)

cat out/dialog_*.json | jq '.turns[] | {turn, state_events: (.state_events[]?.state)}'
```

---

## Was die States bedeuten

| State | Bedeutung | Normal? |
|-------|-----------|---------|
| `LISTENING` | App hört zu, wartet auf Sprache | ✓ Start/Ende pro Turn |
| `RECOGNIZING` | VAD triggerte, Audio wird erkannt | ✓ Mittig |
| `PROCESSING` | Gemma denkt (LLM-Inference) | ✓ Mittig |
| `SPEAKING` | Gemma antwortet per TTS | ✓ Mittig |

**Erwarteter Flow:**
```
LISTENING → (Thorsten spricht)
         → RECOGNIZING → PROCESSING → SPEAKING
         → (Thorsten schweigt)
         → LISTENING
```

---

## Probleme erkennen

### ❌ Zu viele LISTENING-Wechsel
```
LISTENING → RECOGNIZING → LISTENING → RECOGNIZING → ...
```
**Ursache:** VAD triggert falsch (Umgebungsgeräusch, Echo)

### ❌ Kein SPEAKING
```
LISTENING → RECOGNIZING → PROCESSING → LISTENING
```
**Ursache:** Gemma antwortete nicht (LLM-Fehler, leere Reply)

### ❌ Timeout
```
LISTENING (dann nichts mehr)
```
**Ursache:** Pixel-Verbindung ab, App crashte, oder Timeout überschritten

---

## Code-Änderungen

**Zeile ~328–350 in 03_luftweg_dialog.py:**
```python
# State-Timeline für Debugging: sammle alle State-Wechsel
state_events = []
for ts, line in log.events:
    m_state = re.search(r"BENCH live_state .* state=(\w+)", line)
    if m_state:
        state_events.append({
            "state": m_state.group(1),
            "line": line[:100]  # First 100 chars only
        })
if state_events:
    t["state_events"] = state_events
```

**Effekt:** Alle State-Wechsel werden pro Turn in JSON gespeichert

---

## Nächste Schritte

1. **Test fahren:**
   ```bash
   python3 03_luftweg_dialog.py --turns 1
   ```

2. **State-Events prüfen:**
   ```bash
   cat out/dialog_*.json | jq '.turns[0].state_events'
   ```

3. **Bei Problemen:**
   - Zu viele State-Wechsel? → Pixel VAD-Threshold anpassen
   - Kein SPEAKING? → LLM-Server prüfen
   - Timeout? → Pixel-Connection oder Timeout-Limits prüfen

---

**State Logging ist jetzt LIVE! 📊**
