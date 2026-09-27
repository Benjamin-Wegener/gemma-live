#!/usr/bin/env python3
"""
bridge.py — Außen-LLM spricht mit der Sisa-App im Emulator.

Komponenten (alle AUSSERHALB des Emus, auf dem Mac):
  STT außen : sherpa-onnx Whisper-tiny  (hört App-Stimme aus tee_*.pcm)
  LLM außen : llama-server (E2B-GGUF, OpenAI-API, Persona "Außen-Partner")
  TTS außen : sherpa-onnx Piper "Kerstin" (identische Stimme wie App)
  Mund      : TCP-Inject 127.0.0.1:4567 (adb forward -> TcpAudioSource im Emu)
  Inhalt    : adb broadcast ACTION_USER_INPUT mit Transkript (exakter Wortlaut)
  Ohr       : adb pull tee_*.pcm aus /sdcard/.../files/bench + STT

Ablauf pro Außen-Turn:
  1. Reply-Text -> Piper-WAV (16 kHz) [+ afplay auf Mac, damit Mensch mithört]
  2. WAV realtime via TCP injizieren (treibt VAD + Barge-In echt an)
  3. Sofort ACTION_USER_INPUT-Broadcast mit Transkript (App antwortet exakt darauf;
     der Benchmark-Hardcode "Hallo Gemma..." wird durch SPEAKING-Guard übersprungen)
  4. Auf neues tee_*.pcm warten -> pull -> STT -> Text an LLM außen

Modi:
  python3 bridge.py talk --text "Hallo Sisa, ..."   # ein Außen-Satz sprechen
  python3 bridge.py listen                          # App-Antwort abhören + STT
  python3 bridge.py loop --turns 6 --seed "..."     # vollautomatisches Gespräch
  python3 bridge.py human                           # Mensch tippt, App antwortet (frei reden per Text)
"""
import argparse
import os
import socket
import subprocess
import sys
import time
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.environ.get("SISA_ASSETS", "/Users/user/Gemma-Live/app/src/main/assets")
PIPER_DIR = os.environ.get("PIPER_DIR", os.path.expanduser("~/piper-voices"))
ADB = ["adb", "-s", os.environ.get("SISA_SERIAL", "emulator-5554")]
BENCH_DIR = "/sdcard/Android/data/com.sisa.app.live/files/bench"
TCP_PORT = 4567
LLM_URL = "http://127.0.0.1:8080/v1/chat/completions"

IS_MAC = sys.platform == "darwin"


def player_cmd(wav_path):
    """Plattform-Audio-Player: afplay (macOS) bzw. ffplay (Linux)."""
    if IS_MAC:
        return ["afplay", wav_path]
    return ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", wav_path]

import sherpa_onnx  # noqa: E402

_tts = None
_recognizer = None


def adb(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True)


def get_tts():
    global _tts
    if _tts is None:
        vits = sherpa_onnx.OfflineTtsVitsModelConfig(
            model=f"{ASSETS}/models/de_DE-kerstin-low.onnx",
            tokens=f"{ASSETS}/models/tokens.txt",
            data_dir=f"{ASSETS}/espeak-ng-data",
            lexicon="",
        )
        model = sherpa_onnx.OfflineTtsModelConfig(vits=vits, num_threads=4, debug=False, provider="cpu")
        config = sherpa_onnx.OfflineTtsConfig(model=model, rule_fsts="", max_num_sentences=2)
        _tts = sherpa_onnx.OfflineTts(config)
        assert _tts.sample_rate == 16000, f"TTS rate {_tts.sample_rate}"
    return _tts


def get_recognizer():
    global _recognizer
    if _recognizer is None:
        mdir = f"{HERE}/models/sherpa-onnx-whisper-tiny"
        _recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
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
    return _recognizer


OUTSIDE_VOICE = os.environ.get("OUTSIDE_VOICE", "Eddy")  # z. B. 'Eddy' (männlich), 'Flo', 'Anna', oder 'Kerstin'

# Sprachpfad der Außenstimme: "tcp" (Host-TTS + TCP-Inject, Default/Mac) oder
# "capture" (Companion-App im Emulator spricht per Android-TTS, Sisa hört per
# Playback-Capture — kein Host-Audio nötig).
VOICE_MODE = os.environ.get("SISA_VOICE_MODE", "tcp")
OUTSIDE_PKG = "com.sisa.outsidevoice.live"
OUTSIDE_SPEAK_ACTION = "com.sisa.outsidevoice.ACTION_SPEAK"


# Linux-Ersatz für macOS say-Stimmen (Piper DE-Modelle, siehe PIPER_DIR).
PIPER_VOICES = {
    "eddy": "de_DE-thorsten-medium",
    "rocko": "de_DE-karlsson-low",
    "anna": "de_DE-eva_k-x_low",
    "flo": "de_DE-pavoque-low",
}


def tts_synthesize(text, wav_path, speed=1.1, voice=None):
    use_voice = voice or OUTSIDE_VOICE
    if use_voice.lower() == "kerstin":
        tts = get_tts()
        audio = tts.generate(text, sid=0, speed=speed)
        samples = np.array(audio.samples, dtype=np.float32)
        pcm16 = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
        with wave.open(wav_path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(audio.sample_rate)
            w.writeframes(pcm16.tobytes())
        return wav_path, len(pcm16) / audio.sample_rate

    if IS_MAC:
        # Natürliche macOS TTS-Stimme (z.B. Eddy, Anna, Flo) -> 16kHz Mono WAV
        aiff_tmp = wav_path + ".aiff"
        try:
            subprocess.run(["say", "-v", use_voice, "-r", "190", "-o", aiff_tmp, text], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["ffmpeg", "-y", "-i", aiff_tmp, "-ar", "16000", "-ac", "1", "-f", "wav", wav_path], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        finally:
            if os.path.exists(aiff_tmp):
                os.remove(aiff_tmp)

        with wave.open(wav_path, "rb") as w:
            frames = w.getnframes()
            rate = w.getframerate()
            return wav_path, frames / float(rate)

    # Linux: Piper-DE-Stimme -> 16kHz Mono WAV (gleiche Posts pipeline wie say)
    model = PIPER_VOICES.get(use_voice.lower(), "de_DE-thorsten-medium")
    onnx = os.path.join(PIPER_DIR, model + ".onnx")
    if not os.path.exists(onnx):
        raise FileNotFoundError(f"Piper-Stimme fehlt: {onnx} (python3 -m piper.download_voices {model})")
    piper_tmp = wav_path + ".piper.wav"
    try:
        subprocess.run(
            ["piper", "--model", onnx, "--output_file", piper_tmp],
            input=text.encode("utf-8"), check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["ffmpeg", "-y", "-i", piper_tmp, "-ar", "16000", "-ac", "1", "-f", "wav", wav_path], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        if os.path.exists(piper_tmp):
            os.remove(piper_tmp)

    with wave.open(wav_path, "rb") as w:
        frames = w.getnframes()
        rate = w.getframerate()
        return wav_path, frames / float(rate)


def stt_transcribe_pcm(pcm_path):
    rec = get_recognizer()
    raw = open(pcm_path, "rb").read()
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    stream = rec.create_stream()
    stream.accept_waveform(16000, samples)
    rec.decode_stream(stream)
    return stream.result.text.strip()


def inject_wav_realtime(wav_path, chunk_samples=512):
    with wave.open(wav_path, "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1, "need 16kHz mono"
        frames = w.readframes(w.getnframes())
    pcm = np.frombuffer(frames, dtype=np.int16)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    for _ in range(20):
        try:
            sock.connect(("127.0.0.1", TCP_PORT))
            break
        except ConnectionRefusedError:
            time.sleep(0.5)
    else:
        raise ConnectionRefusedError("TCP 4567 down — adb forward tcp:4567 tcp:4567 aktiv?")
    interval = chunk_samples / 16000.0
    data = pcm.tobytes()
    step = chunk_samples * 2
    try:
        t0 = time.perf_counter()
        n = 0
        for off in range(0, len(data), step):
            chunk = data[off:off + step]
            if len(chunk) < step:
                chunk += b"\x00" * (step - len(chunk))
            sock.sendall(chunk)
            n += 1
            target = t0 + n * interval
            wait = target - time.perf_counter()
            if wait > 0:
                time.sleep(wait)
    finally:
        sock.close()
    return len(pcm) / 16000.0


def broadcast_user_input(text):
    # WICHTIG: adb shell bekommt EINEN String (sonst zerlegt die Device-Shell
    # mehrteilige Texte in Einzel-Args -> Intent kaputt). Single-Quotes escapen.
    safe = text.replace("'", "").replace('"', "").replace("\n", " ")
    cmd = f"am broadcast -a com.sisa.app.live.ACTION_USER_INPUT --es text '{safe}'"
    return adb("shell", cmd)


def outside_say(text, play_local=False):
    """Außen-Stimme spricht: Broadcast zuerst (Inhalt gewinnt Rennen gegen
    VAD-Hardcode), dann Piper -> TCP-Inject (Stimme/VAD/Barge-In).
    play_local steuert, ob afplay auf dem Mac-Lautsprecher mitspielt.
    Im Modus SISA_VOICE_MODE=capture spricht stattdessen die Companion-App
    im Emulator (Android-TTS), Sisa hört per Playback-Capture."""
    if VOICE_MODE == "capture":
        return companion_say(text)
    broadcast_user_input(text)
    wav = "/tmp/outside_voice.wav"
    _, dur = tts_synthesize(text, wav)
    print(f"[außen] sagt ({dur:.1f}s): {text}", flush=True)
    player = None
    if play_local:
        player = subprocess.Popen(player_cmd(wav), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    inject_wav_realtime(wav)
    if player:
        player.wait()
    return dur


def companion_say(text, voice=None, rate=1.0, speak_timeout_s=60):
    """Außenstimme via Companion-App im Emulator (kein Host-Audio):
    Text-Broadcast an Sisa (exakter Wortlaut) + SPEAK an Companion (Sound).
    Wartet auf tts_done im Logcat. -> geschätzte Sprechdauer in Sekunden."""
    broadcast_user_input(text)
    safe = text.replace("'", "").replace('"', "").replace("\n", " ")
    before = set(
        l for l in adb("shell", "logcat -d -s OUTSIDE:I").stdout.splitlines()
        if "tts_done" in l
    )
    cmd = (f"am broadcast -n {OUTSIDE_PKG}/com.sisa.outsidevoice.SpeakReceiver "
           f"-a {OUTSIDE_SPEAK_ACTION} --es text '{safe}'")
    if voice:
        cmd += f" --es voice '{voice}'"
    if rate and rate != 1.0:
        cmd += f" --ef rate {float(rate)}"
    adb("shell", cmd)
    est = max(1.5, len(text) / 15.0 + 1.0)
    print(f"[außen via Companion] sagt (~{est:.1f}s): {text}", flush=True)
    # Auf tts_done warten (Companion meldet Fertigstellung im Logcat).
    t0 = time.time()
    while time.time() - t0 < speak_timeout_s:
        time.sleep(1.0)
        cur = adb("shell", "logcat -d -s OUTSIDE:I").stdout.splitlines()
        if any("tts_done" in l and l not in before for l in cur):
            break
    return est


def remote_pcm_sizes():
    """{dateiname: bytes} aller tee_*.pcm auf dem Emu (via stat, ls fallback)."""
    r = adb("shell", f"ls -l {BENCH_DIR}")
    out = {}
    if r.returncode != 0:
        return out
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 8 and parts[-1].endswith(".pcm"):
            try:
                out[parts[-1]] = int(parts[4])
            except ValueError:
                pass
    return out


APP_LOG = "/tmp/app_live.log"


def mark_log():
    try:
        return os.path.getsize(APP_LOG)
    except OSError:
        return 0


def wait_log(pattern, since_off, timeout_s=30):
    """Pollt /tmp/app_live.log ab Offset auf Regex-Pattern. -> neues Offset oder None."""
    import re
    t0 = time.time()
    off = since_off
    rx = re.compile(pattern)
    while time.time() - t0 < timeout_s:
        time.sleep(1.0)
        try:
            with open(APP_LOG, "rb") as f:
                f.seek(off)
                data = f.read().decode("utf-8", "replace")
                off = f.tell()
        except OSError:
            continue
        for line in data.splitlines():
            if rx.search(line):
                return off
    return None


def list_remote_pcms():
    return sorted(remote_pcm_sizes().keys())


def listen_app(timeout_s=90, log_mark=None, sizes_before=None):
    """Wartet bis die App-Antwort als Audio vorliegt (neues ODER wachsendes
    tee_*.pcm — die App schreibt oft in die offene Datei weiter), pullt + STT.
    Mit log_mark: wartet erst auf 'Received ACTION_USER_INPUT' (Turn akzeptiert)
    und dann auf 'tts_chunk' (Audio fließt), statt nur auf Dateiwachstum —
    robust auch wenn STT+LLM länger dauern als die Antwort selbst.
    sizes_before: Datei-Snapshot von VOR dem Sprechen (sonst ist eine schnelle
    Antwort schon drin und wird nicht erkannt)."""
    before = sizes_before if sizes_before is not None else remote_pcm_sizes()
    total_before = sum(before.values())
    print(f"[ohr] warte auf App-Antwort ({len(before)} PCMs, {total_before//1024} KB)...", flush=True)
    off = log_mark
    if off is not None:
        off2 = wait_log(r"Received ACTION_USER_INPUT", off, timeout_s=20)
        if off2 is None:
            print("[ohr] WARN: kein Received im Log — fahre mit Datei-Watch fort", flush=True)
        else:
            off = off2
            off3 = wait_log(r"BENCH\s+tts_chunk", off, timeout_s=timeout_s)
            if off3 is not None:
                off = off3
    t0 = time.time()
    target = None
    while time.time() - t0 < timeout_s:
        time.sleep(2.0)
        cur = remote_pcm_sizes()
        if not cur:
            continue
        fresh = [f for f in cur if f not in before]
        grown = [f for f, sz in cur.items() if sz > before.get(f, 0) + 32000]
        if fresh:
            target = sorted(fresh)[-1]
            break
        if grown:
            target = sorted(grown)[-1]
            break
    if not target:
        # Fallback: nimm die zuletzt gewachsene/groesste Datei (Antwort evtl.
        # schon komplett geschrieben waehrend STT+LLM liefen).
        cur = remote_pcm_sizes()
        if cur and (set(cur) - set(before) or sum(cur.values()) > total_before):
            target = sorted(cur)[-1]
            print(f"[ohr] nutze vorhandene Datei {target} (Antwort war schon fertig)", flush=True)
    if not target:
        print("[ohr] TIMEOUT — keine neue App-Antwort", flush=True)
        return "", None
    # Warten bis TTS fertig geschrieben hat (Dateigröße stabil)
    local = f"/tmp/app_answer_{target}"
    prev_size = before.get(target, 0)
    last = -1
    for _ in range(30):
        adb("pull", f"{BENCH_DIR}/{target}", local)
        try:
            sz = os.path.getsize(local)
        except OSError:
            sz = 0
        if sz == last and sz > 0:
            break
        last = sz
        time.sleep(2.0)
    # Nur den NEUEN Audio-Anhang dieses Turns transkribieren (Datei wächst an),
    # sonst wiederholt STT alte Antworten und sprengt das 30s-Whisper-Limit.
    full = open(local, "rb").read()
    new_bytes = full[prev_size:] if len(full) > prev_size else full
    print(f"[ohr] {len(new_bytes)//1024} KB neue Audio (Datei total {len(full)//1024} KB)", flush=True)
    slice_path = f"/tmp/app_slice_{target}"
    open(slice_path, "wb").write(new_bytes if len(new_bytes) >= 3200 else full[:16000 * 2 * 30])
    text = stt_transcribe_pcm(slice_path)
    print(f"[ohr] Sisa sagte (STT): {text}", flush=True)
    return text, local


def llm_outside(prompt, system="Du bist ein neugieriger Gesprächspartner. Antworte auf Deutsch, kurz, in 1-2 Sätzen. Stelle gern eine Rückfrage."):
    import json
    import urllib.request
    body = json.dumps({
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": prompt},
        ],
        "stream": False,
        "max_tokens": 200,
        "temperature": 0.7,
    }).encode()
    req = urllib.request.Request(LLM_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read())
    return data["choices"][0]["message"]["content"].strip()


def set_external_mic(enabled):
    cmd = ("am broadcast -a com.sisa.app.live.ACTION_SET_EXTERNAL_MIC --ez enabled " +
           ("true" if enabled else "false"))
    adb("shell", cmd)


_host_vad = None


def get_host_vad():
    """Silero-VAD auf dem Mac (gleiches Modell wie in der App)."""
    global _host_vad
    if _host_vad is None:
        config = sherpa_onnx.VadModelConfig()
        config.silero_vad.model = f"{ASSETS}/vad/silero_vad.onnx"
        config.silero_vad.threshold = 0.5
        config.silero_vad.min_silence_duration = 0.30
        config.silero_vad.min_speech_duration = 0.15
        config.silero_vad.window_size = 512
        config.silero_vad.max_speech_duration = 25.0
        config.sample_rate = 16000
        config.num_threads = 1
        config.provider = "cpu"
        config.debug = False
        _host_vad = sherpa_onnx.VadModel.create(config)
    return _host_vad


def read_exact(stream, n):
    buf = b""
    while len(buf) < n:
        chunk = stream.read(n - len(buf))
        if not chunk:
            break
        buf += chunk
    return buf


def connect_tcp():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    for _ in range(20):
        try:
            sock.connect(("127.0.0.1", TCP_PORT))
            return sock
        except ConnectionRefusedError:
            time.sleep(0.5)
    raise ConnectionRefusedError("TCP 4567 down — adb forward tcp:4567 tcp:4567 aktiv?")


def stt_transcribe_samples(samples_f32):
    rec = get_recognizer()
    stream = rec.create_stream()
    stream.accept_waveform(16000, np.asarray(samples_f32, dtype=np.float32))
    rec.decode_stream(stream)
    return stream.result.text.strip()


def handle_human_turn(audio_i16, mark, sizes):
    """Mensch-Äußerung (int16 @16kHz): STT -> Broadcast -> Antwort abspielen+zeigen."""
    if len(audio_i16) < 6400:  # <0.4s ignorieren
        return mark, sizes
    # Führende/abschließende Stille grob trimmen
    text = stt_transcribe_samples(audio_i16.astype(np.float32) / 32768.0)
    if not text:
        print("[mic] nichts verstanden.", flush=True)
        return mark, sizes
    print(f"\nDU (STT): {text}", flush=True)
    mark = mark_log()
    sizes = remote_pcm_sizes()
    broadcast_user_input(text)
    app_text, local = listen_app(timeout_s=90, log_mark=mark, sizes_before=sizes)
    if app_text:
        print(f"SISA: {app_text}", flush=True)
        if local and os.path.exists(local):
            # Roh-PCM -> WAV für afplay (hörbar sobald Lautsprecher an)
            import wave as _w
            raw = open(local, "rb").read()
            pcm = np.frombuffer(raw, dtype=np.int16)
            ww = "/tmp/sisa_answer.wav"
            with _w.open(ww, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(16000)
                w.writeframes(pcm.tobytes())
            subprocess.Popen(player_cmd(ww), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    mark = mark_log()
    sizes = remote_pcm_sizes()
    return mark, sizes


def cmd_mic(args):
    """Mensch spricht live via Mac-Mikro mit der App (Vollduplex + Barge-In)."""
    set_external_mic(True)
    vad = get_host_vad()
    ff = subprocess.Popen(
        ["ffmpeg", "-hide_banner", "-loglevel", "error",
         "-f", "avfoundation", "-i", args.device,
         "-ar", "16000", "-ac", "1", "-f", "s16le", "pipe:1"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=1024 * 64)
    time.sleep(1.0)
    if ff.poll() is not None:
        print("[mic] ffmpeg startet nicht — Mikrofon-Berechtigung?", flush=True)
        print("     Systemeinstellungen -> Datenschutz & Sicherheit -> Mikrofon -> Terminal erlauben", flush=True)
        set_external_mic(False)
        return
    sock = connect_tcp()
    # Pegel-Check: 1s lesen — exakt 0 überall = Mikro blockiert (Berechtigung)
    peak0 = 0
    pre = b""
    for _ in range(32):
        c = read_exact(ff.stdout, 1024)
        if len(c) < 1024:
            break
        pre += c
        sock.sendall(c)
        peak0 = max(peak0, int(np.abs(np.frombuffer(c, dtype=np.int16)).max()))
    print(f"[mic] Eingangspegel (1s): peak={peak0}", flush=True)
    if peak0 == 0:
        print("[mic] WARNUNG: Stille (exakt 0) — evtl. Mikrofon-Berechtigung fehlt!", flush=True)
        print("     Systemeinstellungen -> Datenschutz & Sicherheit -> Mikrofon -> Terminal erlauben.", flush=True)
    print("MIC LIVE — sprich mit Sisa (Barge-In: einfach dazwischenreden). Strg+C = Ende.", flush=True)
    speech_buf, speech_w, silence_w, in_speech = [], 0, 0, False
    mark, sizes = mark_log(), remote_pcm_sizes()
    try:
        while True:
            raw = read_exact(ff.stdout, 1024)  # 512 Samples
            if len(raw) < 1024:
                print("[mic] Mikrofon-Stream abgerissen.", flush=True)
                break
            try:
                sock.sendall(raw)
            except (BrokenPipeError, ConnectionResetError):
                print("[mic] TCP reconnect...", flush=True)
                sock = connect_tcp()
                sock.sendall(raw)
            pcm = np.frombuffer(raw, dtype=np.int16)
            try:
                is_sp = bool(vad.is_speech((pcm.astype(np.float32) / 32768.0)))
            except Exception:
                is_sp = np.abs(pcm).max() > 800  # Energy-Fallback
            if is_sp:
                speech_w += 1
                silence_w = 0
                if speech_w >= 5:
                    in_speech = True
                if in_speech:
                    speech_buf.append(pcm.copy())
            elif in_speech:
                silence_w += 1
                speech_buf.append(pcm.copy())
                if silence_w >= 10:  # ~320 ms Stille -> Turn fertig
                    audio = np.concatenate(speech_buf)
                    mark, sizes = handle_human_turn(audio, mark, sizes)
                    speech_buf, speech_w, silence_w, in_speech = [], 0, 0, False
                    vad.reset()
            else:
                speech_w = 0
    except KeyboardInterrupt:
        print("\n[mic] Ende.", flush=True)
    finally:
        try:
            ff.kill()
        except Exception:
            pass
        try:
            sock.close()
        except Exception:
            pass
        set_external_mic(False)


def cmd_talk(args):
    outside_say(args.text)


def cmd_listen(args):
    listen_app(timeout_s=args.timeout)


def cmd_loop(args):
    print(f"=== Außen-Loop: {args.turns} Turns ===", flush=True)
    mark = mark_log()
    sizes = remote_pcm_sizes()
    outside_say(args.seed)
    for i in range(args.turns):
        print(f"\n--- Turn {i+1}/{args.turns} ---", flush=True)
        app_text, _ = listen_app(timeout_s=90, log_mark=mark, sizes_before=sizes)
        if not app_text:
            print("Abbruch: keine App-Antwort.", flush=True)
            break
        try:
            reply = llm_outside(app_text)
        except Exception as e:
            print(f"LLM außen Fehler: {e} — nutze Echo-Fallback", flush=True)
            reply = f"Interessant! Du sagtest also: {app_text} Erzähl mir mehr dazu."
        time.sleep(1.0)
        mark = mark_log()
        sizes = remote_pcm_sizes()
        outside_say(reply)
    print("\n=== Loop fertig ===", flush=True)


def cmd_human(args):
    print("Mensch-Modus: tippen + Enter = du sprichst (Text geht direkt an App, ohne Zeitverlust).", flush=True)
    print("Danach App-Antwort abhören mit: python3 bridge.py listen", flush=True)
    try:
        while True:
            line = input("DU> ").strip()
            if not line:
                continue
            if line in ("/quit", "/exit"):
                break
            broadcast_user_input(line)
            print("[gesendet] App denkt + spricht...", flush=True)
    except (EOFError, KeyboardInterrupt):
        pass


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("talk")
    p.add_argument("--text", required=True)
    p.set_defaults(fn=cmd_talk)
    p = sub.add_parser("listen")
    p.add_argument("--timeout", type=int, default=60)
    p.set_defaults(fn=cmd_listen)
    p = sub.add_parser("loop")
    p.add_argument("--turns", type=int, default=4)
    p.add_argument("--seed", default="Hallo Sisa! Ich bin dein Gesprächspartner von außen. Wie geht es dir heute?")
    p.set_defaults(fn=cmd_loop)
    p = sub.add_parser("human")
    p.set_defaults(fn=cmd_human)
    p = sub.add_parser("mic")
    p.add_argument("--device", default=":0", help="ffmpeg avfoundation audio device (default: :0 MacBook-Mikrofon)")
    p.set_defaults(fn=cmd_mic)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
