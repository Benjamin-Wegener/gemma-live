package com.example.gemma_live.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.example.gemma_live.download.AppModelManager
import java.io.File

/**
 * Paket 1: Turn-Erkennung mit echtem Silero-VAD (sherpa-onnx, aus der
 * bereits gebündelten AAR — keine neue Dependency).
 * Energie-Gate bleibt nur als Rückfall, falls das Modell fehlt.
 */
class TurnDetector(
    private val context: Context,
    private val logger: DetectorLogger = AndroidDetectorLogger(),
    private val config: Config = Config(),
) {

    data class Config(
        val speechStartThresholdDb: Float = -38f,
        val speechStopThresholdDb: Float = -46f,
        val minSpeechFrames: Int = 3,
        // 25 × 32 ms = 800 ms. Kurze natürliche Pausen innerhalb eines Satzes
        // dürfen keinen neuen KI-Turn auslösen.
        val minSilenceFrames: Int = 25,
        val modelAssetPath: String = "vad/silero_vad.onnx",
        val modelFilename: String = "silero_vad.onnx",
    )

    sealed interface Event {
        data class SpeechStarted(
            val startedAtElapsedMs: Long,
            val rmsDb: Float,
            val peak: Int,
        ) : Event

        data class SpeechStopped(
            val stoppedAtElapsedMs: Long,
            val rmsDb: Float,
            val peak: Int,
            val speechDurationMs: Long,
            val audioSamples: FloatArray? = null,
        ) : Event
    }

    fun interface EventListener {
        fun onEvent(event: Event)
    }

    interface DetectorLogger {
        fun log(level: Int, message: String, error: Throwable? = null)
    }

    private class AndroidDetectorLogger : DetectorLogger {
        override fun log(level: Int, message: String, error: Throwable?) {
            when (level) {
                Log.ERROR -> Log.e(TAG, message, error)
                Log.WARN -> Log.w(TAG, message, error)
                else -> Log.i(TAG, message, error)
            }
        }
    }

    private var isSpeechActive = false
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    private var speechStartedAtElapsedMs = 0L
    private var sileroModelFile: File? = null
    private var vad: Vad? = null

    fun prepare() {
        sileroModelFile = resolveModelFile()
        vad = try {
            val silero = SileroVadModelConfig().apply {
                // Erst Asset-Pfad (sherpa öffnet via AssetManager), sonst Datei
                model = config.modelAssetPath
                threshold = 0.5f
                minSilenceDuration = 0.80f // Satzende, keine Binnenpause
                minSpeechDuration = 0.15f  // 150ms
            }
            val vadConfig = VadModelConfig().apply {
                sileroVadModelConfig = silero
                sampleRate = 16_000
                numThreads = 1
            }
            Vad(context.assets, vadConfig).also {
                logger.log(Log.INFO, "turn_detector prepared mode=silero model=${config.modelAssetPath}")
            }
        } catch (t: Throwable) {
            logger.log(Log.WARN, "turn_detector silero init via assets failed, trying file", t)
            try {
                val file = sileroModelFile ?: throw IllegalStateException("no model file")
                val silero = SileroVadModelConfig().apply {
                    model = file.absolutePath
                    threshold = 0.5f
                    minSilenceDuration = 0.80f // Satzende, keine Binnenpause
                    minSpeechDuration = 0.15f
                }
                val vadConfig = VadModelConfig().apply {
                    sileroVadModelConfig = silero
                    sampleRate = 16_000
                    numThreads = 1
                }
                Vad(context.assets, vadConfig).also {
                    logger.log(Log.INFO, "turn_detector prepared mode=silero model=${file.absolutePath}")
                }
            } catch (t2: Throwable) {
                logger.log(Log.WARN, "turn_detector silero unavailable, energy_gate fallback", t2)
                null
            }
        }
        if (vad == null) {
            logger.log(
                Log.INFO,
                "turn_detector prepared mode=energy_gate model=${sileroModelFile?.absolutePath ?: "missing"}",
            )
        }
    }

    fun process(frame: AudioLoop.AudioFrame, listener: EventListener) {
        val engine = vad
        if (engine != null) {
            processSilero(engine, frame, listener)
        } else {
            processEnergy(frame, listener)
        }
    }

    private fun processSilero(vad: Vad, frame: AudioLoop.AudioFrame, listener: EventListener) {
        val floats = FloatArray(frame.samples.size) { i -> frame.samples[i] / 32768f }
        try {
            vad.acceptWaveform(floats)
        } catch (t: Throwable) {
            logger.log(Log.WARN, "turn_detector silero accept failed", t)
            return
        }
        try {
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                // start/samples in Samples @16 kHz -> Dauer exakt, Wandzeit genähert
                val durationMs = (seg.samples.size / 16).toLong()
                val endMs = frame.startedAtElapsedMs
                val startMs = (endMs - durationMs).coerceAtLeast(0L)
                val nowNs = SystemClock.elapsedRealtimeNanos()
                Log.i("BENCH", "BENCH speech_started t_elapsed_ns=$nowNs mode=silero startSample=${seg.start} durationMs=$durationMs")
                logger.log(Log.INFO, "speech_started silero startSample=${seg.start} durationMs=$durationMs rmsDb=${frame.rmsDb}")
                listener.onEvent(
                    Event.SpeechStarted(
                        startedAtElapsedMs = startMs,
                        rmsDb = frame.rmsDb,
                        peak = frame.peak,
                    )
                )
                Log.i("BENCH", "BENCH speech_stopped t_elapsed_ns=$nowNs mode=silero durationMs=$durationMs")
                logger.log(Log.INFO, "speech_stopped silero startSample=${seg.start} durationMs=$durationMs")
                listener.onEvent(
                    Event.SpeechStopped(
                        stoppedAtElapsedMs = endMs,
                        rmsDb = frame.rmsDb,
                        peak = frame.peak,
                        speechDurationMs = durationMs,
                        audioSamples = seg.samples,
                    )
                )
            }
        } catch (t: Throwable) {
            logger.log(Log.WARN, "turn_detector silero drain failed", t)
        }
    }

    private fun processEnergy(frame: AudioLoop.AudioFrame, listener: EventListener) {
        val isSpeechCandidate = frame.rmsDb >= activeThreshold()
        if (isSpeechCandidate) {
            consecutiveSpeechFrames += 1
            consecutiveSilenceFrames = 0
            if (!isSpeechActive && consecutiveSpeechFrames >= config.minSpeechFrames) {
                isSpeechActive = true
                speechStartedAtElapsedMs = frame.startedAtElapsedMs
                val nowNs = SystemClock.elapsedRealtimeNanos()
                Log.i("BENCH", "BENCH speech_started t_elapsed_ns=$nowNs mode=energy_gate rmsDb=${frame.rmsDb}")
                logger.log(
                    Log.INFO,
                    "speech_started rmsDb=${frame.rmsDb} peak=${frame.peak} startMs=${frame.startedAtElapsedMs}",
                )
                listener.onEvent(
                    Event.SpeechStarted(
                        startedAtElapsedMs = frame.startedAtElapsedMs,
                        rmsDb = frame.rmsDb,
                        peak = frame.peak,
                    )
                )
            }
            return
        }

        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames += 1
        if (isSpeechActive && consecutiveSilenceFrames >= config.minSilenceFrames) {
            isSpeechActive = false
            val duration = (frame.startedAtElapsedMs - speechStartedAtElapsedMs).coerceAtLeast(0L)
            val nowNs = SystemClock.elapsedRealtimeNanos()
            Log.i("BENCH", "BENCH speech_stopped t_elapsed_ns=$nowNs mode=energy_gate durationMs=$duration")
            logger.log(
                Log.INFO,
                "speech_stopped rmsDb=${frame.rmsDb} peak=${frame.peak} stopMs=${frame.startedAtElapsedMs} durationMs=$duration",
            )
            listener.onEvent(
                Event.SpeechStopped(
                    stoppedAtElapsedMs = frame.startedAtElapsedMs,
                    rmsDb = frame.rmsDb,
                    peak = frame.peak,
                    speechDurationMs = duration,
                )
            )
        }
    }

    fun reset() {
        isSpeechActive = false
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
        speechStartedAtElapsedMs = 0L
    }

    private fun activeThreshold(): Float {
        return if (isSpeechActive) config.speechStopThresholdDb else config.speechStartThresholdDb
    }

    private fun resolveModelFile(): File? {
        val modelsDir = AppModelManager.getModelsDir(context)
        val modelFile = File(modelsDir, config.modelFilename)
        if (modelFile.exists() && modelFile.length() > 1024) {
            return modelFile
        }
        return try {
            context.assets.open(config.modelAssetPath).use { input ->
                modelFile.parentFile?.mkdirs()
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
            modelFile.takeIf { it.exists() && it.length() > 1024 }
        } catch (t: Throwable) {
            logger.log(Log.WARN, "turn_detector model copy failed path=${config.modelAssetPath}", t)
            null
        }
    }

    companion object {
        private const val TAG = "GemmaVoice"
    }
}
