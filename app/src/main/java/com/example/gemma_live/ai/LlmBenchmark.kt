package com.example.gemma_live.ai

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SessionConfig
import com.example.gemma_live.download.AppModelManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-Device LLM Benchmark für Gemma 4 E2B via LiteRT-LM 0.17.1.
 *
 * Misst pro Backend (GPU+MTP, GPU, CPU):
 * - TTFT (Time To First Token) in ms
 * - Prefill & Decode Tokens pro Sekunde (tok/s)
 * - Peak RAM & PSS
 * - 1 Warmup-Lauf (verworfen) + [repeats] Messläufe
 */
object LlmBenchmark {

    private const val TAG = "LlmBenchmark"

    enum class Backend(val label: String) {
        GPU_MTP("GPU+MTP"),
        GPU("GPU"),
        CPU("CPU")
    }

    data class RunResult(
        val backend: Backend,
        val runIndex: Int, // 0 = Warmup
        val ttftMs: Long,
        val prefillTps: Double,
        val decodeTps: Double,
        val peakRamMb: Long,
        val ok: Boolean,
        val note: String = ""
    )

    data class Report(
        val modelFile: String,
        val modelSizeMb: Double,
        val litertVersion: String,
        val litertPresent: Boolean,
        val runs: List<RunResult>
    ) {
        fun medianPerBackend(): Map<Backend, RunResult> {
            val res = mutableMapOf<Backend, RunResult>()
            for (b in Backend.values()) {
                val measured = runs.filter { it.backend == b && it.runIndex > 0 && it.ok }
                if (measured.isEmpty()) {
                    val failed = runs.firstOrNull { it.backend == b }
                    if (failed != null) res[b] = failed
                } else {
                    val sortedByDecode = measured.sortedBy { it.decodeTps }
                    val median = sortedByDecode[sortedByDecode.size / 2]
                    res[b] = median
                }
            }
            return res
        }

        fun toShareText(): String {
            val sb = StringBuilder()
            sb.appendLine("=== Gemma-Live LLM Benchmark ===")
            sb.appendLine("Modell: $modelFile (%.1f MB)".format(modelSizeMb))
            sb.appendLine("LiteRT-LM: $litertVersion (present=$litertPresent)")
            sb.appendLine("--------------------------------------")
            val medians = medianPerBackend()
            for (b in Backend.values()) {
                val r = medians[b]
                if (r == null || !r.ok) {
                    sb.appendLine("${b.label}: FEHLGESCHLAGEN (${r?.note ?: "keine Daten"})")
                } else {
                    sb.appendLine("${b.label}: TTFT=${r.ttftMs}ms | Decode=%.1f tok/s | RAM=${r.peakRamMb}MB".format(r.decodeTps))
                }
            }
            sb.appendLine("======================================")
            return sb.toString()
        }
    }

    suspend fun run(
        context: Context,
        aiService: E2BAIService?,
        repeats: Int = 3,
        onProgress: (String) -> Unit
    ): Report = withContext(Dispatchers.Default) {
        val modelDir = AppModelManager.getModelsDir(context)
        val modelFile = File(modelDir, LocalGemmaAssistant.MODEL_FILE)
        val modelExists = modelFile.exists() && modelFile.length() > 1_000_000L

        if (!modelExists) {
            onProgress("Modell ${LocalGemmaAssistant.MODEL_FILE} nicht gefunden!")
            return@withContext Report(
                modelFile = LocalGemmaAssistant.MODEL_FILE,
                modelSizeMb = 0.0,
                litertVersion = LocalGemmaAssistant.LITERT_LM_VERSION,
                litertPresent = false,
                runs = listOf(
                    RunResult(Backend.GPU, 1, 0, 0.0, 0.0, 0, false, "Modell fehlt in getModelsDir()")
                )
            )
        }

        val modelSizeMb = modelFile.length() / (1024.0 * 1024.0)
        val allRuns = mutableListOf<RunResult>()
        val backendsToTest = listOf(Backend.GPU_MTP, Backend.GPU, Backend.CPU)

        for (backendKind in backendsToTest) {
            onProgress("Teste ${backendKind.label} …")
            val litertBackend: com.google.ai.edge.litertlm.Backend = when (backendKind) {
                Backend.CPU -> com.google.ai.edge.litertlm.Backend.CPU()
                else -> com.google.ai.edge.litertlm.Backend.GPU()
            }

            var engine: Engine? = null
            try {
                val cfg = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = litertBackend,
                    maxNumTokens = 4096
                )
                val e = Engine(cfg)
                e.initialize()
                engine = e

                // 1 Warmup + repeats Messläufe
                for (runIdx in 0..repeats) {
                    val isWarmup = (runIdx == 0)
                    val runName = if (isWarmup) "Warmup" else "Run $runIdx/$repeats"
                    onProgress("${backendKind.label}: $runName läuft …")

                    val startNs = SystemClock.elapsedRealtimeNanos()
                    var firstTokenNs = 0L
                    var tokenCount = 0
                    val done = CompletableDeferred<Boolean>()

                    val session = e.createSession(
                        SessionConfig(
                            samplerConfig = SamplerConfig(
                                topK = 40,
                                topP = 0.95,
                                temperature = 0.7
                            )
                        )
                    )

                    try {
                        val callback = object : ResponseCallback {
                            override fun onNext(text: String) {
                                if (text.isNotEmpty()) {
                                    if (firstTokenNs == 0L) {
                                        firstTokenNs = SystemClock.elapsedRealtimeNanos()
                                    }
                                    tokenCount++
                                }
                            }

                            override fun onDone() {
                                done.complete(true)
                            }

                            override fun onError(t: Throwable) {
                                Log.e(TAG, "Run failed: ${t.message}")
                                done.complete(false)
                            }
                        }

                        val inputs = listOf(
                            InputData.Text("You are a helpful assistant."),
                            InputData.Text("Hello Gemma, how are you today?")
                        )

                        session.generateContentStream(inputs, callback)
                        val success = done.await()
                        val endNs = SystemClock.elapsedRealtimeNanos()

                        val ttftMs = if (firstTokenNs > 0L) (firstTokenNs - startNs) / 1_000_000L else 0L
                        val decodeDurationSec = if (firstTokenNs > 0L) (endNs - firstTokenNs) / 1_000_000_000.0 else 0.0
                        val decodeTps = if (decodeDurationSec > 0.0 && tokenCount > 1) (tokenCount - 1) / decodeDurationSec else 0.0
                        val ramMb = getProcessMemoryMb(context)

                        Log.i(
                            "BENCH",
                            "BENCH llm_bench_run backend=${backendKind.label} run=$runIdx " +
                                "warmup=$isWarmup ttft_ms=$ttftMs decode_tps=%.2f tokens=$tokenCount ram_mb=$ramMb".format(decodeTps)
                        )

                        allRuns.add(
                            RunResult(
                                backend = backendKind,
                                runIndex = runIdx,
                                ttftMs = ttftMs,
                                prefillTps = 0.0,
                                decodeTps = decodeTps,
                                peakRamMb = ramMb,
                                ok = success
                            )
                        )
                    } finally {
                        runCatching { session.close() }
                    }
                    delay(150)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Backend ${backendKind.label} fehlgeschlagen", t)
                Log.w(
                    "BENCH",
                    "BENCH llm_bench_error backend=${backendKind.label} error=${t.javaClass.simpleName}: ${t.message}"
                )
                allRuns.add(
                    RunResult(
                        backend = backendKind,
                        runIndex = 1,
                        ttftMs = 0,
                        prefillTps = 0.0,
                        decodeTps = 0.0,
                        peakRamMb = 0,
                        ok = false,
                        note = "${t.javaClass.simpleName}: ${t.message}"
                    )
                )
            } finally {
                runCatching { engine?.close() }
            }
        }

        Report(
            modelFile = modelFile.name,
            modelSizeMb = modelSizeMb,
            litertVersion = LocalGemmaAssistant.LITERT_LM_VERSION,
            litertPresent = true,
            runs = allRuns
        )
    }

    private fun getProcessMemoryMb(context: Context): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pids = intArrayOf(android.os.Process.myPid())
            val memInfo = am.getProcessMemoryInfo(pids)
            if (memInfo.isNotEmpty()) {
                memInfo[0].totalPss / 1024L
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }
}
