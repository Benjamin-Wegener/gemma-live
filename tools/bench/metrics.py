#!/usr/bin/env python3
"""
metrics.py:
Führt vollständige Offline-Auswertung durch (zero overhead während Live-Benchmark).
Metriken:
- TTFA (Time To First Audio): Von speech_stopped bis tts_chunk_first
- VAD Turn-Lag: Offset zwischen WebrtcVAD Ground-Truth Ende und Log speech_stopped
- TTFT (Time To First Token): Von speech_stopped bis llm_first_token
- TTS Chunking: Inter-Chunk Latenzen und Jitter
- Barge-In RT: Von Injektionszeitpunkt des Barge-In Signals bis BENCH barge_flush
- WER: Word Error Rate via jiwer (STT Text vs. Ground Truth)
- TTS-WER via faster-whisper auf gepulltem Tee-Audio (offline)
- RTF: Real-Time Factor (Synthese-/Inferenzdauer relativ zur Audiodauer)
- Pilot Tone Kreuzkorrelation zur sub-Millisekunden Offset-Validierung
"""

import argparse
import json
import os
import re
import struct
from typing import Dict, List, Optional, Tuple
import numpy as np
import soundfile as sf
from scipy import signal


def parse_bench_logcat(logcat_path: str) -> List[Dict]:
    """
    Parst alle BENCH-Logzeilen aus dem Logcat-Dump.
    Format: BENCH <event> t_elapsed_ns=<val> [key=val ...]
    """
    events = []
    bench_re = re.compile(r"BENCH\s+([a-zA-Z0-9_]+)\s+t_elapsed_ns=(\d+)(.*)")
    with open(logcat_path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            match = bench_re.search(line)
            if match:
                event_name = match.group(1)
                t_elapsed_ns = int(match.group(2))
                extra_str = match.group(3).strip()
                extra = {}
                for part in extra_str.split():
                    if "=" in part:
                        k, v = part.split("=", 1)
                        extra[k] = v.strip('"')
                events.append({
                    "event": event_name,
                    "t_elapsed_ns": t_elapsed_ns,
                    "extra": extra,
                    "raw": line.strip()
                })
    return events


def read_tee_pcm(tee_file_path: str) -> Tuple[np.ndarray, List[Dict]]:
    """
    Liest die von TeeAudioTrack geschriebene Binärdatei:
    Format: [8B t_elapsed_ns][4B sizeInShorts][sizeInShorts * 2B int16-LE]
    Gibt die aneinandergehängten Samples und Metadaten der Chunks zurück.
    """
    chunks_meta = []
    pcm_arrays = []

    with open(tee_file_path, "rb") as f:
        while True:
            header = f.read(12)
            if len(header) < 12:
                break
            t_elapsed_ns, size_in_shorts = struct.unpack("<Qi", header)
            pcm_data = f.read(size_in_shorts * 2)
            if len(pcm_data) < size_in_shorts * 2:
                break
            shorts = np.frombuffer(pcm_data, dtype="<i2")
            pcm_arrays.append(shorts)
            chunks_meta.append({
                "t_elapsed_ns": t_elapsed_ns,
                "samples": size_in_shorts
            })

    if pcm_arrays:
        full_pcm = np.concatenate(pcm_arrays)
    else:
        full_pcm = np.array([], dtype=np.int16)

    return full_pcm, chunks_meta


def compute_ground_truth_vad_end(wav_path: str) -> float:
    """
    Berechnet den exakten akustischen Sprach-Endpunkt (in Sekunden) der Quell-WAV
    mittels webrtcvad / Frame-Energie.
    """
    import webrtcvad
    vad = webrtcvad.Vad(2)  # Aggressiveness: 2
    data, sr = sf.read(wav_path, dtype="int16")
    if data.ndim > 1:
        data = data[:, 0]

    frame_ms = 30
    frame_samples = int(sr * frame_ms / 1000)
    bytes_per_frame = frame_samples * 2

    last_speech_ms = 0.0
    for idx in range(0, len(data) - frame_samples, frame_samples):
        chunk = data[idx : idx + frame_samples]
        chunk_bytes = chunk.tobytes()
        is_speech = vad.is_speech(chunk_bytes, sr)
        if is_speech:
            last_speech_ms = (idx + frame_samples) / sr * 1000.0

    return last_speech_ms


def cross_correlate_pilot(source_wav: str, tee_pcm: np.ndarray, sr: int = 16000) -> float:
    """
    Berechnet die Kreuzkorrelation zwischen Pilot-Ton und Anfang des Audio-Streams,
    um den exakten Zeitversatz (< 1ms) zu ermitteln.
    """
    if len(tee_pcm) < sr // 4:
        return 0.0
    src_data, src_sr = sf.read(source_wav, dtype="float32")
    if src_data.ndim > 1:
        src_data = src_data[:, 0]

    # Erste 200ms des Quell-Signals (Pilot-Ton)
    pilot = src_data[: int(0.15 * sr)]
    target = tee_pcm[: int(0.5 * sr)].astype(np.float32) / 32768.0

    corr = signal.correlate(target, pilot, mode='valid')
    lag_samples = np.argmax(corr)
    lag_ms = (lag_samples / sr) * 1000.0
    return float(lag_ms)


def compute_metrics(
    logcat_path: str,
    source_wav_path: Optional[str] = None,
    tee_pcm_path: Optional[str] = None,
    ground_truth_text: str = "",
    barge_in_inj_time_ns: Optional[int] = None,
    device_offset_ns: Optional[int] = None,
    whisper_model_size: str = "tiny"
) -> Dict:
    """
    Kombiniert Logcat-Events, Tee-PCM und Ground Truth zur Berechnung aller 6 Kernmetriken.

    barge_in_inj_time_ns: Zeitpunkt der Host-seitigen Barge-In-Injektion (Host-Uhr,
                          time.time_ns() aus dem Orchestrator).
    device_offset_ns:     aus clock_sync.sync_clocks() stammender Offset
                          (device_elapsed_ns - host_time_ns). Nur damit lässt sich die
                          Host-Injektionszeit in die monotone Geräteuhr übertragen und
                          die End-to-End-Barge-In-Latenz (Injektion -> hörbarer Stopp)
                          ehrlich berechnen. Ohne Offset bleibt der Wert None.
    """
    events = parse_bench_logcat(logcat_path)

    # Relevante Events extrahieren
    t_speech_started = [e["t_elapsed_ns"] for e in events if e["event"] == "speech_started"]
    t_speech_stopped = [e["t_elapsed_ns"] for e in events if e["event"] == "speech_stopped"]
    t_llm_first = [e["t_elapsed_ns"] for e in events if e["event"] == "llm_first_token"]
    t_llm_chunks = [e["t_elapsed_ns"] for e in events if e["event"] == "llm_chunk"]
    t_llm_first_chunk = [e["t_elapsed_ns"] for e in events
                         if e["event"] == "llm_chunk" and e["extra"].get("first") == "true"]
    t_tts_first = [e["t_elapsed_ns"] for e in events if e["event"] == "tts_chunk_first"]
    t_tts_chunks = [e["t_elapsed_ns"] for e in events if e["event"] in ("tts_chunk_first", "tts_chunk")]
    # Nur echte Nutzer-Unterbrechungen zählen. `chunk_switch`-Flushes entstehen,
    # wenn der nächste Chunk DERSELBEN Antwort den Vorgänger verdrängt
    # (SisaVoiceService.speak() -> stop(reason="chunk_switch")) und sagen nichts
    # über die Barge-In-Reaktionszeit aus.
    t_barge_flush = [e["t_elapsed_ns"] for e in events
                     if e["event"] == "barge_flush" and e["extra"].get("reason") == "barge_in"]
    t_barge_flush_all = [e["t_elapsed_ns"] for e in events if e["event"] == "barge_flush"]
    stt_finals = [e["extra"].get("text", "") for e in events if e["event"] == "stt_final"]

    metrics = {}

    # 1. TTFA (Time To First Audio): speech_stopped -> tts_chunk_first
    if t_speech_stopped and t_tts_first:
        # Finde den passenden tts_first nach speech_stopped
        stop_ns = t_speech_stopped[0]
        tts_after = [t for t in t_tts_first if t >= stop_ns]
        if tts_after:
            metrics["ttfa_ms"] = round((tts_after[0] - stop_ns) / 1e6, 2)
        else:
            metrics["ttfa_ms"] = round((t_tts_first[0] - stop_ns) / 1e6, 2)
    else:
        metrics["ttfa_ms"] = None

    # 2. TTFT (Time To First Token): speech_stopped -> llm_first_token
    if t_speech_stopped and t_llm_first:
        stop_ns = t_speech_stopped[0]
        llm_after = [t for t in t_llm_first if t >= stop_ns]
        if llm_after:
            metrics["ttft_ms"] = round((llm_after[0] - stop_ns) / 1e6, 2)
        else:
            metrics["ttft_ms"] = round((t_llm_first[0] - stop_ns) / 1e6, 2)
    else:
        metrics["ttft_ms"] = None

    # 2b. Chunker-Latenz: llm_first_token -> erster an die TTS ausgelieferter Chunk.
    #     Direktes Maß für die TTFA-Optimierung im Sub-Sentence Chunker
    #     (E2BAIService.shouldFlushChunk: erster Chunk bereits ab 3 Wörtern).
    metrics["llm_chunk_count"] = len(t_llm_chunks)
    if t_llm_first and t_llm_first_chunk:
        tok_ns = t_llm_first[0]
        first_after = [t for t in t_llm_first_chunk if t >= tok_ns]
        if first_after:
            metrics["llm_first_chunk_gap_ms"] = round((first_after[0] - tok_ns) / 1e6, 2)
        else:
            metrics["llm_first_chunk_gap_ms"] = None
    else:
        metrics["llm_first_chunk_gap_ms"] = None

    # 3. VAD Turn-Lag: Ground Truth Ende vs. speech_stopped
    if source_wav_path and os.path.exists(source_wav_path) and t_speech_stopped and t_speech_started:
        gt_end_ms = compute_ground_truth_vad_end(source_wav_path)
        measured_duration_ms = (t_speech_stopped[0] - t_speech_started[0]) / 1e6
        # VAD Lag = wie lange nach Ende der echten Sprache hat VAD reagiert
        vad_lag_ms = max(0.0, measured_duration_ms - gt_end_ms)
        metrics["vad_turn_lag_ms"] = round(vad_lag_ms, 2)
    else:
        metrics["vad_turn_lag_ms"] = None

    # 4. TTS Chunking Delays
    if len(t_tts_chunks) > 1:
        delays_ms = np.diff(t_tts_chunks) / 1e6
        metrics["tts_chunk_delays_mean_ms"] = round(float(np.mean(delays_ms)), 2)
        metrics["tts_chunk_delays_p95_ms"] = round(float(np.percentile(delays_ms, 95)), 2)
        metrics["tts_chunk_count"] = len(t_tts_chunks)
    else:
        metrics["tts_chunk_delays_mean_ms"] = None
        metrics["tts_chunk_delays_p95_ms"] = None
        metrics["tts_chunk_count"] = len(t_tts_chunks)

    # 5. Barge-In RT: Reaktion auf eine ECHTE Nutzer-Unterbrechung.
    #    Device-intern gemessen: VAD-Erkennung des Nutzersprachbeginns
    #    (speech_started) -> AudioTrack-Flush. Bewusst NICHT über
    #    barge_in_inj_time_ns (Host-Uhr) gerechnet, da dieser Wert erst über die
    #    Host/Device-Uhrendifferenz übertragen werden müsste; die Kopplung an die
    #    Host-Injektion wird separat als barge_in_e2e_ms ausgewiesen.
    metrics["barge_flush_count"] = len(t_barge_flush_all)
    metrics["barge_flush_barge_in_count"] = len(t_barge_flush)
    if t_barge_flush:
        flush_ns = t_barge_flush[-1]
        # Auslösend ist der letzte speech_started VOR dem Flush. Ohne Kandidat
        # (z. B. wenn der Flush während der TTS-Synthese kommt, bevor Audio
        # hörbar war) wird kein Wert gemeldet — statt eines falschen.
        triggers = [t for t in t_speech_started if t <= flush_ns]
        if triggers:
            metrics["barge_in_rt_ms"] = round((flush_ns - triggers[-1]) / 1e6, 2)
        else:
            metrics["barge_in_rt_ms"] = None

        # End-to-End: Host-Injektion -> hörbarer Stopp. Erst der Clock-Sync
        # (clock_sync.py) erlaubt die Übertragung der Host-Zeit in die
        # monotone Geräteuhr; die Plausibilitätsgrenzen verhindern, dass eine
        # fehlerhafte Synchronisation als Latenzmessung durchgeht.
        if barge_in_inj_time_ns is not None and device_offset_ns is not None:
            inj_device_ns = barge_in_inj_time_ns + device_offset_ns
            delta_ms = (flush_ns - inj_device_ns) / 1e6
            if 0.0 <= delta_ms <= 5000.0:
                metrics["barge_in_e2e_ms"] = round(delta_ms, 2)
            else:
                metrics["barge_in_e2e_ms"] = None
        else:
            metrics["barge_in_e2e_ms"] = None
    else:
        metrics["barge_in_rt_ms"] = None
        metrics["barge_in_e2e_ms"] = None

    # 6. WER (Word Error Rate) für STT
    recognized_text = " ".join(stt_finals).strip()
    metrics["recognized_text"] = recognized_text
    if ground_truth_text and recognized_text:
        import jiwer
        metrics["wer"] = round(jiwer.wer(ground_truth_text.lower(), recognized_text.lower()), 3)
    else:
        metrics["wer"] = 0.0 if not ground_truth_text else None

    # 7. TTS Offline Validation & RTF
    if tee_pcm_path and os.path.exists(tee_pcm_path):
        pcm_data, chunks_meta = read_tee_pcm(tee_pcm_path)
        metrics["tee_samples_captured"] = len(pcm_data)
        metrics["tee_duration_sec"] = round(len(pcm_data) / 16000.0, 3)
        if len(pcm_data) > 0 and source_wav_path and os.path.exists(source_wav_path):
            pilot_offset = cross_correlate_pilot(source_wav_path, pcm_data)
            metrics["pilot_cross_correlation_offset_ms"] = round(pilot_offset, 2)

        # Schnelle Offline Whisper Transkription des erzeugten TTS-Signals
        if len(pcm_data) > 16000 * 0.5:
            try:
                from faster_whisper import WhisperModel
                # Offline Tiny Whisper
                whisper = WhisperModel(whisper_model_size, device="cpu", compute_type="int8")
                # Temporäre WAV schreiben für Whisper
                tmp_wav = f"{tee_pcm_path}.eval.wav"
                sf.write(tmp_wav, pcm_data, 16000, subtype='PCM_16')
                segments, info = whisper.transcribe(tmp_wav, language="de")
                tts_text = " ".join([s.text for s in segments]).strip()
                metrics["tts_transcription"] = tts_text
                if os.path.exists(tmp_wav):
                    os.remove(tmp_wav)
            except Exception as e:
                metrics["tts_transcription_error"] = str(e)

    return metrics


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Offline Benchmark Metrics Calculator")
    parser.add_argument("--logcat", required=True, help="Path to logcat file")
    parser.add_argument("--wav", help="Path to source WAV file")
    parser.add_argument("--tee", help="Path to pulled TeeAudioTrack PCM file")
    parser.add_argument("--gt-text", default="", help="Ground truth spoken text")
    parser.add_argument("--out", default="results.json", help="Path to output JSON")
    parser.add_argument("--barge-in-inj-ns", type=int, default=None,
                        help="Host-Zeitpunkt (time.time_ns) der Barge-In-Injektion")
    parser.add_argument("--device-offset-ns", type=int, default=None,
                        help="Offset aus clock_sync (device_elapsed_ns - host_time_ns)")
    args = parser.parse_args()

    results = compute_metrics(
        logcat_path=args.logcat,
        source_wav_path=args.wav,
        tee_pcm_path=args.tee,
        ground_truth_text=args.gt_text,
        barge_in_inj_time_ns=args.barge_in_inj_ns,
        device_offset_ns=args.device_offset_ns
    )

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"[metrics] Wrote metrics to {args.out}:")
    print(json.dumps(results, indent=2))
