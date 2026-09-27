#!/usr/bin/env python3
"""
clock_sync.py:
Führt 20x adb shell date +%s%N RTT/2 Messungen durch, berechnet Zeit-Offset und Drift
zwischen Host (time.perf_counter_ns / time.time_ns) und Android-Device
mittels robustem Theil-Sen Estimator.
"""

import argparse
import subprocess
import time
from typing import List, Tuple
import numpy as np


def measure_single_offset(adb_target: str) -> Tuple[int, int, int]:
    """
    Führt einen Round-Trip durch.
    Gibt (t_host_mid_ns, t_device_ns, rtt_ns) zurück.
    """
    cmd = ["adb"]
    if adb_target:
        cmd.extend(["-s", adb_target])
    cmd.extend(["shell", "date +%s%N; echo -n \":\"; cat /proc/uptime"])

    t_start = time.time_ns()
    out = subprocess.check_output(cmd, text=True).strip()
    t_end = time.time_ns()

    # out hat das Format "<date_ns>:<uptime_sec> <idle_sec>"
    date_part = out.split(":")[0].strip()
    try:
        t_device_ns = int(date_part)
    except ValueError:
        # Fallback auf pure date +%s%N falls Ausgabeformat abweicht
        cmd_pure = ["adb"]
        if adb_target:
            cmd_pure.extend(["-s", adb_target])
        cmd_pure.extend(["shell", "date +%s%N"])
        t_start = time.time_ns()
        out_pure = subprocess.check_output(cmd_pure, text=True).strip()
        t_end = time.time_ns()
        t_device_ns = int(out_pure)

    rtt_ns = t_end - t_start
    t_host_mid = t_start + (rtt_ns // 2)
    return t_host_mid, t_device_ns, rtt_ns


def theil_sen_estimator(x: np.ndarray, y: np.ndarray) -> Tuple[float, float]:
    """
    Robustes lineares Fitting y = slope * x + intercept via Theil-Sen.
    """
    n = len(x)
    slopes = []
    for i in range(n):
        for j in range(i + 1, n):
            dx = x[j] - x[i]
            if dx != 0:
                slopes.append((y[j] - y[i]) / dx)
    if not slopes:
        slope = 1.0
    else:
        slope = float(np.median(slopes))
    intercepts = y - slope * x
    intercept = float(np.median(intercepts))
    return slope, intercept


def sync_clocks(adb_target: str = "", rounds: int = 20) -> dict:
    """
    Führt rounds Messungen durch und schätzt Offset + Drift.
    """
    measurements = []
    for _ in range(rounds):
        try:
            m = measure_single_offset(adb_target)
            measurements.append(m)
        except Exception as e:
            time.sleep(0.01)
            continue
        time.sleep(0.005)

    if not measurements:
        raise RuntimeError("Clock sync failed: no successful adb roundtrips.")

    # Sortiere nach RTT und nimm die besten 75%
    measurements.sort(key=lambda item: item[2])
    best = measurements[: max(5, int(len(measurements) * 0.75))]

    t_host = np.array([b[0] for b in best], dtype=np.float64)
    t_dev = np.array([b[1] for b in best], dtype=np.float64)
    rtts = np.array([b[2] for b in best], dtype=np.float64)

    # Offset = t_dev - t_host
    offsets = t_dev - t_host
    t0 = t_host[0]
    dt_host = (t_host - t0) * 1e-9  # in Sekunden

    drift_slope, base_offset = theil_sen_estimator(dt_host, offsets)

    res = {
        "device_offset_ns": int(base_offset),
        "device_offset_ms": float(base_offset / 1e6),
        "drift_ns_per_sec": float(drift_slope),
        "median_rtt_ms": float(np.median(rtts) / 1e6),
        "min_rtt_ms": float(np.min(rtts) / 1e6),
        "samples_used": len(best),
    }
    return res


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Host-Device Clock Synchronizer")
    parser.add_argument("-s", "--serial", default="", help="ADB device serial")
    parser.add_argument("-n", "--rounds", type=int, default=20, help="Number of sync rounds")
    args = parser.parse_args()

    res = sync_clocks(adb_target=args.serial, rounds=args.rounds)
    print(f"[clock_sync] Device Offset: {res['device_offset_ms']:.3f} ms | Drift: {res['drift_ns_per_sec']:.2f} ns/s | Median RTT: {res['median_rtt_ms']:.2f} ms")
