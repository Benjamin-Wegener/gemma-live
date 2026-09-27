#!/bin/zsh
# SIS-Pflege für Doofe :D
# Baut, installiert, startet und prüft danach alles durch:
#   1. Gerät da?  2. Build ok?  3. Installiert?  4. App lebt (kein Crash)?
#   5. Mikrofon-Permission?  6. Modelle in Downloads/SIS_Models?
#   7. Onboarding-Stand (Intro/Berechtigung/Downloads/Setting)?
#
# Benutzung: ./install.sh
set -u
set -o pipefail # Build-Fehler nicht hinter tail verstecken

export JAVA_HOME=/opt/brew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/local/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

cd "$(dirname "$0")"
PKG="com.sisa.app"
FAIL=0

ok()   { echo "  ✅ $1"; }
bad()  { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
info() { echo "  ➖ $1"; }

echo "=== [1/6] Gerät ==="
adb start-server >/dev/null 2>&1
adb wait-for-device
DEV=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
if [[ -z "$DEV" ]]; then
  bad "Kein Gerät im device-Modus (USB-Debugging an? Kabel dran?)"
  exit 1
fi
MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ok "Gerät: $MODEL ($DEV)"

echo "=== [2/6] Build ==="
adb logcat -c 2>/dev/null || true
if ./gradlew installDebug 2>&1 | tail -3; then
  ok "Build + Install erfolgreich"
else
  bad "Build/Install fehlgeschlagen (siehe oben)"
  exit 1
fi

echo "=== [3/6] Start (frischer Prozess) ==="
adb shell am force-stop $PKG
adb shell am start -n $PKG/.ui.MainActivity 2>&1 | tr -d '\r'
sleep 10

echo "=== [4/6] Lebt die App? (Crash-Check) ==="
PID=$(adb shell pidof $PKG 2>/dev/null | tr -d '\r')
if [[ -n "$PID" ]]; then
  ok "App läuft (PID $PID)"
else
  bad "App-Prozess nicht gefunden — vermutlich abgestürzt"
fi
CRASH=$(adb logcat -d -s AndroidRuntime:E 2>/dev/null | grep -i -m3 -E "fatal|FATAL" || true)
if [[ -z "$CRASH" ]]; then
  ok "Kein Fatal-Crash in logcat"
else
  bad "Crash in logcat:"
  echo "$CRASH"
fi

echo "=== [5/6] Mikrofon-Berechtigung ==="
PERM=$(adb shell dumpsys package $PKG 2>/dev/null | grep -m1 "RECORD_AUDIO.*granted" | tr -d '\r' || true)
if echo "$PERM" | grep -q "granted=true"; then
  ok "RECORD_AUDIO erteilt"
elif [[ -n "$PERM" ]]; then
  info "RECORD_AUDIO noch nicht erteilt (Onboarding-Schritt 2) — am Telefon freigeben"
else
  info "Permission-Status unklar, Dump prüfen"
fi

echo "=== [6/6] Modelle + Onboarding-Stand ==="
echo "  -- Downloads/SIS_Models (öffentlich, Scoped Storage) --"
LS=$(adb shell ls -la /sdcard/Download/SIS_Models/ 2>&1 | tr -d '\r' || true)
echo "$LS" | sed 's/^/  /'
echo "$LS" | grep -q "gemma-4-E2B-it-gpu.litertlm" \
  && ok "Gemma-GPU in Downloads gefunden" \
  || info "Gemma-GPU fehlt in Downloads (wird im Onboarding geladen)"

echo "  -- App-Speicher (Inference, CRC-geprüft) --"
PRIV=$(adb shell ls -la /sdcard/Android/data/$PKG/files/models/ 2>&1 | tr -d '\r' || true)
echo "$PRIV" | sed 's/^/  /'
echo "$PRIV" | grep -q "de_DE-kerstin-low.onnx" \
  && ok "Sisa-Stimme (Kerstin) einsatzbereit (aus APK-Bundle entpackt)" \
  || info "Sisa-Stimme noch nicht entpackt (passiert beim 1. Sprechen automatisch)"

echo "  -- Onboarding-Fortschritt (sis_prefs) --"
PREFS=$(adb shell "run-as $PKG cat shared_prefs/sis_prefs.xml" 2>&1 | tr -d '\r' || true)
if echo "$PREFS" | grep -q "has_seen_intro_v1.*true"; then
  ok "Intro gesehen"
else
  info "Intro noch offen (Schritt 1)"
fi
GEMMA_PRIV=$(adb shell ls -la /sdcard/Android/data/$PKG/files/models/gemma-4-E2B-it-gpu.litertlm 2>&1 | tr -d '\r' || true)
if echo "$GEMMA_PRIV" | grep -q "gemma-4-E2B-it-gpu.litertlm"; then
  ok "Gemma inferenzbereit (privater App-Speicher)"
else
  info "Gemma noch nicht im App-Speicher (Onboarding-Download Pflicht, kein Überspringen)"
fi

echo ""
if [[ $FAIL -eq 0 ]]; then
  echo "🎉 SIS FÜR DOOFE: ALLES OK — Intro → Berechtigung → Downloads → App läuft."
else
  echo "⚠️  $FAIL Prüfung(en) rot — oben nach ❌ suchen. Voll-Log: adb logcat -s AndroidRuntime:E *:F"
  exit 1
fi
