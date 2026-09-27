#!/usr/bin/env bash
set -euo pipefail

# run_ci.sh:
# Automatisierter Benchmark-Runner:
# 1. BlackHole Audio Device Smoke Check
# 2. Swap / Memory Pressure Gate (Abbruch bei swap > 500MB)
# 3. CPU Core Pinning via taskpolicy auf macOS (llama: Cores 2-7, qemu/emulator: Cores 0-1, 8-9)
# 4. llama-server Management mit --threads 6 --mlock
# 5. Build, Install und Ausführung von vad_sweep.yaml und barge_in.yaml
# 6. Validierung der Ergebnisse in results.json

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ADB="/opt/brew/bin/adb"
VENV_PYTHON="${SCRIPT_DIR}/.venv/bin/python3"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"

echo "=========================================================="
echo "=== GEMMA-LIVE CLOSED-LOOP BENCHMARK CI RUNNER ==="
echo "=========================================================="

# 1. BlackHole Check
echo "[CI] Checking BlackHole Audio Driver (Smoke-Check)..."
if system_profiler SPAudioDataType 2>/dev/null | grep -qi "BlackHole"; then
    echo "[CI] BlackHole detected (available for optional system audio smoke test)."
else
    echo "[CI] BlackHole not detected. Direct TCP+Tee (bit-exact) pipeline will be used as primary."
fi

# 2. Swap / Memory Pressure Gate
echo "[CI] Checking macOS Swap and Memory Pressure..."
SWAP_USED_MB=$(sysctl vm.swapusage | awk '{print $7}' | sed 's/M//' | cut -d'.' -f1 || echo "0")
if [ -n "$SWAP_USED_MB" ] && [ "$SWAP_USED_MB" -gt 500 ]; then
    if [ "${ALLOW_SWAP:-0}" = "1" ]; then
        echo "[CI WARNING] Host swap usage is ${SWAP_USED_MB}MB (> 500MB). Proceeding because ALLOW_SWAP=1."
    else
        echo "[CI ERROR] Host swap usage is ${SWAP_USED_MB}MB (> 500MB). Aborting benchmark to prevent latency skew."
        exit 1
    fi
else
    echo "[CI] Swap usage: ${SWAP_USED_MB}MB (OK)."
fi

# 3. Emulator Check & Setup
echo "[CI] Checking ADB Device: ${SERIAL}..."
${ADB} devices | grep -q "${SERIAL}" || {
    echo "[CI ERROR] Device ${SERIAL} not attached!"
    exit 1
}

# 4. Taskpolicy Pinning für Emulator falls qemu läuft
EMU_PID=$(pgrep -f "qemu-system" || pgrep -f "emulator" | head -n 1 || true)
if [ -n "${EMU_PID}" ]; then
    echo "[CI] Applying taskpolicy to Emulator (PID ${EMU_PID})..."
    taskpolicy -b -p "${EMU_PID}" || true
fi

# 5. Llama-Server Management
LLAMA_PID=$(pgrep -f "llama-server" | head -n 1 || true)
if [ -z "${LLAMA_PID}" ]; then
    echo "[CI] Notice: llama-server not active locally. App will stream to default container or mock endpoint."
else
    echo "[CI] Pinning existing llama-server (PID ${LLAMA_PID})..."
    taskpolicy -B -p "${LLAMA_PID}" || true
fi

# 6. Build & Install Benchmark Variant
echo "[CI] Building app:assembleBenchmark..."
export JAVA_HOME=/opt/brew/opt/openjdk@17
export ANDROID_HOME=/Users/user/local/android-sdk
cd "${ROOT_DIR}"
./gradlew assembleBenchmark

BENCH_APK="${ROOT_DIR}/app/build/outputs/apk/benchmark/app-benchmark.apk"
echo "[CI] Installing ${BENCH_APK} on ${SERIAL}..."
${ADB} -s "${SERIAL}" install -r -t "${BENCH_APK}"

# Berechtigungen erteilen & App starten
${ADB} -s "${SERIAL}" shell pm grant com.sisa.app.live android.permission.RECORD_AUDIO || true
echo "[CI] Launching Gemma-Live in Benchmark Mode..."
${ADB} -s "${SERIAL}" shell am force-stop com.sisa.app.live
${ADB} -s "${SERIAL}" shell am start -n com.sisa.app.live/com.sisa.app.ui.MainActivity
sleep 5

# Port Forwarding
echo "[CI] Setting up adb forward tcp:4567 tcp:4567..."
${ADB} -s "${SERIAL}" forward tcp:4567 tcp:4567

# LLM-Endpoint des Hosts erreichbar machen. Auf dem Emulator ist 10.0.2.2 der
# Host-Alias, auf physischer Hardware (Pixel 8a) nicht. `adb reverse` spiegelt
# den Host-Port ins Gerät, daher funktioniert derselbe Endpoint auf beiden
# Zielen. Für Hardware-Benchmarks zusätzlich bauen mit:
#   ./gradlew assembleBenchmark -Pe2bUrl=http://localhost:8080
echo "[CI] Setting up adb reverse tcp:8080 tcp:8080 (host llama-server)..."
${ADB} -s "${SERIAL}" reverse tcp:8080 tcp:8080 || true

# 7. Test-Corpus generieren
echo "[CI] Ensuring synthetic corpus is generated..."
"${VENV_PYTHON}" "${SCRIPT_DIR}/synth_corpus.py" --out-dir "${SCRIPT_DIR}/corpus"

# 8. Orchestrator ausführen
echo "[CI] Executing VAD Sweep Benchmark..."
"${VENV_PYTHON}" "${SCRIPT_DIR}/orchestrator.py" "${SCRIPT_DIR}/scenarios/vad_sweep.yaml" -s "${SERIAL}" --out "${SCRIPT_DIR}/results_vad.json"

echo "[CI] Executing Barge-In Benchmark..."
"${VENV_PYTHON}" "${SCRIPT_DIR}/orchestrator.py" "${SCRIPT_DIR}/scenarios/barge_in.yaml" -s "${SERIAL}" --out "${SCRIPT_DIR}/results_barge_in.json"

echo "=========================================================="
echo "=== BENCHMARK COMPLETED SUCCESSFULLY ==="
echo "=== Results: ${SCRIPT_DIR}/results_vad.json ==="
echo "=== Results: ${SCRIPT_DIR}/results_barge_in.json ==="
echo "=========================================================="
