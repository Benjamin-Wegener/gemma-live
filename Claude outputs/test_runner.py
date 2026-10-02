#!/usr/bin/env python3
"""
Test Runner für Gemma-Live Luftweg-Dialog
Fahre Tests lokal auf macOS oder über device_bash
"""
import json
import subprocess
import sys
import os
from pathlib import Path

# Setze GGUF_PATH für Zugriff über verschiedene Umgebungen
os.environ['GGUF_PATH'] = '/Users/user/models/gguf/gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf'

PROJECT_DIR = Path('/Users/user/Gemma-Live/local-test')

def run_tests():
    """Fahre Luftweg-Dialog Tests"""
    if not PROJECT_DIR.exists():
        print(f"❌ Projekt-Dir nicht gefunden: {PROJECT_DIR}")
        return False

    os.chdir(PROJECT_DIR)

    print("=" * 60)
    print("🎙️  Gemma-Live Luftweg-Dialog Tests")
    print("=" * 60)
    print()

    # 1. Alte Prozesse killen
    print("[1/4] Alte Prozesse killen ...")
    subprocess.run(['pkill', '-f', 'llama-server'], capture_output=True)
    subprocess.run(['pkill', '-f', 'python.*03_luftweg'], capture_output=True)

    import time
    time.sleep(2)
    print("  ✓ Prozesse gestoppt")

    # 2. Test fahren
    print()
    print("[2/4] Starte 1-Turn Dialog (ca. 4 min) ...")
    print("  >>> python3 03_luftweg_dialog.py --turns 1 --device 192.168.178.160:33685")
    print()

    try:
        result = subprocess.run(
            ['python3', '03_luftweg_dialog.py', '--turns', '1',
             '--device', '192.168.178.160:33685'],
            capture_output=False,  # Live output
            timeout=600  # 10 min max
        )

        if result.returncode != 0:
            print(f"❌ Test failed with exit code {result.returncode}")
            return False
    except subprocess.TimeoutExpired:
        print("❌ Test timeout (>10 min)")
        return False
    except Exception as e:
        print(f"❌ Error: {e}")
        return False

    # 3. Logs prüfen
    print()
    print("[3/4] Logs prüfen ...")
    out_dir = PROJECT_DIR / 'out'
    dialogs = sorted(out_dir.glob('dialog_*.json'), key=lambda p: p.stat().st_mtime, reverse=True)

    if not dialogs:
        print("  ❌ Kein dialog_*.json gefunden")
        return False

    latest = dialogs[0]
    print(f"  📄 {latest.name}")

    with open(latest) as f:
        data = json.load(f)

    print(f"  Device: {data['device']}")
    print(f"  Turns: {len(data['turns'])}")
    print()

    all_ok = True
    for t in data['turns']:
        turn_num = t['turn']
        skipped = t.get('skipped')

        if skipped:
            print(f"  ✗ Turn {turn_num}: SKIPPED ({skipped})")
            all_ok = False
        else:
            thorsten = t.get('thorsten_text', '')
            gemma = t.get('gemma_reply', '')
            playback = t.get('playback_done', False)

            status_thorsten = "✓" if thorsten and len(thorsten) > 2 else "✗"
            status_gemma = "✓" if gemma and len(gemma) > 2 else "✗"
            status_playback = "✓" if playback else "✗"

            print(f"  Turn {turn_num}:")
            print(f"    Thorsten: {status_thorsten} '{thorsten[:40]}...'")
            print(f"    Gemma:    {status_gemma} '{gemma[:40] if gemma else 'null'}...'")
            print(f"    Playback: {status_playback}")

            if not (thorsten and gemma and playback):
                all_ok = False

    # 4. Summary
    print()
    print("[4/4] ✅ Test-Run komplett!")
    print()

    if all_ok:
        print("🎉 ERFOLG: Alle Turns erfolgreich!")
        print()
        print("Nächster Schritt:")
        print("  python3 03_luftweg_dialog.py --turns 2")
        return True
    else:
        print("⚠️  Einige Turns hatten Fehler — siehe oben")
        return False

if __name__ == '__main__':
    success = run_tests()
    sys.exit(0 if success else 1)
