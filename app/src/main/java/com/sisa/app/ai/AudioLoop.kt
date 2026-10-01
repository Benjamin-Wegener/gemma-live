package com.sisa.app.ai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.sisa.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * Endlos-Audio-Loop für Live-Interaktion und Benchmarking.
 * Unterstützt dynamisch oder per Build-Typ konfigurierbare AudioFrameSource:
 * - Im Benchmark-Build: Default TcpAudioSource(4567)
 * - Im Release/Normal-Build: Default MicAudioSource (AudioRecord)
 */
class AudioLoop(
    private val context: Context,
    private val config: Config = Config(),
    private val logger: LoopLogger = AndroidLoopLogger(),
) {

    data class Config(
        val sampleRateHz: Int = 16_000,
        val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
        val audioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
        val frameSizeSamples: Int = 512,
        val ringBufferFrames: Int = 512,
        val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    )

    data class AudioFrame(
        val samples: ShortArray,
        val sampleRateHz: Int,
        val startedAtElapsedMs: Long,
        val rmsDb: Float,
        val peak: Int,
    )

    fun interface FrameListener {
        fun onFrame(frame: AudioFrame)
    }

    interface LoopLogger {
        fun log(level: Int, message: String, error: Throwable? = null)
    }

    private class AndroidLoopLogger : LoopLogger {
        override fun log(level: Int, message: String, error: Throwable?) {
            when (level) {
                Log.ERROR -> Log.e(TAG, message, error)
                Log.WARN -> Log.w(TAG, message, error)
                else -> Log.i(TAG, message, error)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()
    private var recordJob: Job? = null
    private var activeSource: AudioFrameSource? = null
    private var ringBuffer = ShortArray(config.frameSizeSamples * config.ringBufferFrames)
    private var writeIndex = 0
    private var totalFramesRead = 0L

    suspend fun start(
        listener: FrameListener
    ) {
        start(sourceOverride = null, listener = listener)
    }

    suspend fun start(
        sourceOverride: AudioFrameSource? = null,
        listener: FrameListener
    ) {
        stateMutex.withLock {
            if (recordJob?.isActive == true) {
                logger.log(Log.WARN, "audio_loop already running")
                return
            }

            val source: AudioFrameSource = sourceOverride ?: if (BuildConfig.BENCHMARK_MODE) {
                logger.log(Log.INFO, "audio_loop in BENCHMARK_MODE -> using TcpAudioSource(port=4567)")
                TcpAudioSource(port = 4567, logger = logger)
            } else {
                MicAudioSource(context, config, logger)
            }

            try {
                source.start()
            } catch (t: Throwable) {
                logger.log(Log.ERROR, "audio_loop failed to start source", t)
                source.release()
                throw t
            }
            activeSource = source

            recordJob = scope.launch {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                logger.log(
                    Log.INFO,
                    "audio_loop started sampleRate=${config.sampleRateHz} frameSize=${config.frameSizeSamples} ringFrames=${config.ringBufferFrames} source=${source::class.simpleName}"
                )
                val tempBuffer = ShortArray(config.frameSizeSamples)
                try {
                    while (isActive) {
                        val read = source.read(tempBuffer, 0, tempBuffer.size)
                        if (read <= 0) {
                            if (!isActive) break
                            logger.log(Log.WARN, "audio_loop read returned $read")
                            continue
                        }
                        val samples = if (read == tempBuffer.size) tempBuffer.copyOf() else tempBuffer.copyOf(read)
                        appendToRing(samples, read)
                        totalFramesRead += 1
                        listener.onFrame(
                            AudioFrame(
                                samples = samples,
                                sampleRateHz = config.sampleRateHz,
                                startedAtElapsedMs = SystemClock.elapsedRealtime(),
                                rmsDb = calculateRmsDb(samples, read),
                                peak = calculatePeak(samples, read),
                            )
                        )
                    }
                } catch (t: Throwable) {
                    logger.log(Log.ERROR, "audio_loop failed", t)
                } finally {
                    runCatching { source.stop() }
                    runCatching { source.release() }
                    if (activeSource === source) activeSource = null
                    logger.log(Log.INFO, "audio_loop stopped frames=$totalFramesRead")
                }
            }
        }
    }

    suspend fun stop() {
        stateMutex.withLock {
            val job = recordJob
            recordJob = null
            activeSource?.let { runCatching { it.stop() } }
            if (job != null) {
                job.cancelAndJoin()
            }
            activeSource?.let { runCatching { it.release() } }
            activeSource = null
        }
    }

    fun snapshot(): ShortArray {
        val copy = ShortArray(ringBuffer.size)
        val firstLen = ringBuffer.size - writeIndex
        System.arraycopy(ringBuffer, writeIndex, copy, 0, firstLen)
        System.arraycopy(ringBuffer, 0, copy, firstLen, writeIndex)
        return copy
    }

    private fun appendToRing(samples: ShortArray, count: Int) {
        var remaining = count
        var srcIndex = 0
        while (remaining > 0) {
            val chunk = min(remaining, ringBuffer.size - writeIndex)
            System.arraycopy(samples, srcIndex, ringBuffer, writeIndex, chunk)
            writeIndex = (writeIndex + chunk) % ringBuffer.size
            srcIndex += chunk
            remaining -= chunk
        }
    }

    private fun calculatePeak(samples: ShortArray, count: Int): Int {
        var peak = 0
        for (i in 0 until count) {
            val value = kotlin.math.abs(samples[i].toInt())
            if (value > peak) peak = value
        }
        return peak
    }

    private fun calculateRmsDb(samples: ShortArray, count: Int): Float {
        if (count <= 0) return RMS_DB_FLOOR
        var energy = 0.0
        for (i in 0 until count) {
            val normalized = samples[i] / Short.MAX_VALUE.toDouble()
            energy += normalized * normalized
        }
        val rms = kotlin.math.sqrt(energy / count).coerceAtLeast(1e-9)
        return (20.0 * kotlin.math.log10(rms)).toFloat().coerceAtLeast(RMS_DB_FLOOR)
    }

    companion object {
        private const val TAG = "GemmaVoice"
        private const val RMS_DB_FLOOR = -120f
    }
}
