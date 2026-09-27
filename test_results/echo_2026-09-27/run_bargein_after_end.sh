#!/usr/bin/env bash
# Barge-In-Test direkt nach dem Ende der KI-Antwort: warten bis das Audio
# physisch draussen ist (playback_complete_callback), dann SOFORT sprechen.
# usage: run_bargein_after_end.sh <frage.wav> <satz.wav> [afplay_lautstaerke] [wartezeit]
set -euo pipefail
FRAGE="$1"; SATZ="$2"; VOL="${3:-1.0}"; WAIT="${4:-15}"
PKG=com.sisa.app.live
ACT=$PKG/com.sisa.app.ui.MainActivity

adb shell am force-stop $PKG
adb logcat -c
sleep 1
adb shell am start -n $ACT >/dev/null
for i in $(seq 1 90); do
  if adb logcat -d 2>/dev/null | grep -q "LocalGemma bereit" &&
     adb logcat -d 2>/dev/null | grep -q "Sisa-Stimme bereit"; then
    echo "modelle bereit nach ${i}s"; break
  fi
  sleep 1
done
adb logcat -c
afplay "$FRAGE"

# Auf das echte Audioende warten und danach ohne Verzug den Folgesatz abspielen.
for i in $(seq 1 300); do
  if adb logcat -d 2>/dev/null | grep -q "playback_complete_callback"; then
    echo "antwort_ende_utc=$(date -u +%H:%M:%S.%3N)"
    break
  fi
  sleep 0.1
done
afplay -v "$VOL" "$SATZ" &
echo "nutzer_satz_utc=$(date -u +%H:%M:%S.%3N)"
wait
sleep "$WAIT"
adb logcat -d | grep -E "BENCH (speech_started|echo_drop|stt_final|llm_first_token|playback_marker_set|playback_complete|barge)|playback_complete_callback|VAD Speech"
