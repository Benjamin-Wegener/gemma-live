package com.sisa.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.sisa.app.download.AppModelManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Sisa-Stimme (Kerstin, Piper VITS, weiblich) via sherpa-onnx.
 * Das Modell liegt in der APK und wird beim ersten Start CRC-geprüft
 * in den App-Speicher entpackt — kein Download.
 *
 * Garantie: [speak] wartet auf die Engine (bis zu 60 s), statt sofort auf
 * Android-TTS auszuweichen. Fallback nur, wenn die Engine fehlt/fehlschlägt.
 * [onDone] meldet das echte Wiedergabe-Ende (AudioTrack-Marker bzw.
 * TTS-Utterance-Callback), damit Dialoge erst danach zuhören.
 */
class SisaVoiceService(
    private val context: Context,
    private val fallback: AndroidTtsService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var initializing = false
    @Volatile private var stopRequested = false
    /** True solange eigene TTS-Ausgabe läuft — VAD nutzt das für Echo-Schutz / Barge-In. */
    @Volatile var isSpeaking: Boolean = false
        private set
    /**
     * elapsedRealtimeNanos des physischen Audioendes (Playback-Marker), 0 solange gesprochen wird.
     * Der VAD braucht nach dem letzten Lautsprecher-Sample noch ~100-400ms, bevor er wieder
     * "Stille" meldet (Nachhall + ausklingender Konus) — genau in diesem Fenster entstehen
     * VAD-Segmente, die nur der eigene Nachhall sind. MainActivity verwirft sie als Echo.
     */
    @Volatile var audioEndedNs: Long = 0L
        private set
    @Volatile private var pendingDone: (() -> Unit)? = null
    private val readySignal = CompletableDeferred<Boolean>()

    val isReady: Boolean get() = tts != null

    private fun takeDone(): (() -> Unit)? {
        val d = pendingDone
        pendingDone = null
        return d
    }

    private fun fireDone() {
        takeDone()?.let { cb -> mainHandler.post { cb() } }
    }

    /** Nur bei echter Unterbrechung / Shutdown: sonst bleibt isSpeaking über Chunk-Grenzen true. */
    private fun markNotSpeaking() {
        mainHandler.removeCallbacks(notSpeakingTail)
        isSpeaking = false
    }

    /** Start einer Ausgabe: kein ausstehender Tail-Timer darf isSpeaking vorzeitig löschen. */
    private fun beginSpeaking() {
        mainHandler.removeCallbacks(notSpeakingTail)
        audioEndedNs = 0L
        isSpeaking = true
    }

    /**
     * Nach dem echten Audioende noch [ECHO_TAIL_MS] "Sprechen" halten. Ein VAD-Segment, das
     * in diesem Fenster startet, ist mit hoher Wahrscheinlichkeit der eigene Nachhall; es wird
     * nur dann behalten, wenn es laut genug für eine echte Nahbesprechung ist (Barge-In).
     */
    private fun markSpeakingAfterEchoTail() {
        audioEndedNs = android.os.SystemClock.elapsedRealtimeNanos()
        mainHandler.removeCallbacks(notSpeakingTail)
        mainHandler.postDelayed(notSpeakingTail, ECHO_TAIL_MS)
    }

    private val notSpeakingTail = Runnable { markNotSpeaking() }

    /** Asynchron initialisieren (Modell laden dauert beim 1. Start ~30 s). */
    fun initAsync(onReady: ((Boolean) -> Unit)? = null) {
        if (tts != null || initializing) {
            val ready = tts != null
            if (!readySignal.isCompleted) readySignal.complete(ready)
            onReady?.invoke(ready)
            return
        }
        initializing = true
        scope.launch {
            val ok = try {
                val ensured = withContext(Dispatchers.IO) {
                    AppModelManager.ensureVoiceFromBundle(context)
                }
                if (!ensured) {
                    false
                } else {
                    val dir = AppModelManager.getModelsDir(context)
                    val modelPath = File(dir, AppModelManager.VOICE_MODEL_FILE).absolutePath
                    val tokensPath = File(dir, AppModelManager.VOICE_TOKENS_FILE).absolutePath
                    val espeakDataDir = File(dir, AppModelManager.ESPEAK_DATA_DIR).absolutePath
                    // Piper VITS: Modell + Phonem-Tokens + espeak-ng-data (Phonemisierung)
                    val vits = OfflineTtsVitsModelConfig(model = modelPath, tokens = tokensPath, dataDir = espeakDataDir)
                    val model = OfflineTtsModelConfig(vits = vits, numThreads = 2, provider = "cpu")
                    val config = OfflineTtsConfig(model = model, maxNumSentences = 1)
                    tts = OfflineTts(config = config)
                    Log.i(TAG, "Sisa-Stimme bereit (Abtastrate ${tts?.sampleRate()} Hz)")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sisa-Stimme Init fehlgeschlagen, Fallback Android-TTS", e)
                try { tts?.free() } catch (_: Exception) {}
                tts = null
                false
            }
            initializing = false
            if (!readySignal.isCompleted) readySignal.complete(ok)
            withContext(Dispatchers.Main) { onReady?.invoke(ok) }
        }
    }

    @Volatile private var audioTrack: TeeAudioTrack? = null
    private var pcmChannel: kotlinx.coroutines.channels.Channel<Any>? = null
    private var playbackJob: Job? = null
    /** Wird von [EndOfPlayback] abgeschlossen, sobald alle Chunks im AudioTrack stehen. */
    @Volatile private var writeAck: CompletableDeferred<Unit>? = null

    private fun initAudioTrack() {
        if (audioTrack != null) return
        val sampleRate = 16_000
        val channelConfig = android.media.AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
        // AAudio FastTrack: Buffer auf die von Android gemeldete Mindestgröße
        // begrenzen (statt ~200 ms) und zusätzlich mit der NATIVEN Geräterate
        // ausgeben. Der Fast-Mixer des Primär-Ausgangs läuft laut
        // `dumpsys media.audio_flinger` auf dem Pixel 8a mit 48000 Hz / 960
        // Frames pro Periode — ein 16-kHz-Track wird trotz
        // PERFORMANCE_MODE_LOW_LATENCY nur vom Legacy-Mixer bedient
        // (gemessen: perf_mode=0). Das Playback läuft daher mit
        // PROPERTY_OUTPUT_SAMPLE_RATE, der Tee-Mitschnitt bleibt bei 16 kHz
        // bitgenau (siehe TeeAudioTrack).
        val nativeRate = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            am?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }.getOrNull() ?: 0
        val outputRate = if (nativeRate > 0) nativeRate else sampleRate
        val minBufferSize = android.media.AudioTrack.getMinBufferSize(outputRate, channelConfig, audioFormat)
        // getMinBufferSize() liefert bei ungültiger Konfiguration ERROR_BAD_VALUE (-2) bzw.
        // ERROR (-1). Der AudioTrack.Builder würde einen negativen Wert mit einer
        // IllegalArgumentException quittieren, daher defensiver Fallback auf ~100ms.
        val bufferSize = if (minBufferSize > 0) minBufferSize else outputRate * 2 / 10
        Log.i(
            TAG,
            "AudioTrack Buffer: srcRate=$sampleRate outRate=$outputRate " +
                "minBufferSize=$minBufferSize used=$bufferSize (Low-Latency FastTrack)"
        )

        audioTrack = TeeAudioTrack(
            context = context,
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            audioFormat = audioFormat,
            bufferSize = bufferSize,
            outputSampleRate = outputRate
        )

        startPlaybackLoop()
    }

    /**
     * Playback-Loop: schreibt die Chunks aus dem Channel in den AudioTrack.
     * [EndOfPlayback] ist ein Sentinel ohne Audio — wenn er dequeued wird, stehen alle
     * vorher gesendeten Chunks garantiert im Track und [writeAck] wird abgeschlossen.
     * Ohne diese Bestätigung wäre die Marker-Position 0, isSpeaking sofort wieder false
     * und der komplette Rest der Antwort als Echo im STT gelandet.
     */
    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        val channel = kotlinx.coroutines.channels.Channel<Any>(capacity = 64)
        pcmChannel = channel
        playbackJob = scope.launch(Dispatchers.IO) {
            val track = audioTrack
            if (track == null) {
                for (item in channel) if (item === EndOfPlayback) writeAck?.complete(Unit)
                return@launch
            }
            for (item in channel) {
                if (item === EndOfPlayback) {
                    writeAck?.complete(Unit)
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val chunk = item as ShortArray
                // Kein stopRequested-Check hier! Der Channel soll bei chunk_switch intakt bleiben,
                // und barge_in bricht den gesamten Job samt Channel ab.
                track.write(chunk, 0, chunk.size)
            }
        }
    }

    /**
     * Wartet, bis alle per [playSamples] gesendeten Chunks im AudioTrack geschrieben sind.
     * Nur danach ist die Endposition der Wiedergabe bekannt (siehe [TeeAudioTrack.triggerPlaybackComplete]).
     */
    private suspend fun awaitTrackDrained(timeoutMs: Long = 15_000L) {
        val channel = pcmChannel ?: return
        val ack = CompletableDeferred<Unit>()
        writeAck = ack
        try {
            channel.send(EndOfPlayback)
            val done = withTimeoutOrNull(timeoutMs) { ack.await(); true } ?: false
            if (!done) Log.w(TAG, "Write-Ack nach ${timeoutMs}ms nicht erhalten — Marker wird geschätzt")
        } catch (e: Exception) {
            Log.w(TAG, "Write-Ack fehlgeschlagen: ${e.message}")
        } finally {
            writeAck = null
        }
    }

    fun speak(text: String, speed: Float = 0.98f, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            onDone()
            return
        }
        if (!com.sisa.app.BuildConfig.BENCHMARK_MODE && !fallback.ensureAudible()) {
            onDone()
            return
        }
        beginSpeaking()
        val clean = germanize(text)
        // Unterbricht den Vorgänger. Das ist der Normalfall beim Chunk-Wechsel
        // innerhalb derselben Antwort und darf NICHT als Barge-In gezählt werden —
        // sonst verfälscht jeder Folge-Chunk die Barge-In-RT-Metrik (metrics.py
        // filtert deshalb auf reason=barge_in).
        stop(reason = "chunk_switch")
        stopRequested = false
        pendingDone = onDone
        initAudioTrack()
        scope.launch {
            withTimeoutOrNull(60_000L) { readySignal.await() }
            val engine = tts
            if (engine == null || stopRequested) {
                if (stopRequested) {
                    fireDone()
                } else {
                    withContext(Dispatchers.Main) { fallback.speak(clean) { fireDone() } }
                }
                return@launch
            }
            stopRequested = false
            var usedFallback = false
            for (chunk in splitSentences(clean)) {
                if (stopRequested) break
                val audio = try {
                    engine.generate(chunk, 0, speed)
                } catch (e: Exception) {
                    Log.e(TAG, "Generierung fehlgeschlagen, Fallback für Chunk", e)
                    usedFallback = true
                    withContext(Dispatchers.Main) { fallback.speak(chunk) }
                    continue
                }
                if (stopRequested) break
                playSamples(audio.samples, audio.sampleRate)
            }
            if (usedFallback) {
                withTimeoutOrNull(30_000L) { fallback.awaitIdle() }
            }
            // Erst warten, bis alle Chunks wirklich im AudioTrack stehen — playSamples()
            // legt sie nur in den Channel. Vorher ist die Endposition der Wiedergabe
            // unbekannt und der Marker würde auf 0 landen (isSpeaking sofort wieder false,
            // obwohl der Lautsprecher noch spricht -> Echo im STT).
            awaitTrackDrained()
            if (stopRequested) return@launch
            // Kein sofortiges markNotSpeaking() hier — der AudioTrack-Puffer spielt noch nach.
            // Stattdessen: Marker auf das exakte Ende aller bisher geschriebenen Frames.
            // Der Callback feuert, wenn die Wiedergabe wirklich bis dorthin gespielt hat,
            // also das Audio physisch aus dem Lautsprecher kommt. Erst DANN isSpeaking=false.
            val trackRef = audioTrack
            trackRef?.setPlaybackCompleteListener {
                Log.i(TAG, "playback_complete_callback -> Echo-Fenster ${ECHO_TAIL_MS}ms")
                // fireDone sofort (Zustand zurück auf LISTENING), isSpeaking erst nach dem
                // Echo-Fenster false — sonst erkennt der VAD den Nachhall als neue Sprache.
                fireDone()
                markSpeakingAfterEchoTail()
            }
            trackRef?.triggerPlaybackComplete()
        }
    }

    fun stop(reason: String = "barge_in") {
        stopRequested = true
        // Wartende Write-Acks freigeben, damit speak() nicht bis zum Timeout hängt.
        writeAck?.complete(Unit)
        // Nur echte Unterbrechungen beenden das "Sprechen" für Echo-Schutz.
        // chunk_switch lässt isSpeaking=true, damit VAD-EchoDrop auch über
        // Chunk-Grenzen hinweg aktiv bleibt.
        if (reason == "barge_in") {
            isSpeaking = false
            pcmChannel?.tryReceive() // flush pending
            try {
                audioTrack?.pause()
                audioTrack?.flush(reason = reason)
                audioTrack?.play()
            } catch (_: Exception) {}
            fallback.stop()
            fireDone()
            audioTrack?.invalidatePendingComplete()
            startPlaybackLoop() // frischen Channel aufsetzen für den nächsten Satz
        } else {
            // chunk_switch: den schon geschriebenen Ton NICHT abschneiden. Der Track spielt
            // weiter, der neue Chunk hängt sich an (playSamples schreibt in denselben Track).
            // Ein flush() hier würde die playbackHeadPosition auf 0 zurücksetzen, den Marker
            // des Vorgängers entwerten und mitten im Folge-Chunk auslösen — und den Ton
            // der KI mitten im Satz abschneiden.
            audioTrack?.invalidatePendingComplete()
            // startPlaybackLoop() wird HIER NICHT aufgerufen! 
            // Der alte Channel und Job laufen weiter, damit kein Audio verloren geht.
        }
    }

    fun shutdown() {
        writeAck?.complete(Unit)
        markNotSpeaking()
        stop()
        playbackJob?.cancel()
        pcmChannel?.close()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        scope.cancel()
        try { tts?.free() } catch (_: Exception) {}
        tts = null
    }

    /**
     * Zero-Copy Direct AudioTrack Streaming:
     * Float-Samples -> ShortArray -> direkt in den Channel ohne Disk-I/O!
     */
    suspend fun playSamples(samples: FloatArray, sampleRate: Int) =
        withContext(Dispatchers.Default) {
            if (stopRequested || samples.isEmpty()) return@withContext
            val pcm = ShortArray(samples.size) { i ->
                (samples[i] * TTS_GAIN).coerceIn(-1f, 1f).times(32767).toInt().toShort()
            }
            pcmChannel?.send(pcm)
        }

    /**
     * Englische Wörter & Abkürzungen für Kerstin (deutsche espeak-Stimme)
     * aussprechbar machen: Abkürzungen werden buchstabiert, Sonderzeichen
     * ausgeschrieben. Gilt für Engine + Fallback.
     */
    private fun germanize(text: String): String {
        var s = text
        val tags = listOf("<start_of_turn>", "<end_of_turn>", "<eos>", "<pad>", "<|im_end|>", "<|im_start|>", "<|endoftext|>", "end_of_turn", "start_of_turn")
        for (tag in tags) {
            s = s.replace(tag, "", ignoreCase = true)
        }
        s = s.replace(Regex("<[^>]+>"), "")
        val spelled = mapOf(
            "PDF" to "P D F", "E-Mail" to "E Mail", "E-Mails" to "E Mails",
            "STT" to "S T T", "TTS" to "T T S", "LLM" to "L L M",
            "GPU" to "G P U", "MTP" to "M T P", "USB" to "U S B",
            "ID" to "I D", "QR" to "Q R", "URL" to "U R L", "WLAN" to "W L A N",
            "PIN" to "P I N", "SMS" to "S M S", "OK" to "O K",
            "SIS" to "S I S", "STEP" to "S T E P", "DSGVO" to "D S G V O",
            "ICD" to "I C D", "ATC" to "A T C", "NRS" to "N R S",
            "MNA" to "M N A", "CAM" to "C A M", "GDS" to "G D S",
            "VAS" to "V A S"
        )
        for ((k, v) in spelled) {
            s = s.replace(Regex("\\b${Regex.escape(k)}\\b"), v)
        }
        s = s.replace("&", " und ")
        s = s.replace("®", "")
        s = s.replace("z.B.", "zum Beispiel")
            .replace("bzw.", "beziehungsweise")
            .replace("ggf.", "gegebenenfalls")
            .replace("ca.", "circa")
        s = s.replace("%", " Prozent ")
            .replace("€", " Euro ")
            .replace("§", " Paragraph ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Lange Texte satzweise portionieren (Engine läuft mit maxNumSentences=1). */
    private fun splitSentences(text: String): List<String> {        val parts = text.split(Regex("(?<=[.!?;:\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(text)
        return parts.flatMap { s -> if (s.length <= 240) listOf(s) else s.chunked(240) }
    }

    companion object {
        private const val TAG = "SisaVoice"
        /** Moderater Software-Gain; begrenzt vor der PCM16-Konvertierung. */
        private const val TTS_GAIN = 1.20f

        /**
         * Nachhall-Fenster nach dem physischen Audioende. In diesem Zeitraum startende
         * VAD-Segmente sind mit hoher Wahrscheinlichkeit der eigene Nachhall und werden
         * verworfen — außer sie sind laut genug für eine echte Nahbesprechung (Barge-In).
         */
        const val ECHO_TAIL_MS = 1000L

        /** Sentinel im Playback-Channel: trägt kein Audio, quittiert nur den Schreibstand. */
        private object EndOfPlayback
    }
}
