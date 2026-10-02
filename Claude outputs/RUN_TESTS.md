# 🚀 Luftweg-Tests ausführen

**Vorbedingung**: Pixel 9a läuft, WLAN-adb am Leben (192.168.178.160:33685)

## Schritt 1: Alte Prozesse clearen
```bash
pkill -f llama-server
pkill -f llama.cpp
# Falls noch Python-Prozesse laufen:
ps aux | grep 03_luftweg
```

## Schritt 2: Probe-Run (1 Turn)
```bash
cd /Users/user/Gemma-Live/local-test
python3 03_luftweg_dialog.py --turns 1 --device 192.168.178.160:33685
```

### Erwarteter Output:
```
[1/5] llama-server prüfen (http://127.0.0.1:8080/v1/chat/completions) ...
[2/5] App-Status prüfen ...
  State=LISTENING — App läuft bereits ...
[3/5] Warte auf Gemma-Engine ...
  Gemma hört zu.

[4/5] Probe-Request (Server Health) ...
  ✓ Server OK: 'Hallo ...'

===== Turn 1 =====
  Thorsten (E2B): "Guten Tag, Gemma, wie geht es dir heute?"
  logcat-State: LISTENING — warte auf LISTENING ...
  LISTENING bestätigt — afplay (thorsten_turn1.wav) ...
  Gemma: "Mir geht es gut, danke der Nachfrage. Wie kann ich dir helfen?" (8.2s)
  → warte auf Echo-Tail (2s) ...

Protokoll: /Users/user/Gemma-Live/local-test/out/dialog_20261002_144530.json

[NOTIZ] Bekannter App-Befund ...
```

## Schritt 3: Logs inspizieren
```bash
# Letzte Dialog-JSON
cat local-test/out/dialog_*.json | tail -20

# Oder formatiert:
python3 << 'EOF'
import json
import glob
from pathlib import Path

latest = sorted(glob.glob("local-test/out/dialog_*.json"))[-1]
data = json.load(open(latest))
print(f"File: {latest}")
print(f"Device: {data['device']}")
print(f"Turns: {len(data['turns'])}")
for t in data['turns']:
    skipped = t.get('skipped', 'N/A')
    status = '✗ SKIP' if skipped else '✓ OK'
    print(f"  Turn {t['turn']}: {status} | thorsten='{t.get('thorsten_text', 'N/A')[:30]}...' | reply='{t.get('gemma_reply', 'N/A')[:30]}...' | playback={t.get('playback_done')}")
EOF
```

## Schritt 4: 2-Turn Dialog (wenn Probe OK)
```bash
python3 03_luftweg_dialog.py --turns 2 --device 192.168.178.160:33685
```

**Warnung**: Kann 4–5 min dauern (LLM-Inference + TTS + Audio-I/O).

## Troubleshooting

### ❌ Server antwortet nicht (leere Probe)
```
[4/5] Probe-Request ...
  FEHLER: Server gab leere/zu kurze Probe zurück: ''
  → Altprozess killen, Server neu starten ...
  → Neustart Server ...
  [server] lademeldung ...
  ✓ Server OK: 'Hallo ...'
```
→ Ist **erwartet & handled**. Skript startet neu.

### ❌ "FEHLER: Gemma wurde nicht bereit (kein LISTENING)"
```bash
# Prüfe App auf Pixel:
adb -s 192.168.178.160:33685 shell am start -n com.sisa.app.live/com.sisa.app.ui.MainActivity

# Oder: Force-Restart
adb -s 192.168.178.160:33685 shell am force-stop com.sisa.app.live
sleep 2
adb -s 192.168.178.160:33685 shell am start -n com.sisa.app.live/com.sisa.app.ui.MainActivity
```

### ❌ WLAN-adb verloren ("Workspace still downloading" beim device_bash)
```bash
# Workspace auf Mac wird noch aufgebaut — warte 2 min, retry dann device_bash-Kommand
```

### ⚠️ Pixel 9a hört Umgebungsgeräusche (VAD-Rauschen)
→ Stille/ruhige Umgebung empfohlen, oder Turn wird als Echo gedroppt.
→ Für stabile Läufe `--turns 1` und Ruhe während playback.

## Erfolgs-Kriterien

✅ **Probe-Request**: Server gibt nicht-leere Antwort  
✅ **Turn 1**: `thorsten_text ≠ ""`, `gemma_reply ≠ null`, `playback_done: true`  
✅ **Turn 2** (bei `--turns 2`): Ähnlich erfolgreich  
✅ **Logs**: `out/dialog_*.json` zeigt alle Felder gefüllt  
✅ **App-Notiz**: Tag-Sanitizing-Issue dokumentiert  

## Nach erfolgreichem Test

**NICHT committen!** `local-test/` bleibt lokal:
```bash
git status  # sollte nur .gitignore zeigen
git check-ignore -v local-test/  # muss `/local-test/` melden
```

Falls Fixes nötig sind, edite in Cloud neu und commit `03_luftweg_dialog.py` lokal — nie pushen.

---
**Bereit? Dann ab zur Terminal! 🎙️**
