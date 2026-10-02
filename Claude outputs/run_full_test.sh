#!/bin/bash
# 🎙️ Sisa-App + Luftweg-Dialog Complete Test Runner
# Alles in einem: kopieren → bauen → pushen → testen

set -e

DEVICE="${1:-192.168.178.160:33685}"
TURNS="${2:-5}"
PROJECT_ROOT="/Users/user/Gemma-Live"
LOCAL_TEST="$PROJECT_ROOT/local-test"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  🎙️  Sisa App + Luftweg-Dialog Full Test"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# 1. Python-Datei kopieren
echo "[1/4] Python-Datei kopieren ..."
cp /mnt/user-data/outputs/03_luftweg_dialog.py "$LOCAL_TEST/03_luftweg_dialog.py"
echo "  ✓ 03_luftweg_dialog.py aktualisiert"
echo ""

# 2. App bauen
echo "[2/4] Sisa-App bauen (gradle build) ..."
cd "$PROJECT_ROOT"
./gradlew installDebug
echo "  ✓ App gebaut und installiert"
echo ""

# 3. App stoppen und starten
echo "[3/4] App neustarten ..."
adb -s "$DEVICE" shell am force-stop com.sisa.app.live 2>/dev/null || true
sleep 2
echo "  ✓ App gestoppt"
echo ""

# 4. Tests fahren
echo "[4/4] Luftweg-Dialog Tests fahren ($TURNS Turns) ..."
cd "$LOCAL_TEST"
bash test_luftweg.sh "$DEVICE" "$TURNS"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  ✅ Full Test Complete"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
