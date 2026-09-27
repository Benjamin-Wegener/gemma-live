#!/usr/bin/env python3
"""
Multi-turn Dynamic Dialogue Test Harness
========================================
Mac (Thorsten via Piper TTS + Qwen 3.6 on localhost:1234) <---> Pixel 9a (Gemma Live App)

Verlauf:
1. Startet die App und wartet, bis Gemma & Sisa-Stimme bereit sind.
2. Qwen erzeugt Thorstens nächsten Satz.
3. Piper (Thorsten) wandelt Text in WAV um und spielt ihn per afplay ab.
4. Skript lauscht auf adb logcat und wartet, bis Gemma geantwortet und fertig gesprochen hat.
5. Gemmas Antwort geht zurück an Qwen, um das Gespräch fortzuführen (Multi-Turn).

Nutzung:
python3 run_thorsten_dialog.py [--turns 5] [--qwen-url http://localhost:1234/v1]
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import wave
from datetime import datetime, timezone
from pathlib import Path

try:
    import requests
except ImportError:
    print("ERROR: requests library not installed. Run: pip install requests")
    sys.exit(1)

PIPER_BIN = "/Users/user/.local/share/uv/python/cpython-3.12.14-macos-aarch64-none/bin/piper"
THORSTEN_MODEL = "/Users/user/Gemma-Live/de_DE-thorsten-medium.onnx"
PKG = "com.sisa.app.live"
ACT = f"{PKG}/com.sisa.app.ui.MainActivity"
RESULTS_DIR = Path(__file__).with_name("dialog_runs")

def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")

QWEN_SYSTEM_PROMPT = """\
Du bist Thorsten, ein Gesprächspartner im Dialog mit der KI-Assistentin Gemma.
Antworte immer nur mit EINEM kurzen deutschen Satz (max. 15 Wörter), den man flüssig vorlesen kann.
Keine Anführungszeichen, kein Markdown, keine Sonderzeichen.
Reagiere direkt auf das, was Gemma gesagt hat, und halte das Gespräch natürlich am Laufen.
"""

class ThorstenTTS:
    def __init__(self, piper_bin=PIPER_BIN, model_path=THORSTEN_MODEL):
        self.piper_bin = piper_bin
        self.model_path = model_path

    def speak(self, text: str, wav_out: str):
        print(f"\n🗣️ [Thorsten / Mac]: \"{text}\"")
        # Synthesize WAV using Piper
        proc = subprocess.Popen(
            [self.piper_bin, "--model", self.model_path, "--output_file", wav_out],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        proc.communicate(input=text.encode("utf-8"), timeout=30)

        # Play audio via Mac speaker into Pixel mic
        with wave.open(wav_out, "rb") as wav:
            duration_ms = round(wav.getnframes() * 1000 / wav.getframerate())
        started_at = utc_now()
        started_monotonic = time.monotonic()
        subprocess.run(["afplay", wav_out], timeout=60)
        return {
            "text": text,
            "wav": wav_out,
            "duration_ms": duration_ms,
            "started_at": started_at,
            "ended_at": utc_now(),
            "playback_elapsed_ms": round((time.monotonic() - started_monotonic) * 1000),
        }

class QwenAgent:
    def __init__(self, base_url: str):
        self.base_url = base_url
        self.model = "qwen3.6-35b-a3b"
        self.messages = [{"role": "system", "content": QWEN_SYSTEM_PROMPT}]

    def generate_reply(self, gemma_response: str = None) -> str:
        if gemma_response:
            self.messages.append({"role": "user", "content": f"Gemma hat gesagt: \"{gemma_response}\""})
        else:
            self.messages.append({"role": "user", "content": "Starte das Gespräch mit einer freundlichen Begrüßung und einer Frage."})

        try:
            r = requests.post(
                f"{self.base_url}/chat/completions",
                json={
                    "model": self.model,
                    "messages": self.messages,
                    "temperature": 0.4,
                    "max_tokens": 128,
                    "chat_template_kwargs": {"enable_thinking": False}
                },
                timeout=30
            )
            r.raise_for_status()
            data = r.json()
            reply = data["choices"][0]["message"]["content"].strip().strip('"\'')
            reply = re.sub(r'\*+', '', reply).strip()
            self.messages.append({"role": "assistant", "content": reply})
            return reply
        except Exception as e:
            print(f"❌ Qwen Fehler: {e}")
            return "Hallo Gemma, wie geht es dir?"

def wait_for_app_ready():
    print("📱 Starte App und warte auf Modelle...")
    subprocess.run(["adb", "shell", "am", "force-stop", PKG])
    subprocess.run(["adb", "logcat", "-c"])
    time.sleep(1)
    subprocess.run(["adb", "shell", "am", "start", "-n", ACT], stdout=subprocess.DEVNULL)

    for i in range(90):
        log = subprocess.run(["adb", "logcat", "-d"], capture_output=True, text=True, errors="replace").stdout
        if "LocalGemma bereit" in log and "Sisa-Stimme bereit" in log:
            print(f"✅ Modelle bereit nach {i+1}s!")
            return True
        time.sleep(1)
    print("❌ Fehler: Modelle wurden nicht innerhalb von 90s geladen.")
    return False

def wait_for_gemma_response(timeout=35):
    """Sammelt alle für einen Turn relevanten App-Ereignisse und ihre Zeitachsen."""
    print("👂 Warte auf Gemmas Antwort...")
    start_time = time.time()
    stt_text = ""
    gemma_text = ""
    context_usage = None
    events = []
    first_token_at = None
    playback_complete_at = None

    proc = subprocess.Popen(["adb", "logcat", "-v", "raw"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")

    try:
        while time.time() - start_time < timeout:
            line = proc.stdout.readline()
            if not line:
                continue

            event = {"at": utc_now(), "raw": line.strip()}
            if "BENCH stt_final" in line:
                m = re.search(r'text="([^"]*)"', line)
                if m:
                    stt_text = m.group(1)
                    event.update({"kind": "stt_final", "text": stt_text})
                    print(f"   🎯 Gemma hat verstanden: \"{stt_text}\"")
            elif "BENCH llm_first_token" in line:
                first_token_at = event["at"]
                event["kind"] = "llm_first_token"
            elif "BENCH turn_completed" in line:
                m = re.search(r'reply="(.*)"', line)
                gemma_text = m.group(1) if m else ""
                event.update({"kind": "gemma_reply", "text": gemma_text})
                print(f"   🤖 [Gemma / Pixel]: \"{gemma_text}\"")
            elif "BENCH context_usage" in line:
                m = re.search(r'used=(\d+) max=(\d+) percent=(\d+)', line)
                if m:
                    context_usage = {"used": int(m.group(1)), "max": int(m.group(2)), "percent": int(m.group(3))}
                    event.update({"kind": "context_usage", **context_usage})
                    print(f"   🧠 Kontext: {context_usage['used']}/{context_usage['max']} ({context_usage['percent']}%)")
            elif "BENCH playback_complete" in line:
                playback_complete_at = event["at"]
                event["kind"] = "playback_complete"
            elif "BENCH echo_drop" in line:
                event["kind"] = "echo_drop"
            else:
                continue
            events.append(event)

            if event.get("kind") == "playback_complete" and gemma_text:
                print("   ✅ Gemma hat fertig gesprochen!")
                break
    finally:
        proc.terminate()

    # Small pause for echo tail
    time.sleep(1.0)
    return {
        "recognized_text": stt_text or None,
        "gemma_reply": gemma_text or None,
        "context_usage": context_usage,
        "first_token_at": first_token_at,
        "playback_complete_at": playback_complete_at,
        "timed_out": playback_complete_at is None,
        "events": events,
    }

def write_report(report):
    RESULTS_DIR.mkdir(exist_ok=True)
    path = RESULTS_DIR / f"dialog_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n📄 Prüfprotokoll: {path}")
    print("\n=== Gesprächsprotokoll ===")
    for turn in report["turns"]:
        print(f"{turn['turn']:02d}  Mac:    {turn['mac']['text']}")
        print(f"    erkannt: {turn['pixel']['recognized_text'] or '—'}")
        print(f"    Gemma:  {turn['pixel']['gemma_reply'] or '—'}")
        context = turn["pixel"]["context_usage"]
        context_label = (
            f"{context['used']}/{context['max']} ({context['percent']}%)"
            if context else "—"
        )
        print(f"    Kontext: {context_label}")

def main():
    parser = argparse.ArgumentParser(description="Multi-turn Dialog Test Harness")
    parser.add_argument("--turns", type=int, default=5, help="Anzahl der Dialog-Turns (default: 5)")
    parser.add_argument("--qwen-url", type=str, default="http://localhost:1234/v1", help="Qwen API URL")
    args = parser.parse_args()

    if not wait_for_app_ready():
        sys.exit(1)

    tts = ThorstenTTS()
    qwen = QwenAgent(base_url=args.qwen_url)

    tmp_dir = tempfile.mkdtemp()
    print(f"\n🚀 Starte Dialog-Test über {args.turns} Turns...")

    report = {"started_at": utc_now(), "turns": []}
    last_gemma_text = None
    for turn in range(1, args.turns + 1):
        print(f"\n--- Turn {turn}/{args.turns} ---")
        thorsten_text = qwen.generate_reply(last_gemma_text)

        wav_file = os.path.join(tmp_dir, f"turn_{turn}.wav")
        # Jeder Turn bekommt einen frischen Logcat-Puffer. Ohne das liest der
        # nachfolgende Monitor bereits abgeschlossene Playback-Ereignisse aus
        # dem vorherigen Turn und ordnet sie fälschlich dem neuen Satz zu.
        subprocess.run(["adb", "logcat", "-c"], check=True)
        mac_turn = tts.speak(thorsten_text, wav_file)
        pixel_turn = wait_for_gemma_response()
        report["turns"].append({"turn": turn, "mac": mac_turn, "pixel": pixel_turn})
        last_gemma_text = pixel_turn["gemma_reply"]

    print("\n🎉 Dialog-Test beendet!")
    report["finished_at"] = utc_now()
    write_report(report)

if __name__ == "__main__":
    main()
