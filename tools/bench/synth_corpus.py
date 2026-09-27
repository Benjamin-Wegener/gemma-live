#!/usr/bin/env python3
"""
synth_corpus.py:
Generiert standardisierte 16 kHz Mono WAV-Testdateien:
1. Pilot-Ton am Start: 19 kHz, 50ms Dauer (für sub-Millisekunden Kreuzkorrelation)
2. Synthetische Sprachsegmente (Formant-Synthese / akustische Sprachmodelle)
3. Präzise Pausen (200ms, 800ms) für VAD-Turn-Lag & Split-Tests
4. Rauschunterlegung (z. B. MUSAN / White / Pink Noise) bei parametrierbarem SNR (clean, 20dB, 10dB, 0dB)
5. Barge-In Utterances (überlagerte Sprache zu definiertem Zeitpunkt)
"""

import argparse
import os
import numpy as np
import soundfile as sf
from scipy import signal


SAMPLE_RATE = 16000


def generate_pilot_tone(duration_ms: float = 50.0, freq_hz: float = 19000.0, sr: int = SAMPLE_RATE) -> np.ndarray:
    """
    Erzeugt einen hochfrequenten Pilot-Ton (Chirp oder Sinus).
    Hinweis: Bei 16 kHz Abtastrate liegt die Nyquist-Grenze bei 8 kHz.
    Falls sr=16000 Hz, verwenden wir einen 7.5 kHz Marker-Puls (oder wenn sr höher, 19 kHz).
    Für 16kHz verwenden wir 7200 Hz Sinus mit Tukey-Fenster für minimale Klickartefakte.
    """
    actual_freq = min(freq_hz, (sr / 2.0) - 500.0)  # max 7500 Hz bei 16 kHz
    num_samples = int((duration_ms / 1000.0) * sr)
    t = np.arange(num_samples) / sr
    sine = np.sin(2 * np.pi * actual_freq * t)
    # Fensterung gegen Einschwingklicks
    window = signal.windows.tukey(num_samples, alpha=0.2)
    tone = sine * window * 0.8
    return tone.astype(np.float32)


def generate_silence(duration_ms: float, sr: int = SAMPLE_RATE) -> np.ndarray:
    num_samples = int((duration_ms / 1000.0) * sr)
    return np.zeros(num_samples, dtype=np.float32)


def generate_synthetic_speech_word(duration_ms: float = 400.0, fundamental_hz: float = 140.0, sr: int = SAMPLE_RATE) -> np.ndarray:
    """
    Erzeugt ein realistisches sprachähnliches Signal (Harmonische Oberwellen + Formantfilterung
    + Hüllkurve) zur zuverlässigen Aktivierung von VADs wie Silero und WebRTC VAD.
    """
    num_samples = int((duration_ms / 1000.0) * sr)
    t = np.arange(num_samples) / sr

    # Impulsfolge (Glottal pulses)
    pulse = np.zeros(num_samples, dtype=np.float32)
    period_samples = int(sr / fundamental_hz)
    pulse[::period_samples] = 1.0

    # Formantfilter (z.B. Vokal /a/: F1~800Hz, F2~1200Hz, F3~2500Hz)
    b1, a1 = signal.iirpeak(800.0, 4.0, fs=sr)
    b2, a2 = signal.iirpeak(1250.0, 5.0, fs=sr)
    b3, a3 = signal.iirpeak(2500.0, 6.0, fs=sr)

    f1 = signal.lfilter(b1, a1, pulse)
    f2 = signal.lfilter(b2, a2, pulse)
    f3 = signal.lfilter(b3, a3, pulse)

    speech = f1 + 0.6 * f2 + 0.3 * f3
    # Sprachhüllkurve (Anstieg - Plateau - Abfall)
    envelope = signal.windows.tukey(num_samples, alpha=0.3)
    speech = speech * envelope

    # Normalisierung
    max_val = np.max(np.abs(speech))
    if max_val > 0:
        speech = speech / max_val * 0.75
    return speech.astype(np.float32)


def generate_synthetic_sentence(words: int = 4, pause_ms: float = 200.0, sr: int = SAMPLE_RATE) -> np.ndarray:
    """
    Erzeugt einen Satz aus mehreren Wörtern mit definierten Binnenpausen.
    """
    parts = []
    pitches = [130.0, 145.0, 140.0, 120.0, 150.0]
    for i in range(words):
        pitch = pitches[i % len(pitches)]
        word = generate_synthetic_speech_word(duration_ms=350.0, fundamental_hz=pitch, sr=sr)
        parts.append(word)
        if i < words - 1:
            parts.append(generate_silence(pause_ms, sr=sr))
    return np.concatenate(parts)


def add_noise(signal_data: np.ndarray, snr_db: float = None) -> np.ndarray:
    """
    Fügt Rauschen mit gegebenem Signal-Rausch-Abstand (SNR in dB) hinzu.
    """
    if snr_db is None or snr_db >= 100:
        return signal_data

    sig_power = np.mean(signal_data ** 2)
    if sig_power <= 0:
        return signal_data

    noise_power = sig_power / (10 ** (snr_db / 10.0))
    noise = np.random.normal(0, np.sqrt(noise_power), len(signal_data)).astype(np.float32)
    mixed = signal_data + noise
    # Verhindere Übersteuern
    max_amp = np.max(np.abs(mixed))
    if max_amp > 0.98:
        mixed = mixed * (0.98 / max_amp)
    return mixed


def build_scenario_wav(
    output_path: str,
    speech_duration_ms: float = 1500.0,
    pause_ms: float = 250.0,
    snr_db: float = None,
    with_pilot: bool = True
) -> str:
    """
    Baut eine vollständige Test-WAV für VAD/Latency Sweeps.
    """
    segments = []
    # 1. 50ms Pilot Ton am Start
    if with_pilot:
        segments.append(generate_pilot_tone(duration_ms=50.0))
        # 100ms Stille nach Pilot-Ton
        segments.append(generate_silence(100.0))

    # 2. Erstes Sprachsegment (z. B. "Hallo Gemma, wie geht es dir?")
    word_count = max(1, int(speech_duration_ms / 400.0))
    speech1 = generate_synthetic_sentence(words=word_count, pause_ms=100.0)
    segments.append(speech1)

    # 3. Spezifische Pause (z. B. 200ms oder 800ms)
    if pause_ms > 0:
        segments.append(generate_silence(pause_ms))

    # 4. Nachfolgende Stille am Ende
    segments.append(generate_silence(500.0))

    full_audio = np.concatenate(segments)
    if snr_db is not None:
        full_audio = add_noise(full_audio, snr_db=snr_db)

    # In 16-Bit PCM konvertieren
    int16_audio = (full_audio * 32767).astype(np.int16)
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    sf.write(output_path, int16_audio, SAMPLE_RATE, subtype='PCM_16')
    return output_path


def build_barge_in_wav(output_path: str, pre_silence_ms: float = 0.0) -> str:
    """
    Erzeugt ein prägnantes, schnelles Barge-In Signal (z. B. "Stopp, warte kurz").
    """
    segments = []
    if pre_silence_ms > 0:
        segments.append(generate_silence(pre_silence_ms))
    # Lautes, direktes Wort
    w1 = generate_synthetic_speech_word(duration_ms=300.0, fundamental_hz=160.0)
    segments.append(w1)
    segments.append(generate_silence(80.0))
    w2 = generate_synthetic_speech_word(duration_ms=350.0, fundamental_hz=150.0)
    segments.append(w2)
    segments.append(generate_silence(300.0))

    full_audio = np.concatenate(segments)
    int16_audio = (full_audio * 32767).astype(np.int16)
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    sf.write(output_path, int16_audio, SAMPLE_RATE, subtype='PCM_16')
    return output_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Synthetic Audio Corpus Generator")
    parser.add_argument("--out-dir", default="tools/bench/corpus", help="Output directory")
    args = parser.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    # Generiere VAD Sweep Varianten
    build_scenario_wav(os.path.join(args.out_dir, "vad_pause_200ms.wav"), speech_duration_ms=1200.0, pause_ms=200.0)
    build_scenario_wav(os.path.join(args.out_dir, "vad_pause_800ms.wav"), speech_duration_ms=1200.0, pause_ms=800.0)
    build_scenario_wav(os.path.join(args.out_dir, "vad_noise_snr10db.wav"), speech_duration_ms=1200.0, pause_ms=250.0, snr_db=10.0)
    build_barge_in_wav(os.path.join(args.out_dir, "barge_in_speech.wav"))

    print(f"[synth_corpus] Generated synthetic corpus files in {args.out_dir}")
