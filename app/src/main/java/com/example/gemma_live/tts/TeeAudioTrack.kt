package com.example.gemma_live.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrappt einen Android AudioTrack und schreibt gleichzeitig alle ausgegebenen PCM-Daten
 * bitgenau zusammen mit t_elapsed_ns Timestamps in eine Datei unter
 * context.getExternalFilesDir("bench")/tee_<timestamp>.pcm.
 *
 * Loggt außerdem BENCH tts_chunk_first und BENCH tts_chunk sowie BENCH barge_flush.
 */
class TeeAudioTrack(
    private val context: Context,
    private val sampleRate: Int = 16_000,
    private val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val bufferSize: Int = 16_000 * 2 / 5,
    /**
     * Abtastrate des Ausgabegeräts (AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE,
     * auf dem Pixel 8a 48000 Hz). Der AAudio-Fast-Mixer des Primär-Ausgangs
     * arbeitet ausschließlich mit der nativen Rate; ein 16-kHz-Track landet daher
     * im Legacy-Mixer (getPerformanceMode() == PERFORMANCE_MODE_NONE, gemessen
     * `perf_mode=0`). Das Playback läuft deshalb mit der Geräterate, während der
     * Tee-Mitschnitt (bitgenau, Basis der Offline-TTS-Validierung in metrics.py)
     * unverändert mit [sampleRate] erfolgt.
     */
    private val outputSampleRate: Int = sampleRate
) {
    private val track: AudioTrack
    private var fileOutputStream: FileOutputStream? = null
    private var isFirstChunk = true
    private var file: File? = null
    /** Callback, wenn die Wiedergabe den Marker erreicht hat (also: Audio ist wirklich raus). */
    private var onPlaybackComplete: (() -> Unit)? = null
    private var markerSet = false
    private var completeFired = false
    private val handler = Handler(Looper.getMainLooper())
    private var safetyRunnable: Runnable? = null

    /**
     * Frames, die seit dem letzten [flush] in den Track geschrieben wurden (Wiedergabe-Rate).
     * Da flush() die playbackHeadPosition auf 0 zurücksetzt, ist das absolute Frame-Ende der
     * Wiedergabe genau dieser Zähler — der Marker MUSS dorthin zeigen. Eine Schätzung
     * (head + bufferSizeInFrames) feuert zu früh: der Low-Latency-Puffer fasst nur ~60 ms,
     * ein TTS-Chunk dauert aber 300-800 ms, dadurch war isSpeaking längst wieder false,
     * während der Lautsprecher noch sprach (Echo-Leak).
     */
    private var writtenFrames: Long = 0L

    // --- Playback-Resampling (nur aktiv, wenn Geräterate != TTS-Rate) ---------
    private val playbackRate: Int = if (outputSampleRate > 0) outputSampleRate else sampleRate
    private val resampling: Boolean = playbackRate != sampleRate
    private val ratio: Double = playbackRate.toDouble() / sampleRate.toDouble()
    // Bruchteil-Position im Eingabestrom, über write()-Grenzen hinweg fortgeführt,
    // damit an Chunk-Grenzen keine Sprünge (Klicks) entstehen.
    private var phase: Double = 0.0
    // Letzter Sample des vorherigen write() für die Interpolation über Chunk-Grenzen.
    private var lastSample: Float = 0f
    private var playbackScratch = ShortArray(0)
    /** FastTrack braucht beim Beginn einer neuen Ausgabe einen stabilen Vorlauf, sonst kann
     * der erste DMA-Puffer (und damit die ersten Silben) unterlaufen. */
    private var needsPriming = true

    init {
        // AAudio Low-Latency FastTrack: PERFORMANCE_MODE_LOW_LATENCY route den Stream
        // über den kurzen AAudio-Fast-Mixer-Pfad (~10-20ms statt der ~200ms Legacy-
        // Puffer), was die Barge-In-Reaktionszeit deutlich senkt (siehe HANDOFF.md
        // "AAudio FastTrack"). Builder.setBufferSizeInBytes() ist bei
        // PERFORMANCE_MODE_LOW_LATENCY nur ein Hint; die tatsächliche Puffergröße
        // wird intern vom AAudio Fast-Mixer bestimmt.
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(playbackRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
            .apply {
                play()
            }

        // Playback-Position-Notification: Callback feuert, wenn playbackHeadPosition >= markerPosition
        // Wird genutzt, um markNotSpeaking() erst nach echter Wiedergabe-Fertigstellung aufzurufen
        // (statt nach Schreib-Fertigstellung, was zu früh ist).
        track.setNotificationMarkerPosition(Int.MAX_VALUE)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onPeriodicNotification(tracker: AudioTrack) {}
            override fun onMarkerReached(tracker: AudioTrack) {
                if (markerSet) firePlaybackComplete("marker")
            }
        })

        // Verifikation des Low-Latency-Pfads für die Benchmark-Auswertung:
        // performanceMode == PERFORMANCE_MODE_LOW_LATENCY (1) => AAudio FastTrack aktiv,
        // andernfalls fiel das System auf den Legacy-Mixer zurück. srcRate/outRate
        // machen sichtbar, ob die Geräterate getroffen wurde (Voraussetzung für FastTrack).
        Log.i(
            "BENCH",
            "BENCH audiotrack_ready t_elapsed_ns=${SystemClock.elapsedRealtimeNanos()} " +
                "perf_mode=${track.performanceMode} " +
                "bufferFrames=${track.bufferSizeInFrames} " +
                "minBufferSize=$bufferSize srcRate=$sampleRate outRate=$playbackRate " +
                "session=${track.audioSessionId}"
        )

        initTeeFile()
    }

    private fun initTeeFile() {
        try {
            val benchDir = File(context.getExternalFilesDir("bench") ?: File(context.filesDir, "bench"), ".")
            if (!benchDir.exists()) benchDir.mkdirs()
            val f = File(benchDir, "tee_${System.currentTimeMillis()}.pcm")
            file = f
            fileOutputStream = FileOutputStream(f, true)
            Log.i("BENCH", "TeeAudioTrack tee file initialized: ${f.absolutePath}")
        } catch (e: Exception) {
            Log.e("BENCH", "Failed to init tee file", e)
        }
    }

    val playbackHeadPosition: Int
        get() = track.playbackHeadPosition

    fun play() {
        track.play()
    }

    fun pause() {
        track.pause()
    }

    /**
     * Schreibt AudioData in AudioTrack und spuckt PCM + Timestamp in die Tee-Datei.
     * Record Format:
     * - 8 Bytes Long: SystemClock.elapsedRealtimeNanos()
     * - 4 Bytes Int: sizeInShorts
     * - sizeInShorts * 2 Bytes: PCM 16-Bit Little Endian
     */
    @Synchronized
    fun write(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val head = track.playbackHeadPosition

        if (needsPriming) {
            needsPriming = false
            // 80 ms war auf dem Pixel noch zu knapp: beim Aufwachen des Fast-Mixers
            // verschwand der erste TTS-Puffer hörbar. 200 ms sind nur Stille vor dem
            // Satz, geben Route, Verstärker und DMA aber zuverlässig Zeit zum Anlaufen.
            val primeFrames = playbackRate * 200 / 1000
            val primed = track.write(ShortArray(primeFrames), 0, primeFrames)
            if (primed > 0) writtenFrames += primed
            Log.i("BENCH", "BENCH tts_playback_primed t_elapsed_ns=$nowNs frames=$primeFrames")
        }

        if (isFirstChunk) {
            isFirstChunk = false
            Log.i("BENCH", "BENCH tts_chunk_first t_elapsed_ns=$nowNs samples=$sizeInShorts head=$head")
        } else {
            Log.i("BENCH", "BENCH tts_chunk t_elapsed_ns=$nowNs samples=$sizeInShorts head=$head")
        }

        // In Datei spiegeln
        var fos = fileOutputStream
        if (fos == null || file?.exists() != true) {
            initTeeFile()
            fos = fileOutputStream
        }
        fos?.let { stream ->
            try {
                val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                header.putLong(nowNs)
                header.putInt(sizeInShorts)
                stream.write(header.array())

                val pcmBuf = ByteBuffer.allocate(sizeInShorts * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in offsetInShorts until (offsetInShorts + sizeInShorts)) {
                    pcmBuf.putShort(audioData[i])
                }
                stream.write(pcmBuf.array())
                stream.flush()
            } catch (e: Exception) {
                Log.w("BENCH", "Tee write error: ${e.message}")
            }
        }

        val written = if (resampling) {
            resampleForPlayback(audioData, offsetInShorts, sizeInShorts)
        } else {
            track.write(audioData, offsetInShorts, sizeInShorts)
        }
        // Mono: 1 Short == 1 Frame. Zähler ist die Referenz für den Playback-Ende-Marker.
        if (written > 0) writtenFrames += written
        return written
    }

    /**
     * Lineare Interpolation mit über write()-Grenzen fortgeführter Phase.
     * Für 16 kHz -> 48 kHz (Faktor 3) entspricht das der klassischen
     * Upsampling-Interpolation: die Spiegelspektren liegen außerhalb des für
     * Sprache relevanten Bands, die interpolierende Filtercharakteristik wirkt
     * zusätzlich mild tiefpassartig. Der Trick (Phase + letzter Sample bleiben
     * erhalten) verhindert Knackser an Chunk-Grenzen.
     */
    private fun resampleForPlayback(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int {
        if (sizeInShorts <= 0) return 0
        // Bei ratio = 48000 / 16000 = 3.0 werden aus N 16kHz-Samples N * 3 48kHz-Samples!
        val needed = (sizeInShorts * ratio).toInt() + 16
        if (playbackScratch.size < needed) playbackScratch = ShortArray(needed)
        val out = playbackScratch

        // phase ist im Bereich [0, 1.0) der Eingabesample-Schritte
        var pos = phase.coerceIn(0.0, 1.0)
        var idx = 0
        val end = offsetInShorts + sizeInShorts
        val step = 1.0 / ratio // z.B. 1.0 / 3.0 = 0.3333333333333333

        while (pos < sizeInShorts && idx < needed) {
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            val curIdx = (offsetInShorts + i).coerceIn(offsetInShorts, end - 1)
            val prevIdx = offsetInShorts + i - 1

            val s0 = if (prevIdx < offsetInShorts) lastSample else audioData[prevIdx].toFloat()
            val s1 = audioData[curIdx].toFloat()

            out[idx++] = kotlin.math.round(s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
            pos += step
        }
        phase = (pos - sizeInShorts).coerceAtLeast(0.0)
        lastSample = audioData[end - 1].toFloat()
        return track.write(out, 0, idx)
    }

    @Synchronized
    fun flush(reason: String = "manual") {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val head = track.playbackHeadPosition
        Log.i("BENCH", "BENCH barge_flush t_elapsed_ns=$nowNs reason=$reason head=$head")
        try {
            track.flush()
        } catch (e: Exception) {
            Log.w("BENCH", "Track flush error", e)
        }
        // flush() setzt die playbackHeadPosition auf 0 zurück — der Marker des verworfenen
        // Chunks darf nicht mehr feuern, sonst wird isSpeaking mitten im Folge-Chunk false.
        invalidatePendingComplete()
        writtenFrames = 0L
        // Resampling-Zustand zurücksetzen: nach einem Flush beginnt ein neuer
        // Sprechabschnitt, die Interpolation darf nicht über die Lücke hinweg
        // den letzten Sample fortschreiben.
        phase = 0.0
        lastSample = 0f
        isFirstChunk = true
        needsPriming = true
    }

    fun stop() {
        invalidatePendingComplete()
        try {
            track.stop()
        } catch (_: Exception) {}
    }

    /**
     * Callback registrieren für Wiedergabe-Ende (Echtes Audio-Ende, nicht Schreib-Ende).
     * Wird gefeuert, wenn die Playback-Head-Position den Marker erreicht.
     */
    fun setPlaybackCompleteListener(listener: () -> Unit) {
        onPlaybackComplete = listener
    }

    /**
     * Beginn eines neuen Sprechabschnitts: einen noch offenen Marker des Vorgängers
     * entwerten. Sonst würde dessen Position (Ende des vorigen Chunks) mitten in der
     * neuen Ausgabe erreicht und isSpeaking vorzeitig auf false setzen.
     */
    @Synchronized
    fun invalidatePendingComplete() {
        markerSet = false
        completeFired = false
        safetyRunnable?.let { handler.removeCallbacks(it) }
        safetyRunnable = null
        try {
            track.setNotificationMarkerPosition(Int.MAX_VALUE)
        } catch (_: Exception) {}
    }

    /**
     * Marker ans echte Ende der geschriebenen Daten setzen. write() zählt die Frames in
     * [writtenFrames] und flush() setzt Head und Zähler gemeinsam zurück, daher ist
     * [writtenFrames] die exakte Position, ab der das Audio wirklich aus dem Lautsprecher
     * gekommen ist. Ein Safety-Timer stellt sicher, dass der Callback auch dann feuert,
     * wenn der Marker wegen Underrun/Pause nicht erreicht wird — sonst bliebe isSpeaking
     * dauerhaft true und die App wäre taub (alle Segmente als Echo verworfen).
     */
    @Synchronized
    fun triggerPlaybackComplete() {
        invalidatePendingComplete()
        val head = track.playbackHeadPosition
        val target = writtenFrames
        val pending = (target - head).coerceAtLeast(0L)
        val fire = Runnable { firePlaybackComplete("safety") }
        safetyRunnable = fire
        Log.i(
            "BENCH",
            "BENCH playback_marker_set t_elapsed_ns=${SystemClock.elapsedRealtimeNanos()} " +
                "pos=$target head=$head pendingFrames=$pending rate=$playbackRate " +
                "pendingMs=${pending * 1000L / playbackRate}"
        )
        if (pending == 0L) {
            handler.post(fire)
            return
        }
        if (target < Int.MAX_VALUE) {
            track.setNotificationMarkerPosition(target.toInt())
            markerSet = true
        }
        handler.postDelayed(fire, pending * 1000L / playbackRate + SAFETY_MARGIN_MS)
    }

    /** Einmalig: Callback auslösen, Marker/Safety-Timer abräumen. */
    @Synchronized
    private fun firePlaybackComplete(reason: String) {
        if (completeFired) return
        completeFired = true
        markerSet = false
        safetyRunnable?.let { handler.removeCallbacks(it) }
        safetyRunnable = null
        try {
            track.setNotificationMarkerPosition(Int.MAX_VALUE)
        } catch (_: Exception) {}
        val head = runCatching { track.playbackHeadPosition }.getOrDefault(-1)
        Log.i(
            "BENCH",
            "BENCH playback_complete t_elapsed_ns=${SystemClock.elapsedRealtimeNanos()} " +
                "reason=$reason head=$head written=$writtenFrames rate=$playbackRate"
        )
        onPlaybackComplete?.invoke()
    }

    fun release() {
        invalidatePendingComplete()
        try {
            track.release()
            fileOutputStream?.close()
        } catch (_: Exception) {}
        fileOutputStream = null
    }

    companion object {
        /** Zusatzpuffer für den Safety-Timer: Puffer-Unterlauf, Track-Pause, Mixer-Latenz. */
        private const val SAFETY_MARGIN_MS = 600L
    }
}
