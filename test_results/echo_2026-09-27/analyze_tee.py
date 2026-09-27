#!/usr/bin/env python3
"""Zerlegt einen Tee-Mitschnitt (16 kHz mono PCM16, 12-Byte-Header pro Chunk:
8 Byte elapsedRealtimeNanos, 4 Byte sizeInShorts, dann PCM) und reportet
Chunk-Timeline, Luecken und abgeschnittene Audiobereiche."""
import struct
import sys
from pathlib import Path

RATE = 16000


def parse(path: Path):
    data = path.read_bytes()
    off = 0
    chunks = []
    while off + 12 <= len(data):
        ts_ns, n = struct.unpack_from("<qi", data, off)
        off += 12
        end = off + n * 2
        if end > len(data):
            break
        pcm = data[off:end]
        off = end
        peak = 0
        if n:
            shorts = struct.unpack(f"<{n}h", pcm)
            peak = max(abs(s) for s in shorts)
        chunks.append({"t_ns": ts_ns, "samples": n, "peak": peak})
    return chunks


def main(path: str):
    chunks = parse(Path(path))
    if not chunks:
        print("keine Chunks")
        return
    t0 = chunks[0]["t_ns"]
    print(f"{path}: {len(chunks)} Chunks, gesamt {sum(c['samples'] for c in chunks)/RATE:.2f}s Audio")
    print(f"{'#':>3} {'t_start_ms':>10} {'dur_ms':>7} {'luecke_ms':>9} {'peak':>6}")
    prev_end = None
    for i, c in enumerate(chunks):
        start_ms = (c["t_ns"] - t0) / 1e6
        dur_ms = c["samples"] / RATE * 1000
        gap = "" if prev_end is None else f"{(c['t_ns']-prev_end)/1e6:9.1f}"
        print(f"{i:>3} {start_ms:10.1f} {dur_ms:7.1f} {gap:>9} {c['peak']:6}")
        prev_end = c["t_ns"] + int(c["samples"] / RATE * 1e9)
    last_end = (prev_end - t0) / 1e6
    audio_ms = sum(c["samples"] for c in chunks) / RATE * 1000
    print(f"\nWandzeit erster Chunk bis letzter Sample: {last_end:.1f} ms, Audio: {audio_ms:.1f} ms")
    print(f"Summe Leerlauf zwischen Chunks: {last_end - audio_ms:.1f} ms")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        main(p)
        print()
