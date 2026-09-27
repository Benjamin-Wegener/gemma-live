#!/usr/bin/env python3
"""
acoustic_duplex.py — Akustischer Dialog zwischen Mac (Thorsten + Gemma E2B) und Pixel 9a (Sisa/Kerstin + Gemma E2B).
Kommunikation erfolgt 100% über Raum-Akustik (Mikrofon ↔ Lautsprecher):
- Mac spricht über die Lautsprecher (Thorsten Piper-TTS).
- Pixel 9a hört über sein physisches Mikrofon zu, denkt (LiteRT Gemma) und antwortet über seinen Lautsprecher (Kerstin TTS).
- Mac hört über das MacBook Pro-Mikrofon zu, transkribiert via Whisper und lässt das lokale Gemma E2B antworten.
"""

import os
import sys
import time
import wave
import json
import threading
import urllib.request
import subprocess
import numpy as np

import sherpa_onnx

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = "/Users/user/Gemma-Live/app/src/main/assets"
THORSTEN_DIR = "/Users/user/models/piper"
LLM_URL = "http://127.0.0.1:8080/v1/chat/completions"
DEVICE_SERIAL = "58281JEBF17302"
RECORDINGS_DIR = os.path.join(HERE, "recordings")

class RoomAudioRecorder:
    """Zeichnet das gesamte Raum-Gespräch lückenlos über das Mac-Mikrofon in eine WAV-Datei auf."""
    def __init__(self, output_wav_path):
        self.output_wav_path = output_wav_path
        self.proc = None

    def start(self):
        os.makedirs(os.path.dirname(self.output_wav_path), exist_ok=True)
        # ffmpeg nimmt direkt vom Mac Mikrofon (avfoundation :0) als 16kHz mono WAV auf
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "avfoundation", "-i", ":0",
            "-ar", "16000", "-ac", "1",
            self.output_wav_path
        ]
        self.proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(f"[Mac-Audio-Recorder] 🎙️ Komplette Raum-Aufnahme gestartet: {self.output_wav_path}", flush=True)

    def stop(self):
        if self.proc:
            try:
                self.proc.terminate()
                self.proc.wait(timeout=3.0)
            except Exception:
                self.proc.kill()
            print(f"[Mac-Audio-Recorder] 💾 Raum-Aufnahme erfolgreich gespeichert: {self.output_wav_path}", flush=True)

def start_logcat_monitor():
    """Startet einen Hintergrund-Thread, der Live-Events aus logcat vom Pixel 9a streamt."""
    cmd = [
        "adb", "-s", DEVICE_SERIAL, "logcat", "-v", "tag",
        "LiveMode:I", "BENCH:I", "LocalGemma:I", "SisaVoice:I", "*:S"
    ]
    def _read_log():
        try:
            # Buffer leeren vor Start
            subprocess.run(["adb", "-s", DEVICE_SERIAL, "logcat", "-c"], check=False)
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
            for line in proc.stdout:
                line_str = line.strip()
                if line_str and not line_str.startswith("---------"):
                    print(f"  [Pixel-Logcat] {line_str}", flush=True)
        except Exception as e:
            print(f"  [Pixel-Logcat Error] {e}", flush=True)

    t = threading.Thread(target=_read_log, daemon=True)
    t.start()
    return t

# 1. Thorsten TTS initialisieren
def get_thorsten_tts():
    vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=f"{THORSTEN_DIR}/de_DE-thorsten-low.onnx",
        tokens=f"{ASSETS}/models/tokens.txt",
        data_dir=f"{ASSETS}/espeak-ng-data",
        lexicon="",
    )
    model = sherpa_onnx.OfflineTtsModelConfig(vits=vits, num_threads=4, debug=False, provider="cpu")
    config = sherpa_onnx.OfflineTtsConfig(model=model, rule_fsts="", max_num_sentences=2)
    tts = sherpa_onnx.OfflineTts(config)
    return tts

# 2. Whisper STT initialisieren
def get_recognizer():
    mdir = f"{HERE}/tools/outside_llm/models/sherpa-onnx-whisper-tiny"
    rec = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{mdir}/tiny-encoder.int8.onnx",
        decoder=f"{mdir}/tiny-decoder.int8.onnx",
        tokens=f"{mdir}/tiny-tokens.txt",
        language="de",
        task="transcribe",
        num_threads=4,
        decoding_method="greedy_search",
        debug=False,
        provider="cpu",
    )
    return rec

# 3. Silero VAD initialisieren
def get_vad():
    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = f"{ASSETS}/vad/silero_vad.onnx"
    config.silero_vad.threshold = 0.45
    config.silero_vad.min_silence_duration = 0.50
    config.silero_vad.min_speech_duration = 0.20
    config.silero_vad.window_size = 512
    config.silero_vad.max_speech_duration = 30.0
    config.sample_rate = 16000
    config.num_threads = 2
    config.provider = "cpu"
    config.debug = False
    return sherpa_onnx.VadModel.create(config)

_tts = None
_rec = None
_vad = None

def thorsten_say(text):
    global _tts
    if _tts is None:
        _tts = get_thorsten_tts()
    audio = _tts.generate(text, sid=0, speed=1.05)
    samples = np.array(audio.samples, dtype=np.float32)
    pcm16 = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
    wav_path = "/tmp/thorsten_say.wav"
    with wave.open(wav_path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(audio.sample_rate)
        w.writeframes(pcm16.tobytes())
    print(f"\n[THORSTEN (Mac)]: {text}", flush=True)
    subprocess.run(["afplay", wav_path])

def llm_generate_reply(history):
    system_prompt = (
        "Du bist Thorsten. Du führst gerade ein ganz normales, freundliches Kennenlerngespräch mit Sisa. "
        "Ihr lernt euch gerade erst kennen. Antworte immer auf Deutsch, sympathisch, authentisch und halte dich kurz (1-2 kurze Sätze), "
        "damit das Gespräch lebendig bleibt. Stelle gern eine interessierte Frage zum Kennenlernen."
    )
    messages = [{"role": "system", "content": system_prompt}] + history
    body = json.dumps({
        "messages": messages,
        "stream": False,
        "max_tokens": 150,
        "temperature": 0.7,
    }).encode()
    req = urllib.request.Request(LLM_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read())
    return data["choices"][0]["message"]["content"].strip()

def listen_to_pixel(max_wait_seconds=60):
    """Lauscht über das Mac-Mikrofon, bis Sisa auf dem Pixel fertig gesprochen hat."""
    global _rec, _vad
    if _rec is None:
        _rec = get_recognizer()
    if _vad is None:
        _vad = get_vad()

    print("[Mac-Mikro] Höre zu... Warte auf Sisas Antwort über Lautsprecher...", flush=True)
    # ffmpeg streamt 16kHz mono aus dem Mac-Mikro
    ff = subprocess.Popen(
        ["ffmpeg", "-hide_banner", "-loglevel", "error",
         "-f", "avfoundation", "-i", ":0",
         "-ar", "16000", "-ac", "1", "-f", "s16le", "pipe:1"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=1024 * 32
    )

    speech_chunks = []
    in_speech = False
    silence_frames = 0
    start_time = time.time()

    try:
        while time.time() - start_time < max_wait_seconds:
            chunk = ff.stdout.read(1024) # 512 Samples
            if len(chunk) < 1024:
                break
            pcm = np.frombuffer(chunk, dtype=np.int16)
            samples = pcm.astype(np.float32) / 32768.0

            is_speech = bool(_vad.is_speech(samples))

            if is_speech:
                in_speech = True
                speech_chunks.append(pcm)
                silence_frames = 0
            else:
                if in_speech:
                    speech_chunks.append(pcm)
                    silence_frames += 1
                    # Ca. 1.2 Sekunden Stille nach dem Sprechen = Satz beendet
                    if silence_frames > 38:
                        break
    finally:
        ff.terminate()
        try:
            ff.wait(timeout=1.0)
        except Exception:
            ff.kill()

    if not speech_chunks:
        return ""

    combined_pcm = np.concatenate(speech_chunks)
    stream = _rec.create_stream()
    stream.accept_waveform(16000, combined_pcm.astype(np.float32) / 32768.0)
    _rec.decode_stream(stream)
    text = stream.result.text.strip()
    return text

def main():
    print("=" * 65)
    print("AKUSTISCHES KENNENLERNGESPRÄCH")
    print("Mac (Thorsten, Gemma E2B) <== AKUSTIK ==> Pixel 9a (Sisa, Gemma E2B)")
    print("100% über Mikrofon & Lautsprecher im Raum")
    print("=" * 65)

    # 1. Komplette Raumaufnahme über Mac-Mikrofon starten (für Durcheinander- / Latenz-Analyse)
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    rec_file = os.path.join(RECORDINGS_DIR, f"acoustic_dialog_{timestamp}.wav")
    recorder = RoomAudioRecorder(rec_file)
    recorder.start()

    # 2. Logcat vom Pixel 9a im Hintergrund starten
    print("[Logcat] Starte Tag-Filter für Pixel 9a (LiveMode, BENCH, LocalGemma, SisaVoice)...")
    start_logcat_monitor()
    time.sleep(1.0)

    try:
        history = []
        
        # Gesprächs-Eröffnung von Thorsten
        greeting = "Hallo! Ich bin Thorsten. Schön dich kennenzulernen. Wie heißt du und wie geht es dir heute?"
        thorsten_say(greeting)
        history.append({"role": "assistant", "content": greeting})

        turn = 1
        while turn <= 10:
            print(f"\n--- Runde {turn} ---")
            # 1. Hören was Sisa über den Lautsprecher des Pixel 9a sagt
            sisa_text = listen_to_pixel(max_wait_seconds=45)

            if not sisa_text:
                print("[Mac-Mikro] Nichts verstanden oder Stille.")
                # Sanfter Prompt zum Weitermachen
                sisa_text = "Hallo? Bist du noch da?"

            print(f"[SISA (Pixel 9a)]: {sisa_text}", flush=True)
            history.append({"role": "user", "content": sisa_text})

            # 2. Thorsten denkt via lokalem llama-server (Gemma E2B) nach
            reply = llm_generate_reply(history)
            history.append({"role": "assistant", "content": reply})

            # Kurze natürliche Pause
            time.sleep(0.5)

            # 3. Thorsten spricht über den Mac-Lautsprecher in den Raum
            thorsten_say(reply)
            turn += 1

        print("\n" + "=" * 65)
        print("Gespräch beendet.")
        print("=" * 65)
    except KeyboardInterrupt:
        print("\n[Abbruch] Test durch Benutzer beendet.")
    finally:
        recorder.stop()
        print(f"\n[Analyse-Audio] Aufnahme bereit für Durcheinander- & Barge-In-Analyse:\n  -> {rec_file}")

if __name__ == "__main__":
    main()
