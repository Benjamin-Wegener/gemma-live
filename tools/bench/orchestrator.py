#!/usr/bin/env python3
"""
orchestrator.py:
Führt vollständige Closed-Loop Audio-Benchmarks durch:
1. Richtet `adb forward tcp:4567 tcp:4567` ein
2. Führt Clock-Sync durch (pre- und post-run)
3. Tailt Logcat (`adb logcat -v epoch -b main -s BENCH:*`)
4. Injiziert Quell-WAVs via TCP in 512-Sample Chunks
5. Überwacht Log-Events und triggert ggf. Barge-In nach `tts_chunk_first + delay_ms`
6. Prüft Assertions (`expect_logs`)
7. Zieht `tee_*.pcm` vom Gerät ab (`adb pull`)
8. Berechnet alle Metriken via `metrics.py` und exportiert `results.json`
"""

import argparse
import os
import re
import subprocess
import sys
import threading
import time
from typing import Dict, List, Optional
import yaml

from clock_sync import sync_clocks
from inject import inject_audio
from metrics import compute_metrics


class BenchmarkOrchestrator:
    def __init__(self, serial: str = "", port: int = 4567):
        self.serial = serial
        self.port = port
        self.adb_base = ["adb"]
        if serial:
            self.adb_base.extend(["-s", serial])
        self.logcat_proc: Optional[subprocess.Popen] = None
        self.captured_logs: List[str] = []
        self.log_events: List[Dict] = []
        self.stop_logging = threading.Event()
        self.event_callbacks = {}
        # Offset Host-Uhr -> monotone Geräteuhr, gesetzt vom Pre-Run-Clock-Sync
        # in main(). Wird für die End-to-End-Barge-In-Latenz benötigt.
        self.device_offset_ns: Optional[int] = None

    def adb_cmd(self, cmd: List[str]) -> str:
        full_cmd = self.adb_base + cmd
        return subprocess.check_output(full_cmd, text=True).strip()

    def setup_port_forward(self):
        print(f"[orchestrator] Setting up adb forward tcp:{self.port} tcp:{self.port}")
        self.adb_cmd(["forward", f"tcp:{self.port}", f"tcp:{self.port}"])

    def clear_device_bench_files(self):
        remote_bench_dir = "/sdcard/Android/data/com.sisa.app.live/files/bench"
        print(f"[orchestrator] Cleaning remote bench files at {remote_bench_dir}")
        subprocess.run(self.adb_base + ["shell", f"rm -rf {remote_bench_dir}/*"], check=False)

    def start_logcat_tail(self):
        self.stop_logging.clear()
        self.captured_logs.clear()
        self.log_events.clear()

        # Logcat leeren
        subprocess.run(self.adb_base + ["logcat", "-c"], check=False)

        cmd = self.adb_base + ["logcat", "-v", "epoch", "-s", "BENCH:I"]
        self.logcat_proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )

        def log_reader():
            bench_pattern = re.compile(r"BENCH\s+([a-zA-Z0-9_]+)\s+t_elapsed_ns=(\d+)(.*)")
            for line in self.logcat_proc.stdout:
                if self.stop_logging.is_set():
                    break
                line_str = line.strip()
                if not line_str:
                    continue
                self.captured_logs.append(line_str)
                match = bench_pattern.search(line_str)
                if match:
                    ev_name = match.group(1)
                    t_ns = int(match.group(2))
                    event_dict = {"event": ev_name, "t_elapsed_ns": t_ns, "raw": line_str}
                    self.log_events.append(event_dict)
                    # Trigger Callbacks
                    if ev_name in self.event_callbacks:
                        for cb in self.event_callbacks[ev_name]:
                            try:
                                cb(event_dict)
                            except Exception as e:
                                print(f"[orchestrator] Error in event callback for {ev_name}: {e}")

        self.reader_thread = threading.Thread(target=log_reader, daemon=True)
        self.reader_thread.start()

    def stop_logcat_tail(self) -> str:
        self.stop_logging.set()
        if self.logcat_proc:
            self.logcat_proc.terminate()
            try:
                self.logcat_proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.logcat_proc.kill()
        self.logcat_proc = None

        log_file = "tools/bench/run_logcat.log"
        with open(log_file, "w", encoding="utf-8") as f:
            f.write("\n".join(self.captured_logs))
        return log_file

    def pull_latest_tee_pcm(self, out_dir: str = "tools/bench/pulled_pcm") -> Optional[str]:
        os.makedirs(out_dir, exist_ok=True)
        remote_bench_dir = "/sdcard/Android/data/com.sisa.app.live/files/bench"
        res = subprocess.run(
            self.adb_base + ["shell", f"ls -1 {remote_bench_dir}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        if res.returncode != 0:
            return None
        pcm_files = [f.strip() for f in res.stdout.splitlines() if f.strip().endswith(".pcm")]
        if not pcm_files:
            return None

        # Nimm die neueste Datei
        latest_file = sorted(pcm_files)[-1]
        local_pcm = os.path.join(out_dir, latest_file)
        print(f"[orchestrator] Pulling {latest_file} to {local_pcm}")
        self.adb_cmd(["pull", f"{remote_bench_dir}/{latest_file}", local_pcm])
        return local_pcm

    def wait_for_event(self, event_name: str, timeout_ms: int) -> bool:
        start = time.time()
        while (time.time() - start) * 1000.0 < timeout_ms:
            for ev in self.log_events:
                if ev["event"] == event_name:
                    return True
            time.sleep(0.05)
        return False

    def run_scenario(self, scenario: Dict) -> Dict:
        name = scenario["name"]
        wav_path = scenario["wav"]
        barge_in_cfg = scenario.get("barge_in")
        expect_logs = scenario.get("expect_logs", [])

        print(f"\n=======================================================")
        print(f"=== RUNNING SCENARIO: {name}")
        print(f"=== WAV: {wav_path}")
        print(f"=======================================================")

        self.clear_device_bench_files()
        self.start_logcat_tail()
        time.sleep(0.5)

        # Barge-In Vorbereitung
        barge_in_injected = threading.Event()
        barge_in_time_ns = None

        if barge_in_cfg:
            trigger_ev = barge_in_cfg.get("trigger_event", "tts_chunk_first")
            delay_ms = barge_in_cfg.get("delay_ms", 100)
            barge_wav = barge_in_cfg["wav"]

            def on_trigger(ev):
                nonlocal barge_in_time_ns
                if barge_in_injected.is_set():
                    return
                barge_in_injected.set()
                print(f"[orchestrator] Barge-in triggered by {trigger_ev}, waiting {delay_ms}ms...")
                time.sleep(delay_ms / 1000.0)
                barge_in_time_ns = time.time_ns()
                print(f"[orchestrator] Injecting barge-in audio: {barge_wav}")
                try:
                    inject_audio(barge_wav, port=self.port)
                except Exception as e:
                    print(f"[orchestrator] Barge-in injection error: {e}")

            self.event_callbacks[trigger_ev] = [on_trigger]

        # Hauptaudio injizieren
        print(f"[orchestrator] Injecting primary audio...")
        inj_meta = inject_audio(wav_path, port=self.port)

        # Auf erwartete Logs warten
        validation_results = {}
        for exp in expect_logs:
            ev = exp["event"]
            within_ms = exp.get("within_ms", 5000)
            ok = self.wait_for_event(ev, within_ms)
            validation_results[ev] = "OK" if ok else "TIMEOUT"
            print(f"  [Assertion] {ev} within {within_ms}ms: {validation_results[ev]}")

        # Pufferzeit für TTS-Synthese, Chunk-Streaming und Ausklingen
        time.sleep(6.0)
        log_file = self.stop_logcat_tail()

        pulled_pcm = self.pull_latest_tee_pcm()

        metrics = compute_metrics(
            logcat_path=log_file,
            source_wav_path=wav_path,
            tee_pcm_path=pulled_pcm,
            barge_in_inj_time_ns=barge_in_time_ns,
            device_offset_ns=self.device_offset_ns
        )

        scenario_res = {
            "scenario": name,
            "validation": validation_results,
            "metrics": metrics,
            "pulled_pcm": pulled_pcm,
        }
        return scenario_res


def main():
    parser = argparse.ArgumentParser(description="Closed-Loop Benchmark Orchestrator")
    parser.add_argument("scenario_file", help="Path to scenario YAML file")
    parser.add_argument("-s", "--serial", default="emulator-5554", help="ADB device serial")
    parser.add_argument("-p", "--port", type=int, default=4567, help="TCP port (default: 4567)")
    parser.add_argument("--out", default="results.json", help="Path to results.json")
    args = parser.parse_args()

    with open(args.scenario_file, "r") as f:
        config = yaml.safe_load(f)

    orchestrator = BenchmarkOrchestrator(serial=args.serial, port=args.port)
    orchestrator.setup_port_forward()

    # Pre-Run Clock Sync
    print("[orchestrator] Running pre-run clock synchronization...")
    pre_clock = sync_clocks(adb_target=args.serial)
    # Der Offset wandert in jede Metrik-Auswertung, damit die Host-Injektionszeit
    # in die Geräteuhr übertragen werden kann (barge_in_e2e_ms).
    orchestrator.device_offset_ns = pre_clock.get("device_offset_ns")

    all_results = {
        "scenario_file": args.scenario_file,
        "pre_clock_sync": pre_clock,
        "runs": []
    }

    scenarios = config.get("scenarios", [])
    for sc in scenarios:
        res = orchestrator.run_scenario(sc)
        all_results["runs"].append(res)

    # Post-Run Clock Sync
    print("[orchestrator] Running post-run clock synchronization...")
    post_clock = sync_clocks(adb_target=args.serial)
    all_results["post_clock_sync"] = post_clock

    with open(args.out, "w", encoding="utf-8") as f:
        import json
        json.dump(all_results, f, indent=2, ensure_ascii=False)

    print(f"\n[orchestrator] All benchmarks finished. Results written to {args.out}")


if __name__ == "__main__":
    main()
