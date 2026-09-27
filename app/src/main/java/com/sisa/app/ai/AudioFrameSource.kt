package com.sisa.app.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstraktion für Audio-Input-Quellen:
 * - MicAudioSource: echter Android AudioRecord
 * - TcpAudioSource: blockierender TCP-Server für bitgenaue Host-Injection im Benchmark
 */
interface AudioFrameSource {
    fun start()
    fun read(buffer: ShortArray, offset: Int, count: Int): Int
    fun stop()
    fun release()
}

/**
 * Standard AudioRecord-Implementierung (ehemals direkt in AudioLoop.kt).
 */
class MicAudioSource(
    private val context: Context,
    private val config: AudioLoop.Config,
    private val logger: AudioLoop.LoopLogger
) : AudioFrameSource {

    private var audioRecord: AudioRecord? = null

    override fun start() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        check(granted) { "RECORD_AUDIO not granted" }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            config.channelConfig,
            config.audioEncoding,
        )
        check(minBufferBytes > 0) { "AudioRecord minBuffer invalid: $minBufferBytes" }
        val targetBytes = maxOf(
            minBufferBytes,
            config.frameSizeSamples * 2 * 4,
        )
        val record = AudioRecord(
            config.audioSource,
            config.sampleRateHz,
            config.channelConfig,
            config.audioEncoding,
            targetBytes,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord init failed state=${record.state}"
        }

        if (AcousticEchoCanceler.isAvailable()) {
            try {
                AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                    enabled = true
                    logger.log(Log.INFO, "AcousticEchoCanceler enabled on session ${record.audioSessionId}")
                }
            } catch (t: Throwable) {
                logger.log(Log.WARN, "Failed to enable AcousticEchoCanceler", t)
            }
        }

        record.startRecording()
        audioRecord = record
    }

    override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
        val record = audioRecord ?: return -1
        return record.read(buffer, offset, count, AudioRecord.READ_BLOCKING)
    }

    override fun stop() {
        runCatching { audioRecord?.stop() }
    }

    override fun release() {
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}

/**
 * TCP Audio Source für Benchmark-Messungen:
 * Lauscht auf TCP-Port (default 4567, via `adb forward tcp:4567 tcp:4567`).
 * Liest 16-Bit Little Endian PCM blockierend in Frame-Größe und loggt
 * jedes ankommende Frame mit nanosekundengenauem SystemClock.elapsedRealtimeNanos().
 */
class TcpAudioSource(
    private val port: Int = 4567,
    private val logger: AudioLoop.LoopLogger? = null
) : AudioFrameSource {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: InputStream? = null
    private val isRunning = AtomicBoolean(false)
    private var frameSeq = 0L

    override fun start() {
        isRunning.set(true)
        logger?.log(Log.INFO, "TcpAudioSource starting server on port $port")
        val server = ServerSocket(port)
        serverSocket = server
    }

    private fun ensureClientConnected(): InputStream? {
        if (!isRunning.get()) return null
        val curInput = inputStream
        if (curInput != null && clientSocket?.isConnected == true && !clientSocket!!.isClosed) {
            return curInput
        }
        val server = serverSocket ?: return null
        return try {
            logger?.log(Log.INFO, "TcpAudioSource waiting for connection on port $port...")
            val socket = server.accept()
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 64 * 1024
            clientSocket = socket
            val stream = socket.getInputStream()
            inputStream = stream
            logger?.log(Log.INFO, "TcpAudioSource connected from ${socket.remoteSocketAddress}")
            stream
        } catch (e: Exception) {
            if (isRunning.get()) {
                logger?.log(Log.WARN, "TcpAudioSource accept error: ${e.message}")
            }
            null
        }
    }

    override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
        if (!isRunning.get()) return -1
        val byteCount = count * 2
        val byteBuf = ByteArray(byteCount)
        var totalBytesRead = 0

        while (isRunning.get() && totalBytesRead < byteCount) {
            val stream = ensureClientConnected() ?: return -1
            try {
                val read = stream.read(byteBuf, totalBytesRead, byteCount - totalBytesRead)
                if (read < 0) {
                    logger?.log(Log.INFO, "TcpAudioSource client disconnected")
                    runCatching { clientSocket?.close() }
                    clientSocket = null
                    inputStream = null
                    continue
                }
                totalBytesRead += read
            } catch (e: Exception) {
                if (isRunning.get()) {
                    logger?.log(Log.WARN, "TcpAudioSource read error: ${e.message}")
                    runCatching { clientSocket?.close() }
                    clientSocket = null
                    inputStream = null
                } else {
                    return -1
                }
            }
        }

        if (totalBytesRead < byteCount) {
            return -1
        }

        val arrivalNs = SystemClock.elapsedRealtimeNanos()
        frameSeq++
        // Loggen des Frame-Eingangs gemäß Anforderung
        Log.i("BENCH", "BENCH frame_in t_elapsed_ns=$arrivalNs seq=$frameSeq samples=$count")

        ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buffer, offset, count)
        return count
    }

    override fun stop() {
        isRunning.set(false)
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        clientSocket = null
        inputStream = null
        serverSocket = null
    }

    override fun release() {
        stop()
    }
}
