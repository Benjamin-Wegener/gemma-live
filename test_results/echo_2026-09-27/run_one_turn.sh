#!/usr/bin/env bash
# Ein-Satz-Test gemaess Projekt-Regeln: App starten, auf Modelle warten,
# EINEN Satz sprechen, Logcat pruefen. Kein Auto-Loop.
# usage: run_one_turn.sh <wav> [wartezeit_nach_antwort_s]
set -euo pipefail
WAV="$1"
WAIT="${2:-18}"
PKG=com.sisa.app.live
ACT=$PKG/com.sisa.app.ui.MainActivity

adb shell am force-stop $PKG
adb logcat -c
sleep 1
adb shell am start -n $ACT >/dev/null

# Auf beide Modelle warten: Gemma (LLM) und Sisa-Stimme (TTS-Engine).
for i in $(seq 1 90); do
  if adb logcat -d 2>/dev/null | grep -q "LocalGemma bereit" &&
     adb logcat -d 2>/dev/null | grep -q "Sisa-Stimme bereit"; then
    echo "modelle bereit nach ${i}s"
    break
  fi
  sleep 1
done
if ! adb logcat -d | grep -q "Sisa-Stimme bereit"; then
  echo "FEHLER: TTS-Modell nicht geladen"; exit 1
fi

adb logcat -c
echo "frage_utc=$(date -u +%H:%M:%S.%3N)"
afplay "$WAV"
sleep "$WAIT"
adb logcat -d | grep -E "BENCH (speech_started|echo_drop|stt_final|llm_first_token|tts_chunk|playback_marker_set|playback_complete|barge)|playback_complete_callback|VAD Speech"
