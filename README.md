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

## Help: Pixel 8a reference device

Gemma Live is actively tested on the Google Pixel 8a. Its Google Tensor G3,
8 GB LPDDR5X RAM, and 128 GB / 256 GB UFS 3.1 storage make it a practical
reference device for local voice inference. The 6.1-inch, 120 Hz OLED display
and 4,492 mAh typical battery are also useful for extended hands-free testing.
See Google's [official Pixel 8a technical specifications](https://support.google.com/pixelphone/answer/7158570?hl=en).

Synthetic scores vary with Android version, temperature, battery level, and
benchmark release. Useful current reference ranges are:

| Benchmark | Pixel 8a reference result |
|-----------|---------------------------|
| Geekbench 6 CPU, single-core | about 1,565–1,640 |
| Geekbench 6 CPU, multi-core | about 3,794–4,193 |
| AnTuTu | about 1.43M |

The Geekbench range comes from a recent [Geekbench Browser result](https://browser.geekbench.com/v6/cpu/18055935) and an independent [Pixel 8a review](https://www.tomsguide.com/phones/google-pixel-phones/google-pixel-8a-review). The AnTuTu figure is a community-reported reference and should be treated as approximate; see [NanoReview's current aggregation](https://nanoreview.net/en/phone/google-pixel-8a).

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
