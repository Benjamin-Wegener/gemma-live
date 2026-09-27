#!/usr/bin/env python3
"""
Sisa Voice Assistant — Full E2E Test Orchestrator
==================================================
Automatischer End-to-End-Test der Sisa/Gemma-Live Full-Duplex Voice App.

Ablauf:
  1. Pre-flight checks (ADB, Qwen, TTS, Audio-Tools)
  2. Build & Deploy der Android-App
  3. App starten, auf Initialisierung warten
  4. Mac-Mikrofon-Aufnahme starten
  5. Gesprächs-Loop: Qwen → Mac-TTS → Pixel-Mic → Whisper → Gemma → Pixel-TTS → Mac-Mic
  6. Analyse: Latenzen, Überschneidungen, WER, Gesprächsfluss
  7. Report generieren

Verwendung:
  python3 orchestrator.py [--skip-build] [--turns 10] [--timeout 120]

Voraussetzungen:
  - Pixel 9a via USB/ADB verbunden
  - Qwen 3.6 auf localhost:1234 (OpenAI-kompatible API)
  - ffmpeg installiert (/opt/brew/bin/ffmpeg)
  - macOS `say` Befehl verfügbar
"""

import argparse
import datetime
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass, field, asdict
from enum import Enum
from pathlib import Path
from typing import Optional

try:
    import requests
except ImportError:
    print("ERROR: requests nicht installiert. pip install requests")
    sys.exit(1)

try:
    from rich.console import Console
    from rich.table import Table
    from rich.live import Live
    from rich.panel import Panel
    from rich.text import Text
    RICH_AVAILABLE = True
except ImportError:
    RICH_AVAILABLE = False

try:
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import matplotlib.patches as mpatches
    MATPLOTLIB_AVAILABLE = True
except ImportError:
    MATPLOTLIB_AVAILABLE = False

try:
    from jiwer import wer as compute_wer
    JIWER_AVAILABLE = True
except ImportError:
    JIWER_AVAILABLE = False

# ─────────────────────────────────────────────────────────────
# CONFIGURATION
# ─────────────────────────────────────────────────────────────

PROJECT_DIR = Path("/Users/user/Gemma-Live")
JAVA_HOME = "/opt/brew/opt/openjdk@17"
APK_PATH = PROJECT_DIR / "app/build/outputs/apk/debug/app-debug.apk"
PACKAGE = "com.sisa.app.live"
ACTIVITY = "com.sisa.app.live/com.sisa.app.ui.MainActivity"
QWEN_URL = "http://localhost:1234/v1"
FFMPEG = "/opt/brew/bin/ffmpeg"
MAC_TTS_VOICE = "Anna"  # German macOS voice
MAC_TTS_RATE = 170      # Words per minute for macOS say

QWEN_SYSTEM_PROMPT = """\
Du bist ein Tester für die Sprachassistentin Sisa.
Antworte immer nur mit EINEM kurzen deutschen Satz (max 15 Wörter), den man laut vorlesen kann.
Keine Anführungszeichen, kein Markdown, keine Erklärungen.
Themen: Begrüßung, Wetter, Allgemeinwissen, kurze Nachfragen.
"""

# Logcat patterns with named groups — angepasst an aktuelle BENCH-Tags (Pixel 9a, 09/2026)
LOGCAT_PATTERNS = {
    'whisper_init':      re.compile(r'Sisa-Stimme bereit|Whisper.*init|turn_detector prepared'),
    'whisper_listening':  re.compile(r'audio_loop started|WhisperSTT.*startListening|startListening'),
    'whisper_transcribe': re.compile(r'Sending \d+ .*samples .*to Whisper STT'),
    'whisper_result':    re.compile(r'BENCH stt_final.*?text="([^"]*)"'),
    'gemma_loaded':      re.compile(r'LocalGemma bereit|BENCH llm_load.*?load_ms=(\d+)'),
    'gemma_start':       re.compile(r'Using on-device LocalGemmaAssistant|BENCH context_state'),
    'gemma_response':    re.compile(r'BENCH llm_chunk.*?first=true.*?words=(\d+).*?chars=(\d+)'),
    'gemma_first_token': re.compile(r'BENCH llm_first_token'),
    'tts_start':         re.compile(r'BENCH tts_chunk(?:_first)?\b'),
    'tts_done':          re.compile(r'BENCH tts_chunk_first.*?samples=(\d+)'),
    'vad_speech_start':  re.compile(r'BENCH speech_started'),
    'vad_speech_stop':   re.compile(r'BENCH speech_stopped'),
    'barge_in':          re.compile(r'BENCH barge_in'),
    'barge_ignored':     re.compile(r'BENCH barge_ignored.*?reason=([a-z_]+)'),
    'echo_drop':         re.compile(r'BENCH echo_drop'),
    'barge_flush':       re.compile(r'BENCH barge_flush.*?reason=([a-z_]+)'),
    'audio_loop':        re.compile(r'audio_loop started'),
    'crash':             re.compile(r'(FATAL EXCEPTION|AndroidRuntime.*Error|CRASH|ANR in)'),
}

# ─────────────────────────────────────────────────────────────
# DATA STRUCTURES
# ─────────────────────────────────────────────────────────────

class EventType(str, Enum):
    WHISPER_INIT = "whisper_init"
    WHISPER_LISTENING = "whisper_listening"
    WHISPER_TRANSCRIBE = "whisper_transcribe"
    WHISPER_RESULT = "whisper_result"
    GEMMA_LOADED = "gemma_loaded"
    GEMMA_START = "gemma_start"
    GEMMA_RESPONSE = "gemma_response"
    TTS_START = "tts_start"
    TTS_DONE = "tts_done"
    VAD_SPEECH_START = "vad_speech_start"
    VAD_SPEECH_STOP = "vad_speech_stop"
    AUDIO_LOOP = "audio_loop"
    CRASH = "crash"
    MAC_SPEAK_START = "mac_speak_start"
    MAC_SPEAK_DONE = "mac_speak_done"
    QWEN_RESPONSE = "qwen_response"

@dataclass
class LogEvent:
    timestamp: float
    event_type: str
    raw_line: str = ""
    data: dict = field(default_factory=dict)

    def to_dict(self):
        d = asdict(self)
        d['iso_time'] = datetime.datetime.fromtimestamp(self.timestamp).isoformat()
        return d

@dataclass
class TurnResult:
    turn_number: int
    mac_text: str = ""           # Was Mac gesagt hat (Qwen-generiert)
    whisper_text: str = ""       # Was Whisper erkannt hat
    gemma_response: str = ""     # Was Gemma geantwortet hat
    mac_speak_start: float = 0
    mac_speak_end: float = 0
    vad_speech_start: float = 0
    vad_speech_stop: float = 0
    whisper_result_time: float = 0
    whisper_latency_ms: int = 0
    gemma_start_time: float = 0
    gemma_response_time: float = 0
    tts_start_time: float = 0
    tts_done_time: float = 0
    success: bool = False
    error: str = ""

# ─────────────────────────────────────────────────────────────
# CONSOLE OUTPUT
# ─────────────────────────────────────────────────────────────

console = Console() if RICH_AVAILABLE else None

def log(msg: str, style: str = ""):
    ts = datetime.datetime.now().strftime("%H:%M:%S")
    if console and style:
        console.print(f"[dim]{ts}[/dim] {msg}", style=style)
    elif console:
        console.print(f"[dim]{ts}[/dim] {msg}")
    else:
        print(f"[{ts}] {msg}")

def log_ok(msg): log(f"✅ {msg}", "green")
def log_warn(msg): log(f"⚠️  {msg}", "yellow")
def log_err(msg): log(f"❌ {msg}", "bold red")
def log_info(msg): log(f"ℹ️  {msg}", "blue")
def log_speak(who, msg): log(f"🎤 [{who}] {msg}", "cyan" if who == "Mac" else "magenta")

# ─────────────────────────────────────────────────────────────
# ADB HELPERS
# ─────────────────────────────────────────────────────────────

def adb(*args, timeout=30) -> subprocess.CompletedProcess:
    """Run an adb command and return the result."""
    cmd = ["adb"] + list(args)
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        log_err(f"ADB timeout: {' '.join(cmd)}")
        return subprocess.CompletedProcess(cmd, 1, "", "timeout")
    except FileNotFoundError:
        log_err("adb nicht gefunden! Android SDK im PATH?")
        sys.exit(1)

def adb_check_device() -> Optional[str]:
    """Check if a device is connected and return its serial."""
    result = adb("devices")
    for line in result.stdout.strip().split("\n")[1:]:
        parts = line.split("\t")
        if len(parts) == 2 and parts[1] == "device":
            return parts[0]
    return None

def adb_shell(*args, **kwargs) -> subprocess.CompletedProcess:
    """Run adb shell command."""
    return adb("shell", *args, **kwargs)

def check_whisper_models() -> bool:
    """Check if Whisper model files exist on device."""
    result = adb_shell("run-as", PACKAGE, "ls", "-la",
                       f"/data/user/0/{PACKAGE}/files/models/")
    if result.returncode != 0:
        return False
    files = result.stdout
    return all(f in files for f in [
        "tiny-encoder.int8.onnx",
        "tiny-decoder.int8.onnx",
        "tiny-tokens.txt"
    ])

# ─────────────────────────────────────────────────────────────
# LOGCAT MONITOR
# ─────────────────────────────────────────────────────────────

class LogcatMonitor:
    """Monitors adb logcat in real-time, parses events, and stores raw output."""

    def __init__(self, output_dir: Path):
        self.output_dir = output_dir
        self.events: list[LogEvent] = []
        self.raw_lines: list[str] = []
        self._lock = threading.Lock()
        self._process: Optional[subprocess.Popen] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._callbacks: list = []
        self._logfile = open(output_dir / "logcat_full.txt", "w")

    def add_callback(self, callback):
        """Add callback for new events. callback(event: LogEvent)"""
        self._callbacks.append(callback)

    def start(self):
        """Start monitoring logcat."""
        # Clear logcat first
        adb("logcat", "-c")
        time.sleep(0.5)

        self._running = True
        self._process = subprocess.Popen(
            ["adb", "logcat", "-v", "time"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )
        self._thread = threading.Thread(target=self._reader_loop, daemon=True)
        self._thread.start()
        log_ok("Logcat-Monitor gestartet")

    def stop(self):
        """Stop monitoring."""
        self._running = False
        if self._process:
            self._process.terminate()
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._process.kill()
        if self._thread:
            self._thread.join(timeout=5)
        self._logfile.close()
        log_ok(f"Logcat-Monitor gestoppt. {len(self.events)} Events erfasst")

    def _reader_loop(self):
        """Background thread reading logcat output."""
        for line in iter(self._process.stdout.readline, ''):
            if not self._running:
                break
            line = line.rstrip()
            if not line:
                continue

            self._logfile.write(line + "\n")
            self._logfile.flush()

            with self._lock:
                self.raw_lines.append(line)

            # Parse timestamp from logcat -v time format: "MM-DD HH:MM:SS.mmm ..."
            ts = time.time()  # Use system time as fallback
            ts_match = re.match(r'(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})', line)
            if ts_match:
                try:
                    now = datetime.datetime.now()
                    parsed = datetime.datetime.strptime(
                        f"{now.year}-{ts_match.group(1)}", "%Y-%m-%d %H:%M:%S.%f"
                    )
                    ts = parsed.timestamp()
                except ValueError:
                    pass

            # Match against patterns
            for pattern_name, pattern in LOGCAT_PATTERNS.items():
                match = pattern.search(line)
                if match:
                    event = LogEvent(
                        timestamp=ts,
                        event_type=pattern_name,
                        raw_line=line,
                        data={"groups": match.groups()} if match.groups() else {}
                    )
                    with self._lock:
                        self.events.append(event)
                    for cb in self._callbacks:
                        try:
                            cb(event)
                        except Exception as e:
                            log_err(f"Callback-Fehler: {e}")
                    break  # One match per line

    def wait_for_event(self, event_type: str, timeout: float = 60) -> Optional[LogEvent]:
        """Wait for a specific event type. Returns the event or None on timeout."""
        deadline = time.time() + timeout
        start_idx = len(self.events)
        while time.time() < deadline:
            with self._lock:
                for i in range(start_idx, len(self.events)):
                    if self.events[i].event_type == event_type:
                        return self.events[i]
            time.sleep(0.1)
        return None

    def wait_for_any(self, event_types: list[str], timeout: float = 60) -> Optional[LogEvent]:
        """Wait for any of the specified event types."""
        deadline = time.time() + timeout
        start_idx = len(self.events)
        while time.time() < deadline:
            with self._lock:
                for i in range(start_idx, len(self.events)):
                    if self.events[i].event_type in event_types:
                        return self.events[i]
            time.sleep(0.1)
        return None

    def get_events_since(self, timestamp: float, event_type: Optional[str] = None) -> list[LogEvent]:
        """Get all events after a timestamp, optionally filtered by type."""
        with self._lock:
            events = [e for e in self.events if e.timestamp >= timestamp]
            if event_type:
                events = [e for e in events if e.event_type == event_type]
            return events

    def save_events(self):
        """Save parsed events to JSONL."""
        with open(self.output_dir / "events.jsonl", "w") as f:
            for event in self.events:
                f.write(json.dumps(event.to_dict(), ensure_ascii=False) + "\n")
        log_ok(f"Events gespeichert: {len(self.events)} Events → events.jsonl")

# ─────────────────────────────────────────────────────────────
# QWEN CONVERSATION ENGINE
# ─────────────────────────────────────────────────────────────

class QwenEngine:
    """Manages conversation with Qwen 3.6 via OpenAI-compatible API."""

    def __init__(self, base_url: str = QWEN_URL):
        self.base_url = base_url
        self.model = None
        self.messages = [
            {"role": "system", "content": QWEN_SYSTEM_PROMPT}
        ]

    def check_available(self) -> bool:
        """Check if Qwen API is reachable."""
        try:
            r = requests.get(f"{self.base_url}/models", timeout=5)
            if r.status_code == 200:
                data = r.json()
                models = data.get("data", [])
                if models:
                    self.model = models[0].get("id", "default")
                    log_ok(f"Qwen erreichbar: Model={self.model}")
                    return True
        except Exception as e:
            log_err(f"Qwen nicht erreichbar: {e}")
        return False

    def generate_turn(self, sisa_response: Optional[str] = None) -> str:
        """Generate the next conversational turn.

        Args:
            sisa_response: What Sisa said in the previous turn (None for first turn)

        Returns:
            Text that the Mac should speak
        """
        if sisa_response:
            self.messages.append({
                "role": "user",
                "content": f"Sisa hat geantwortet: \"{sisa_response}\""
            })

        try:
            r = requests.post(
                f"{self.base_url}/chat/completions",
                json={
                    "model": self.model or "default",
                    "messages": self.messages,
                    "temperature": 0.3,
                    "max_tokens": 512,
                    # Qwen3.6 denkt sonst 200+ Tokens nach und content bleibt leer
                    "chat_template_kwargs": {"enable_thinking": False},
                },
                timeout=60
            )
            r.raise_for_status()
            data = r.json()
            msg = data["choices"][0]["message"]
            text = (msg.get("content") or "").strip()
            if not text:
                # Fallback: letzter deutscher Satz aus reasoning_content
                reasoning = msg.get("reasoning_content") or ""
                cands = re.findall(r'[A-ZÄÖÜ][^.!?\n]{3,80}[.!?]', reasoning)
                text = cands[-1].strip() if cands else ""
            # Clean up: remove quotes, markdown, etc.
            text = text.strip('"\'')
            text = re.sub(r'\*+', '', text).strip()
            # Nur nicht-leere Antworten in Historie (sonst 400 beim nächsten Call)
            if text:
                self.messages.append({"role": "assistant", "content": text})
            return text
        except Exception as e:
            log_err(f"Qwen Fehler: {e}")
            return ""

    def save_conversation(self, output_dir: Path):
        """Save conversation history to JSON."""
        with open(output_dir / "conversation.json", "w") as f:
            json.dump(self.messages, f, ensure_ascii=False, indent=2)

# ─────────────────────────────────────────────────────────────
# MAC TTS (macOS `say` command with Anna voice)
# ─────────────────────────────────────────────────────────────

class MacTTS:
    """Text-to-Speech on Mac using macOS `say` command."""

    def __init__(self, voice: str = MAC_TTS_VOICE, rate: int = MAC_TTS_RATE,
                 output_dir: Optional[Path] = None):
        self.voice = voice
        self.rate = rate
        self.output_dir = output_dir

    def speak(self, text: str, turn_number: int = 0) -> tuple[float, float]:
        """Speak text through Mac speakers.

        Returns:
            (start_time, end_time) as timestamps
        """
        log_speak("Mac", text)

        # Optionally save to WAV for analysis
        wav_path = None
        if self.output_dir:
            turns_dir = self.output_dir / "turns"
            turns_dir.mkdir(exist_ok=True)
            wav_path = turns_dir / f"turn_{turn_number:03d}_mac.wav"
            # Save text too
            (turns_dir / f"turn_{turn_number:03d}_text.txt").write_text(text)

        start_time = time.time()

        # Speak through speakers
        cmd = ["say", "-v", self.voice, "-r", str(self.rate)]

        # Also save to file if output_dir set
        if wav_path:
            # First save to file
            save_cmd = cmd + ["-o", str(wav_path), "--data-format=LEI16@16000", text]
            subprocess.run(save_cmd, capture_output=True, timeout=30)

        # Then actually speak aloud
        speak_cmd = cmd + [text]
        subprocess.run(speak_cmd, capture_output=True, timeout=60)

        end_time = time.time()
        return start_time, end_time

    @staticmethod
    def check_available() -> bool:
        """Check if macOS say is available."""
        try:
            result = subprocess.run(
                ["say", "-v", MAC_TTS_VOICE, "Test"],
                capture_output=True, timeout=10
            )
            return result.returncode == 0
        except Exception:
            return False

# ─────────────────────────────────────────────────────────────
# AUDIO RECORDER (Mac Microphone via ffmpeg)
# ─────────────────────────────────────────────────────────────

class AudioRecorder:
    """Records audio from Mac microphone using ffmpeg."""

    def __init__(self, output_path: Path):
        self.output_path = output_path
        self._process: Optional[subprocess.Popen] = None

    def start(self):
        """Start recording from Mac default microphone."""
        # Use ffmpeg with avfoundation to record from default mic
        # `:default` selects default audio input on macOS
        cmd = [
            FFMPEG,
            "-y",                          # Overwrite
            "-f", "avfoundation",          # macOS audio framework
            "-i", ":default",             # Default audio input device
            "-ar", "16000",                # 16kHz sample rate
            "-ac", "1",                    # Mono
            "-acodec", "pcm_s16le",        # 16-bit PCM
            str(self.output_path)
        ]
        self._process = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        log_ok(f"Audio-Aufnahme gestartet → {self.output_path.name}")

    def stop(self) -> Optional[Path]:
        """Stop recording and return path to WAV file."""
        if self._process:
            # Send 'q' to ffmpeg to gracefully stop
            try:
                self._process.stdin.write(b'q')
                self._process.stdin.flush()
            except (BrokenPipeError, OSError):
                pass
            try:
                self._process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait()
            log_ok(f"Audio-Aufnahme gestoppt → {self.output_path.name}")
            if self.output_path.exists():
                size_mb = self.output_path.stat().st_size / (1024 * 1024)
                log_info(f"Audio-Datei: {size_mb:.1f} MB")
                return self.output_path
        return None

# ─────────────────────────────────────────────────────────────
# BUILD & DEPLOY
# ─────────────────────────────────────────────────────────────

def build_and_deploy() -> bool:
    """Build the app and install on device."""
    log_info("Starte Build...")

    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME

    # Build
    result = subprocess.run(
        ["./gradlew", "assembleDebug"],
        cwd=str(PROJECT_DIR),
        env=env,
        capture_output=True,
        text=True,
        timeout=300
    )

    if result.returncode != 0:
        log_err(f"Build fehlgeschlagen!\n{result.stderr[-500:]}")
        return False

    log_ok("Build erfolgreich")

    # Check APK exists
    if not APK_PATH.exists():
        log_err(f"APK nicht gefunden: {APK_PATH}")
        return False

    # Install
    log_info("Installiere APK...")
    result = adb("install", "-r", str(APK_PATH), timeout=60)
    if result.returncode != 0:
        log_err(f"Installation fehlgeschlagen: {result.stderr}")
        return False

    log_ok("APK installiert")
    return True

def start_app() -> bool:
    """Start the Sisa app."""
    # Force stop first
    adb_shell("am", "force-stop", PACKAGE)
    time.sleep(1)

    # Start
    result = adb_shell("am", "start", "-n", ACTIVITY)
    if "Error" in result.stdout or result.returncode != 0:
        log_err(f"App-Start fehlgeschlagen: {result.stdout}")
        return False

    log_ok("App gestartet")
    return True

# ─────────────────────────────────────────────────────────────
# ANALYSIS ENGINE
# ─────────────────────────────────────────────────────────────

def compute_word_error_rate(reference: str, hypothesis: str) -> float:
    """Compute Word Error Rate between expected and actual transcription."""
    if JIWER_AVAILABLE:
        try:
            return compute_wer(reference.lower(), hypothesis.lower())
        except Exception:
            pass

    # Fallback: simple WER calculation
    ref_words = reference.lower().split()
    hyp_words = hypothesis.lower().split()
    if not ref_words:
        return 0.0 if not hyp_words else 1.0

    # Levenshtein distance on word level
    m, n = len(ref_words), len(hyp_words)
    dp = [[0] * (n + 1) for _ in range(m + 1)]
    for i in range(m + 1):
        dp[i][0] = i
    for j in range(n + 1):
        dp[0][j] = j
    for i in range(1, m + 1):
        for j in range(1, n + 1):
            if ref_words[i-1] == hyp_words[j-1]:
                dp[i][j] = dp[i-1][j-1]
            else:
                dp[i][j] = 1 + min(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
    return dp[m][n] / m


def analyze_results(turns: list[TurnResult], events: list[LogEvent],
                    output_dir: Path) -> dict:
    """Analyze test results and generate metrics."""
    metrics = {
        "total_turns": len(turns),
        "successful_turns": sum(1 for t in turns if t.success),
        "failed_turns": sum(1 for t in turns if not t.success),
        "latencies": {},
        "overlaps": [],
        "wer_scores": [],
        "turn_details": [],
    }

    stt_latencies = []
    e2e_latencies = []
    llm_latencies = []
    tts_latencies = []
    wer_scores = []

    for turn in turns:
        detail = {
            "turn": turn.turn_number,
            "mac_text": turn.mac_text,
            "whisper_text": turn.whisper_text,
            "gemma_response": turn.gemma_response,
            "success": turn.success,
            "error": turn.error,
        }

        if turn.success:
            # STT Latency: VAD speech_stop → whisper result
            if turn.vad_speech_stop > 0 and turn.whisper_result_time > 0:
                stt_lat = (turn.whisper_result_time - turn.vad_speech_stop) * 1000
                stt_latencies.append(stt_lat)
                detail["stt_latency_ms"] = round(stt_lat)

            # LLM Latency: whisper result → gemma response start
            if turn.whisper_result_time > 0 and turn.gemma_response_time > 0:
                llm_lat = (turn.gemma_response_time - turn.whisper_result_time) * 1000
                llm_latencies.append(llm_lat)
                detail["llm_latency_ms"] = round(llm_lat)

            # TTS Latency: gemma response → TTS start
            if turn.gemma_response_time > 0 and turn.tts_start_time > 0:
                tts_lat = (turn.tts_start_time - turn.gemma_response_time) * 1000
                tts_latencies.append(tts_lat)
                detail["tts_latency_ms"] = round(tts_lat)

            # E2E Latency: mac speak end → pixel TTS start
            if turn.mac_speak_end > 0 and turn.tts_start_time > 0:
                e2e_lat = (turn.tts_start_time - turn.mac_speak_end) * 1000
                e2e_latencies.append(e2e_lat)
                detail["e2e_latency_ms"] = round(e2e_lat)

            # WER
            if turn.mac_text and turn.whisper_text:
                wer_score = compute_word_error_rate(turn.mac_text, turn.whisper_text)
                wer_scores.append(wer_score)
                detail["wer"] = round(wer_score, 3)

            # Overlap detection: Mac speaking AND Pixel TTS active
            if (turn.mac_speak_start > 0 and turn.mac_speak_end > 0 and
                turn.tts_start_time > 0 and turn.tts_done_time > 0):
                overlap_start = max(turn.mac_speak_start, turn.tts_start_time)
                overlap_end = min(turn.mac_speak_end, turn.tts_done_time)
                if overlap_start < overlap_end:
                    overlap_ms = (overlap_end - overlap_start) * 1000
                    metrics["overlaps"].append({
                        "turn": turn.turn_number,
                        "overlap_ms": round(overlap_ms),
                        "description": "Mac und Pixel sprechen gleichzeitig"
                    })

        metrics["turn_details"].append(detail)

    # Aggregate latencies
    def stats(values):
        if not values:
            return {"min": 0, "max": 0, "avg": 0, "median": 0, "count": 0}
        values_sorted = sorted(values)
        return {
            "min": round(min(values)),
            "max": round(max(values)),
            "avg": round(sum(values) / len(values)),
            "median": round(values_sorted[len(values_sorted) // 2]),
            "count": len(values),
        }

    metrics["latencies"]["stt"] = stats(stt_latencies)
    metrics["latencies"]["llm"] = stats(llm_latencies)
    metrics["latencies"]["tts"] = stats(tts_latencies)
    metrics["latencies"]["e2e"] = stats(e2e_latencies)
    metrics["wer_scores"] = {
        "values": [round(w, 3) for w in wer_scores],
        **stats([w * 100 for w in wer_scores])  # as percentage
    }

    # Save metrics
    with open(output_dir / "metrics.json", "w") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=2)

    return metrics


def generate_report(metrics: dict, turns: list[TurnResult],
                    output_dir: Path, duration_s: float):
    """Generate markdown report."""
    report = []
    report.append("# 🧪 Sisa E2E Test Report")
    report.append(f"\n**Datum**: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    report.append(f"**Dauer**: {duration_s:.0f}s ({duration_s/60:.1f} Minuten)")
    report.append(f"**Turns**: {metrics['successful_turns']}/{metrics['total_turns']} erfolgreich")
    report.append("")

    # Latency table
    report.append("## ⏱️ Latenzen")
    report.append("")
    report.append("| Metrik | Min | Avg | Median | Max | N |")
    report.append("|--------|-----|-----|--------|-----|---|")
    for name, label in [("stt", "Whisper STT"), ("llm", "Gemma LLM"),
                        ("tts", "Piper TTS"), ("e2e", "End-to-End")]:
        lat = metrics["latencies"].get(name, {})
        if lat.get("count", 0) > 0:
            report.append(
                f"| {label} | {lat['min']}ms | {lat['avg']}ms | "
                f"{lat['median']}ms | {lat['max']}ms | {lat['count']} |"
            )
    report.append("")

    # WER
    wer_data = metrics.get("wer_scores", {})
    if wer_data.get("count", 0) > 0:
        report.append("## 📝 Transkriptionsqualität (Word Error Rate)")
        report.append(f"\n- **Durchschnitt WER**: {wer_data['avg']:.1f}%")
        report.append(f"- **Median WER**: {wer_data['median']:.1f}%")
        report.append(f"- **Beste WER**: {wer_data['min']:.1f}%")
        report.append(f"- **Schlechteste WER**: {wer_data['max']:.1f}%")
        report.append("")

    # Overlaps
    overlaps = metrics.get("overlaps", [])
    if overlaps:
        report.append("## ⚡ Überschneidungen")
        report.append(f"\n**{len(overlaps)} Überschneidungen erkannt:**\n")
        for o in overlaps:
            report.append(f"- Turn {o['turn']}: {o['overlap_ms']}ms — {o['description']}")
        report.append("")
    else:
        report.append("## ⚡ Überschneidungen")
        report.append("\n✅ Keine Überschneidungen erkannt.\n")

    # Turn details
    report.append("## 📋 Turn-Details")
    report.append("")
    for detail in metrics.get("turn_details", []):
        status = "✅" if detail["success"] else "❌"
        report.append(f"### Turn {detail['turn']} {status}")
        report.append(f"- **Mac sagte**: \"{detail['mac_text']}\"")
        report.append(f"- **Whisper erkannte**: \"{detail['whisper_text']}\"")
        report.append(f"- **Gemma antwortete**: \"{detail['gemma_response']}\"")
        if detail.get("stt_latency_ms"):
            report.append(f"- STT: {detail['stt_latency_ms']}ms | "
                        f"LLM: {detail.get('llm_latency_ms', '?')}ms | "
                        f"E2E: {detail.get('e2e_latency_ms', '?')}ms")
        if detail.get("wer") is not None:
            report.append(f"- WER: {detail['wer']*100:.1f}%")
        if detail.get("error"):
            report.append(f"- **Fehler**: {detail['error']}")
        report.append("")

    # Write report
    report_text = "\n".join(report)
    (output_dir / "test_report.md").write_text(report_text)
    log_ok("Report generiert → test_report.md")
    return report_text


def generate_timeline(turns: list[TurnResult], events: list[LogEvent],
                      output_dir: Path):
    """Generate timeline visualization."""
    if not MATPLOTLIB_AVAILABLE:
        log_warn("matplotlib nicht verfügbar, Timeline übersprungen")
        return

    if not turns:
        return

    if not turns or not any(t.mac_speak_start > 0 for t in turns):
        log_warn("Keine Turns mit Audio — Timeline übersprungen")
        return

    fig, ax = plt.subplots(figsize=(16, max(6, len(turns) * 1.2)))

    # Reference time = first event
    spoken = [t.mac_speak_start for t in turns if t.mac_speak_start > 0]
    t0 = min(spoken)

    colors = {
        "mac_speak": "#4CAF50",    # Green
        "vad_active": "#FF9800",   # Orange
        "whisper": "#2196F3",      # Blue
        "gemma": "#9C27B0",        # Purple
        "pixel_tts": "#F44336",    # Red
    }

    for i, turn in enumerate(turns):
        y = len(turns) - i

        # Mac speaking
        if turn.mac_speak_start > 0 and turn.mac_speak_end > 0:
            ax.barh(y, turn.mac_speak_end - turn.mac_speak_start,
                    left=turn.mac_speak_start - t0,
                    color=colors["mac_speak"], height=0.6, alpha=0.8)

        # VAD active
        if turn.vad_speech_start > 0 and turn.vad_speech_stop > 0:
            ax.barh(y - 0.15, turn.vad_speech_stop - turn.vad_speech_start,
                    left=turn.vad_speech_start - t0,
                    color=colors["vad_active"], height=0.3, alpha=0.7)

        # Whisper processing
        if turn.vad_speech_stop > 0 and turn.whisper_result_time > 0:
            ax.barh(y - 0.15, turn.whisper_result_time - turn.vad_speech_stop,
                    left=turn.vad_speech_stop - t0,
                    color=colors["whisper"], height=0.3, alpha=0.7)

        # Gemma processing
        if turn.gemma_start_time > 0 and turn.gemma_response_time > 0:
            ax.barh(y + 0.15, turn.gemma_response_time - turn.gemma_start_time,
                    left=turn.gemma_start_time - t0,
                    color=colors["gemma"], height=0.3, alpha=0.7)

        # Pixel TTS
        if turn.tts_start_time > 0 and turn.tts_done_time > 0:
            ax.barh(y + 0.15, turn.tts_done_time - turn.tts_start_time,
                    left=turn.tts_start_time - t0,
                    color=colors["pixel_tts"], height=0.3, alpha=0.7)

    # Labels
    ax.set_yticks(range(1, len(turns) + 1))
    ax.set_yticklabels([f"Turn {t.turn_number}" for t in reversed(turns)])
    ax.set_xlabel("Zeit (Sekunden)")
    ax.set_title("Sisa E2E Test — Gesprächs-Timeline")

    # Legend
    patches = [
        mpatches.Patch(color=colors["mac_speak"], label="Mac spricht", alpha=0.8),
        mpatches.Patch(color=colors["vad_active"], label="VAD aktiv", alpha=0.7),
        mpatches.Patch(color=colors["whisper"], label="Whisper STT", alpha=0.7),
        mpatches.Patch(color=colors["gemma"], label="Gemma LLM", alpha=0.7),
        mpatches.Patch(color=colors["pixel_tts"], label="Pixel TTS", alpha=0.7),
    ]
    ax.legend(handles=patches, loc="upper right")

    plt.tight_layout()
    plt.savefig(output_dir / "timeline.png", dpi=150)
    plt.close()
    log_ok("Timeline generiert → timeline.png")

# ─────────────────────────────────────────────────────────────
# MAIN ORCHESTRATOR
# ─────────────────────────────────────────────────────────────

class Orchestrator:
    """Main test orchestrator."""

    def __init__(self, args):
        self.args = args
        self.num_turns = args.turns
        self.timeout = args.timeout

        # Create output directory
        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        self.output_dir = Path(f"/Users/user/Gemma-Live/test_results/run_{timestamp}")
        self.output_dir.mkdir(parents=True, exist_ok=True)
        (self.output_dir / "turns").mkdir(exist_ok=True)

        self.logcat = LogcatMonitor(self.output_dir)
        self.qwen = QwenEngine(base_url=args.qwen_url if hasattr(args, 'qwen_url') else QWEN_URL)
        self.tts = MacTTS(output_dir=self.output_dir)
        self.recorder = AudioRecorder(self.output_dir / "full_session.wav")
        self.turns: list[TurnResult] = []
        self.start_time = 0.0
        self._crash_detected = False

        # Register crash callback
        self.logcat.add_callback(self._on_logcat_event)

    def _on_logcat_event(self, event: LogEvent):
        """Handle real-time logcat events."""
        if event.event_type == "crash":
            log_err(f"💥 CRASH DETECTED: {event.raw_line}")
            self._crash_detected = True
        elif event.event_type == "whisper_result":
            groups = event.data.get("groups", ())
            text = groups[0] if groups else "?"
            log_info(f"🎯 Whisper: \"{text}\"")
        elif event.event_type == "tts_start":
            groups = event.data.get("groups", ())
            text = groups[0] if groups else "?"
            log_speak("Pixel", text[:80])

    def preflight_checks(self) -> bool:
        """Run all pre-flight checks."""
        log_info("═══ PRE-FLIGHT CHECKS ═══")
        all_ok = True

        # 1. ADB
        serial = adb_check_device()
        if serial:
            log_ok(f"Pixel verbunden: {serial}")
        else:
            log_err("Kein Android-Gerät verbunden!")
            all_ok = False

        # 2. Whisper models
        if check_whisper_models():
            log_ok("Whisper-Modelle auf Device vorhanden")
        else:
            log_err("Whisper-Modelle fehlen auf Device!")
            log_info("Fix: adb push + run-as (siehe handoff.md)")
            all_ok = False

        # 3. Qwen
        if self.qwen.check_available():
            log_ok("Qwen API erreichbar")
        else:
            log_err("Qwen auf localhost:1234 nicht erreichbar!")
            log_info("Starte Qwen: lms server start / ollama serve / etc.")
            all_ok = False

        # 4. Mac TTS
        if self.tts.check_available():
            log_ok(f"Mac TTS verfügbar (Stimme: {MAC_TTS_VOICE})")
        else:
            log_err("macOS `say` nicht verfügbar!")
            all_ok = False

        # 5. ffmpeg
        if shutil.which(FFMPEG) or Path(FFMPEG).exists():
            log_ok(f"ffmpeg verfügbar: {FFMPEG}")
        else:
            log_warn("ffmpeg nicht gefunden — Audio-Aufnahme deaktiviert")

        # 6. JAVA_HOME
        if Path(JAVA_HOME).exists():
            log_ok(f"JAVA_HOME: {JAVA_HOME}")
        else:
            log_err(f"JAVA_HOME nicht gefunden: {JAVA_HOME}")
            if not self.args.skip_build:
                all_ok = False

        return all_ok

    def run_conversation_turn(self, turn_number: int,
                              last_sisa_response: Optional[str] = None) -> TurnResult:
        """Ein Over-Air Turn: Mac spricht über Luft → Pixel hört → Gemma antwortet über Lautsprecher.
        Mit Detail-Logging: timestamps, wav, logcat-slice pro Turn."""
        result = TurnResult(turn_number=turn_number)
        turn_t0 = time.time()
        turn_log_lines: list[str] = []

        def snap(tag: str) -> float:
            ts = time.time()
            turn_log_lines.append(f"{ts:.3f} {tag}")
            return ts

        try:
            # 1. Qwen generiert Text
            log_info(f"── Turn {turn_number} ──")
            mac_text = self.qwen.generate_turn(last_sisa_response)
            if not mac_text:
                result.error = "Qwen hat keinen Text generiert"
                return result
            result.mac_text = mac_text
            snap(f"QWEN text={mac_text!r}")

            self.logcat.events.append(LogEvent(
                timestamp=time.time(),
                event_type="qwen_response",
                data={"text": mac_text}
            ))

            # Start-Index für Event-Suche (nur neue Events nach Turn-Start)
            # Cursor rückt nach jedem Treffer vor — kein Re-Matching alter Turns
            cursor = [len(self.logcat.events)]
            def wait(types: list[str], timeout: float):
                evt = self._wait_from(cursor[0], types, timeout)
                if evt:
                    with self.logcat._lock:
                        try:
                            idx = next(i for i in range(cursor[0], len(self.logcat.events))
                                       if self.logcat.events[i] is evt)
                            cursor[0] = idx + 1
                        except StopIteration:
                            pass
                return evt

            # 2. Mac spricht über Lautsprecher → Pixel-Mic (über Luft)
            speak_start, speak_end = self.tts.speak(mac_text, turn_number)
            result.mac_speak_start = speak_start
            result.mac_speak_end = speak_end
            snap(f"MAC_SPEAK start={speak_start:.3f} end={speak_end:.3f} dur={(speak_end-speak_start)*1000:.0f}ms")

            self.logcat.events.append(LogEvent(timestamp=speak_start, event_type="mac_speak_start", data={"text": mac_text}))
            self.logcat.events.append(LogEvent(timestamp=speak_end, event_type="mac_speak_done", data={"text": mac_text}))

            # 3. Warte auf VAD (Pixel hat Mac gehört)
            log_info("Warte auf VAD (Pixel hört Mac über Luft)...")
            vad_start_evt = self._wait_from(search_from, ["vad_speech_start"], timeout=20)
            if vad_start_evt:
                result.vad_speech_start = vad_start_evt.timestamp
                snap(f"VAD_START t+{(vad_start_evt.timestamp-turn_t0)*1000:.0f}ms")

            vad_stop_evt = self._wait_from(search_from, ["vad_speech_stop"], timeout=20)
            if vad_stop_evt:
                result.vad_speech_stop = vad_stop_evt.timestamp
                snap(f"VAD_STOP t+{(vad_stop_evt.timestamp-turn_t0)*1000:.0f}ms")
            else:
                result.error = "VAD Timeout — Pixel hat Mac nicht gehört? (Abstand/Lautstärke prüfen)"
                log_warn(f"Turn {turn_number}: {result.error}")
                self._save_turn_log(turn_number, turn_log_lines, result)
                return result

            # 4. Warte auf Whisper STT
            log_info("Warte auf Whisper-Ergebnis...")
            whisper_evt = self._wait_from(search_from, ["whisper_result"], timeout=self.timeout)
            if not whisper_evt:
                result.error = f"Whisper Timeout ({self.timeout}s)"
                log_warn(f"Turn {turn_number}: Whisper Timeout!")
                self._save_turn_log(turn_number, turn_log_lines, result)
                return result

            result.whisper_result_time = whisper_evt.timestamp
            groups = whisper_evt.data.get("groups", ())
            # Pattern: BENCH stt_final ... text="..." — Gruppe 0 = Text
            raw = whisper_evt.raw_line
            m = re.search(r'text="([^"]*)"', raw)
            result.whisper_text = (m.group(1) if m else (groups[0] if groups else "")).strip()
            snap(f"STT text={result.whisper_text!r}")
            log_info(f"Whisper erkannte: \"{result.whisper_text}\"")

            # 5. Warte auf Gemma (First-Token + First-Chunk als Response-Start)
            log_info("Warte auf Gemma-Antwort...")
            gemma_start = self._wait_from(search_from, ["gemma_start"], timeout=10)
            if gemma_start:
                result.gemma_start_time = gemma_start.timestamp
                snap("GEMMA_START")

            tok = self._wait_from(search_from, ["gemma_first_token", "gemma_response"], timeout=self.timeout)
            if tok:
                result.gemma_response_time = tok.timestamp
                snap(f"GEMMA_FIRST t+{(tok.timestamp-turn_t0)*1000:.0f}ms type={tok.event_type}")
                # Volltext aus Logcat steht nicht in BENCH — als Proxy TTS-Audio abwarten,
                # Gemma-Text bleibt leer, Erfolg zählt über TTS-Start
                result.gemma_response = f"[{tok.event_type}]"
            else:
                result.error = "Gemma Timeout (kein First-Token)"
                self._save_turn_log(turn_number, turn_log_lines, result)
                return result

            # 6. Warte auf Pixel-TTS (hörbare Antwort über Luft)
            tts_start = self._wait_from(search_from, ["tts_start"], timeout=20)
            if tts_start:
                result.tts_start_time = tts_start.timestamp
                snap(f"TTS_START t+{(tts_start.timestamp-turn_t0)*1000:.0f}ms")

            # TTS-Ende: warte 2. Turn-Stille ODER barge_flush/echo als Zeichen für laufende Ausgabe,
            # dann natürliche Pause für Mic-Aufnahme
            time.sleep(4.0)
            snap("TURN_END wait=4s")
            # als tts_done: letztes tts-Event seit Turn-Start
            tts_evts = [e for e in self.logcat.events[search_from:] if e.event_type in ("tts_start", "tts_done")]
            if tts_evts:
                result.tts_done_time = tts_evts[-1].timestamp

            # Barge/Echo-Statistik für Robustheit
            barges = [e for e in self.logcat.events[search_from:] if e.event_type in ("barge_in", "barge_ignored", "echo_drop", "barge_flush")]
            for b in barges:
                snap(f"{b.event_type.upper()} {b.raw_line[-120:]}")

            result.success = result.tts_start_time > 0
            if not result.success:
                result.error = "Kein Pixel-TTS erkannt"
            else:
                log_ok(f"Turn {turn_number} erfolgreich!")

            self._save_turn_log(turn_number, turn_log_lines, result)

        except KeyboardInterrupt:
            result.error = "Abgebrochen durch User"
            raise
        except Exception as e:
            result.error = str(e)
            log_err(f"Turn {turn_number} Fehler: {e}")
            self._save_turn_log(turn_number, turn_log_lines, result)

        return result

    def _wait_from(self, from_idx: int, event_types: list[str], timeout: float) -> Optional[LogEvent]:
        """Warte nur auf Events ab Index from_idx (kein altes Re-Matching)."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.logcat._lock:
                for i in range(from_idx, len(self.logcat.events)):
                    if self.logcat.events[i].event_type in event_types:
                        # beim nächsten Wait nicht doppelt finden: Index weiterschieben
                        return self.logcat.events[i]
            time.sleep(0.15)
        return None

    def _save_turn_log(self, turn_number: int, lines: list[str], result: "TurnResult"):
        """Detail-Log pro Turn: timestamps + Ergebnis als JSON."""
        try:
            turns_dir = self.output_dir / "turns"
            turns_dir.mkdir(exist_ok=True)
            (turns_dir / f"turn_{turn_number:03d}_timeline.txt").write_text("\n".join(lines) + "\n")
            (turns_dir / f"turn_{turn_number:03d}_result.json").write_text(
                json.dumps(asdict(result), ensure_ascii=False, indent=2)
            )
            # Logcat-Slice seit Turn-Start grob mitschreiben
            with self.logcat._lock:
                tail = self.logcat.raw_lines[-400:]
            (turns_dir / f"turn_{turn_number:03d}_logcat.txt").write_text("\n".join(tail))
        except Exception as e:
            log_warn(f"Turn-Log speichern fehlgeschlagen: {e}")

    def run(self):
        """Main orchestration loop."""
        log_info("╔══════════════════════════════════════════╗")
        log_info("║   Sisa E2E Test Orchestrator v1.0        ║")
        log_info("╚══════════════════════════════════════════╝")

        # Pre-flight
        if not self.preflight_checks():
            log_err("Pre-flight Checks fehlgeschlagen. Abbruch.")
            if not self.args.force:
                sys.exit(1)
            log_warn("--force aktiv, fahre trotzdem fort...")

        # Build & Deploy
        if not self.args.skip_build:
            log_info("═══ BUILD & DEPLOY ═══")
            if not build_and_deploy():
                log_err("Build/Deploy fehlgeschlagen!")
                if not self.args.force:
                    sys.exit(1)
        else:
            log_info("Build übersprungen (--skip-build)")

        # Start app
        log_info("═══ APP STARTEN ═══")
        if not start_app():
            log_err("App konnte nicht gestartet werden!")
            sys.exit(1)

        # Start logcat monitor
        self.logcat.start()

        # Wait for initialization
        log_info("Warte auf App-Initialisierung...")
        whisper_init = self.logcat.wait_for_event("whisper_init", timeout=30)
        if whisper_init:
            log_ok("Whisper initialisiert!")
        else:
            log_warn("Whisper-Initialisierung nicht erkannt (Timeout 30s)")

        gemma_init = self.logcat.wait_for_event("gemma_loaded", timeout=60)
        if gemma_init:
            log_ok("Gemma geladen!")
        else:
            log_warn("Gemma-Laden nicht erkannt (Timeout 60s)")
            log_info("Warte noch 10s...")
            time.sleep(10)

        # Wait a bit more for everything to settle
        time.sleep(3)

        # Start audio recording
        log_info("═══ AUFNAHME STARTEN ═══")
        try:
            self.recorder.start()
        except Exception as e:
            log_warn(f"Audio-Aufnahme fehlgeschlagen: {e}")

        # Conversation loop
        log_info("═══ GESPRÄCHS-LOOP ═══")
        self.start_time = time.time()
        last_sisa_response = None
        consecutive_failures = 0

        try:
            for turn_num in range(1, self.num_turns + 1):
                if self._crash_detected:
                    log_err("App ist gecrasht! Abbruch.")
                    break

                result = self.run_conversation_turn(turn_num, last_sisa_response)
                self.turns.append(result)

                if result.success:
                    consecutive_failures = 0
                    last_sisa_response = result.gemma_response or result.whisper_text
                else:
                    consecutive_failures += 1
                    log_warn(f"Fehler-Streak: {consecutive_failures}/3")
                    if consecutive_failures >= 3:
                        log_err("3 aufeinanderfolgende Fehler. Abbruch.")
                        break

        except KeyboardInterrupt:
            log_warn("Test durch User abgebrochen (Ctrl+C)")

        duration = time.time() - self.start_time

        # Stop recording
        log_info("═══ AUFNAHME STOPPEN ═══")
        self.recorder.stop()

        # Stop logcat
        self.logcat.stop()
        self.logcat.save_events()

        # Save conversation
        self.qwen.save_conversation(self.output_dir)

        # Analysis
        log_info("═══ ANALYSE ═══")
        metrics = analyze_results(self.turns, self.logcat.events, self.output_dir)
        report = generate_report(metrics, self.turns, self.output_dir, duration)
        generate_timeline(self.turns, self.logcat.events, self.output_dir)

        # Summary
        log_info("═══ ERGEBNIS ═══")
        log_info(f"Ordner: {self.output_dir}")
        log_info(f"Turns: {metrics['successful_turns']}/{metrics['total_turns']} erfolgreich")

        lat = metrics["latencies"].get("e2e", {})
        if lat.get("count", 0) > 0:
            log_info(f"E2E Latenz: Ø{lat['avg']}ms (Median {lat['median']}ms)")

        wer_data = metrics.get("wer_scores", {})
        if wer_data.get("count", 0) > 0:
            log_info(f"WER: Ø{wer_data['avg']:.1f}%")

        overlaps = metrics.get("overlaps", [])
        if overlaps:
            log_warn(f"{len(overlaps)} Überschneidungen!")
        else:
            log_ok("Keine Überschneidungen")

        # Print report to console
        if console:
            console.print(Panel(report[:2000], title="Test Report", border_style="green"))

        log_ok(f"Test abgeschlossen in {duration:.0f}s")
        log_info(f"Report: {self.output_dir / 'test_report.md'}")

# ─────────────────────────────────────────────────────────────
# ENTRY POINT
# ─────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Sisa Voice Assistant — Full E2E Test Orchestrator"
    )
    parser.add_argument("--skip-build", action="store_true",
                       help="Build/Install überspringen")
    parser.add_argument("--turns", type=int, default=10,
                       help="Anzahl Gesprächs-Turns (default: 10)")
    parser.add_argument("--timeout", type=int, default=30,
                       help="Timeout pro Turn in Sekunden (default: 30)")
    parser.add_argument("--force", action="store_true",
                       help="Trotz fehlgeschlagener Checks weitermachen")
    parser.add_argument("--qwen-url", type=str, default=QWEN_URL,
                       help=f"Qwen API URL (default: {QWEN_URL})")

    args = parser.parse_args()

    orchestrator = Orchestrator(args)

    # Handle Ctrl+C gracefully
    def signal_handler(sig, frame):
        log_warn("\nAbbruch durch User...")
        orchestrator.recorder.stop()
        orchestrator.logcat.stop()
        orchestrator.logcat.save_events()
        orchestrator.qwen.save_conversation(orchestrator.output_dir)
        sys.exit(0)

    signal.signal(signal.SIGINT, signal_handler)

    orchestrator.run()


if __name__ == "__main__":
    main()
