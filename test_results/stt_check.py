import sys
import wave
import numpy as np
import sherpa_onnx

wav_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/turn2.wav"
rec = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder="/tmp/tiny-enc.onnx", decoder="/tmp/tiny-dec.onnx",
    tokens="/tmp/tiny-tok.txt", num_threads=4, language="de", task="transcribe")
w = wave.open(wav_path)
audio = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
stream = rec.create_stream()
stream.accept_waveform(16000, audio)
rec.decode_stream(stream)
with open("/tmp/stt_out.txt", "w") as f:
    f.write(stream.result.text)
print("DONE chars=%d" % len(stream.result.text))
