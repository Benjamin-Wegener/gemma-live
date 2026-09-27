#!/usr/bin/env python3
"""
inject.py:
Streamt 16 kHz Mono int16-LE PCM Chunks (default 512 Samples = 32ms) über TCP
(adb forward tcp:4567) an Android TcpAudioSource mit ultrapräzisem
perf_counter_ns Busy-Wait Spinning.
"""

import argparse
import socket
import sys
import time
import numpy as np
import soundfile as sf


def busy_wait_until(target_perf_ns: int):
    """
    Kombiniert kurzes Schlafen mit CPU-Spinning für sub-Mikrosekunden-Präzision.
    """
    # Wenn mehr als 2ms Restzeit, kurz sleepen, um CPU zu entlasten
    remaining = target_perf_ns - time.perf_counter_ns()
    if remaining > 2_000_000:
        time.sleep((remaining - 1_500_000) / 1e9)
    # Busy wait für den verbleibenden Rest
    while time.perf_counter_ns() < target_perf_ns:
        pass


def inject_audio(
    wav_path: str,
    host: str = "127.0.0.1",
    port: int = 4567,
    chunk_samples: int = 512,
    sample_rate: int = 16000,
    realtime: bool = True
) -> dict:
    """
    Liest wav_path und streamt Chunks über den TCP-Socket.
    """
    data, sr = sf.read(wav_path, dtype="int16")
    if data.ndim > 1:
        data = data[:, 0]  # Mono
    if sr != sample_rate:
        raise ValueError(f"WAV sample rate is {sr}, expected {sample_rate}")

    # Konvertiere in Bytes
    pcm_bytes = data.tobytes()
    chunk_bytes_len = chunk_samples * 2  # int16 = 2 bytes per sample
    total_samples = len(data)
    frame_interval_ns = int((chunk_samples / sample_rate) * 1e9)  # 32_000_000 ns für 512 @ 16k

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    
    retries = 10
    connected = False
    for attempt in range(retries):
        try:
            sock.connect((host, port))
            connected = True
            break
        except ConnectionRefusedError:
            time.sleep(0.5)

    if not connected:
        raise ConnectionRefusedError(f"Could not connect to {host}:{port}. Is adb forward tcp:{port} tcp:{port} active?")

    chunk_timestamps = []
    start_time_ns = time.time_ns()
    next_deadline_perf = time.perf_counter_ns()

    try:
        offset = 0
        seq = 0
        while offset < len(pcm_bytes):
            chunk = pcm_bytes[offset : offset + chunk_bytes_len]
            if len(chunk) < chunk_bytes_len:
                # Padding mit 0 falls letzter Chunk kleiner
                chunk = chunk + b"\x00" * (chunk_bytes_len - len(chunk))

            t_chunk_host_ns = time.time_ns()
            sock.sendall(chunk)
            chunk_timestamps.append((seq, t_chunk_host_ns, len(chunk) // 2))

            seq += 1
            offset += chunk_bytes_len

            if realtime:
                next_deadline_perf += frame_interval_ns
                busy_wait_until(next_deadline_perf)

    finally:
        sock.close()

    duration_ms = (total_samples / sample_rate) * 1000.0
    return {
        "wav_path": wav_path,
        "total_samples": total_samples,
        "duration_ms": duration_ms,
        "chunks_sent": len(chunk_timestamps),
        "start_time_ns": start_time_ns,
        "end_time_ns": time.time_ns(),
        "chunk_timestamps": chunk_timestamps,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Real-Time TCP Audio Streamer")
    parser.add_argument("--wav", required=True, help="Path to 16kHz mono int16 WAV file")
    parser.add_argument("--host", default="127.0.0.1", help="Target TCP host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=4567, help="Target TCP port (default: 4567)")
    parser.add_argument("--chunk-size", type=int, default=512, help="Chunk size in samples (default: 512)")
    args = parser.parse_args()

    res = inject_audio(args.wav, host=args.host, port=args.port, chunk_samples=args.chunk_size)
    print(f"[inject] Sent {res['chunks_sent']} chunks ({res['duration_ms']:.1f}ms) to {args.host}:{args.port}")
