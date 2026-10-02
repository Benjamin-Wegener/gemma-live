#!/bin/bash
# Gemma-Live Luftweg-Dialog Tests
# Führe dieses Skript lokal auf macOS aus: bash run_luftweg_tests.sh

set -e

PROJECT_DIR="/Users/user/Gemma-Live/local-test"
cd "$PROJECT_DIR" || { echo "ERROR: $PROJECT_DIR nicht gefunden"; exit 1; }

echo "🚀 Gemma-Live Luftweg-Tests"
echo "============================"
echo ""

# 1. Alte Prozesse killen
echo "[1/4] Alte Prozesse killen ..."
pkill -f llama-server 2>/dev/null || true
pkill -f "python.*03_luftweg" 2>/dev/null || true
sleep 2

# 2. Probe-Run (1 Turn)
echo "[2/4] Starte Probe-Run (1 Turn, ~4 min) ..."
echo "  Command: python3 03_luftweg_dialog.py --turns 1"
python3 03_luftweg_dialog.py --turns 1 --device 192.168.178.160:33685

# 3. Logs prüfen
echo ""
echo "[3/4] Logs prüfen ..."
LATEST=$(ls -t out/dialog_*.json 2>/dev/null | head -1)
if [ -n "$LATEST" ]; then
  echo "  File: $LATEST"
  python3 << 'PYEOF'
import json
import sys
from pathlib import Path

latest = sorted(Path('out').glob('dialog_*.json'))[-1]
data = json.load(open(latest))
print(f"  Device: {data['device']}")
print(f"  Turns: {len(data['turns'])}")
for t in data['turns']:
    skipped = t.get('skipped')
    if skipped:
        print(f"    Turn {t['turn']}: ✗ SKIP ({skipped})")
    else:
        thorsten = t.get('thorsten_text', '')[:40]
        gemma = t.get('gemma_reply', '')[:40] if t.get('gemma_reply') else 'null'
        playback = "✓" if t.get('playback_done') else "✗"
        print(f"    Turn {t['turn']}: thorsten='{thorsten}...' | gemma='{gemma}...' | playback={playback}")
PYEOF
else
  echo "  ERROR: Kein dialog_*.json gefunden"
  exit 1
fi

# 4. Erfolgs-Meldung
echo ""
echo "[4/4] ✅ Tests abgeschlossen!"
echo ""
echo "Ergebnis in: $LATEST"
echo ""
echo "Für 2-Turn Dialog, fahre:"
echo "  python3 03_luftweg_dialog.py --turns 2"
