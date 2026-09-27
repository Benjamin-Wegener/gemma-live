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
        subprocess.run(["afplay", wav_out], timeout=60)

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

def wait_for_gemma_response(timeout=25):
    """Liest Logcat und wartet, bis Gemma geantwortet und ihr Audio beendet hat."""
    print("👂 Warte auf Gemmas Antwort...")
    start_time = time.time()
    stt_text = ""
    gemma_text = ""
    got_stt = False
    got_complete = False

    proc = subprocess.Popen(["adb", "logcat", "-v", "raw"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")

    try:
        while time.time() - start_time < timeout:
            line = proc.stdout.readline()
            if not line:
                continue

            # 1. STT text caught by Gemma
            if "BENCH stt_final" in line:
                m = re.search(r'text="([^"]*)"', line)
                if m:
                    stt_text = m.group(1)
                    got_stt = True
                    print(f"   🎯 Gemma hat verstanden: \"{stt_text}\"")

            # 2. Gemma response / audio playback complete
            if "playback_complete" in line or "playback_complete_callback" in line:
                got_complete = True
                print("   ✅ Gemma hat fertig gesprochen!")
                break

            # 3. Last AI response text log
            if "Using on-device LocalGemmaAssistant" in line or "BENCH llm_first_token" in line:
                pass
    finally:
        proc.terminate()

    # Small pause for echo tail
    time.sleep(1.0)
    return stt_text if got_stt else "Wie bitte?"

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

    last_gemma_text = None
    for turn in range(1, args.turns + 1):
        print(f"\n--- Turn {turn}/{args.turns} ---")
        thorsten_text = qwen.generate_reply(last_gemma_text)

        wav_file = os.path.join(tmp_dir, f"turn_{turn}.wav")
        tts.speak(thorsten_text, wav_file)

        last_gemma_text = wait_for_gemma_response()

    print("\n🎉 Dialog-Test beendet!")

if __name__ == "__main__":
    main()
