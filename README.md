# Gemma Live

**Gemma Live** is an open-source, 100% on-device full-duplex voice assistant for Android, built on Google's Gemma 4 E2B model via LiteRT-LM. No cloud, no external servers — completely offline capable.

## Features

- **Full-Duplex Voice Conversation** — Talk naturally, just like a phone call
- **On-Device AI** — Gemma 4 E2B runs locally via LiteRT-LM (GPU/CPU)
- **Barge-In** — Interrupt the assistant mid-sentence (< 50 ms response)
- **Auto Model Download** — Gemma model downloads automatically if not present
- **Offline TTS** — Piper VITS voice (Kerstin) for natural speech output
- **Silero VAD** — Voice Activity Detection for turn detection
- **Conversation Memory** — Persistent context across multiple turns

## Architecture

| Component | Technology |
|-----------|-----------|
| AI Inference | Google Gemma 4 E2B via LiteRT-LM 0.17.1 |
| Voice Activity | Silero VAD (ONNX) |
| Text-to-Speech | Piper VITS (sherpa-onnx) |
| Audio | 16 kHz mono, hardware AEC |
| UI | Jetpack Compose (Material 3) |

## Building

```bash
./gradlew assembleDebug
```

## Installation

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

Apache License 2.0 — see [LICENSE](LICENSE)

## About

Gemma Live is a **foundation app** — a base for building other on-device AI applications. It provides the core infrastructure for:

- Real-time audio capture and processing
- On-device LLM inference with Gemma
- Full-duplex voice interaction patterns
- Offline TTS integration
- Model management and auto-download

Use this project as a starting point for your own on-device AI apps, research projects, or commercial products.
