package com.sisa.app.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import com.sisa.app.download.AppModelManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Lokale Gemma-4-E2B-Inferenz in der App via LiteRT-LM — EINZIGE Inference-Quelle
 * im Live-Modus auf dem Pixel 8a. Kein llama-server, kein Container, kein WLAN.
 *
 * Ziel (Roadmap 14.3): Antwort on-device in <= 1,5 s nach Redeende.
 * [E2BAIService] (llama-server/SSE ueber 10.0.2.2 bzw. adb reverse) bleibt NUR
 * Fallback, wenn `BuildConfig.BENCHMARK_MODE` und LiteRT nicht verfuegbar ist
 * (Warn-Log; Umschaltung in MainActivity.handleUserSpeechFinished).
 *
 * Backend-Kette bei [load]: GPU+MTP -> GPU -> CPU. Jeder Fehlversuch wird mit
 * exaktem Stacktrace geloggt (Vorgabe: "Bei OOM/Delegate-Crash: exaktes Log +
 * Fallback-Config posten"). MTP-Hinweis: LiteRT-LM 0.17.1 exposet keine
 * oeffentliche MTP-Option in EngineConfig (per javap verifiziert: nur
 * modelPath/backend/visionBackend/audioBackend/maxNumTokens/maxNumImages/
 * cacheDir). GPU_MTP laeuft daher aktuell als GPU und wird als
 * `mtpConfigurable=false` dokumentiert; sobald eine Version die Option
 * freigibt, wird sie hier eingeschaltet.
 *
 * Artefakte:
 *  - LiteRT-LM AAR 0.17.1 (app/libs; Maven Central
 *    com.google.ai.edge.litertlm:litert-lm-android:0.17.1)
 *  - Modell gemma-4-E2B-it-gpu.litertlm (litert-community, QAT-Int4, 1,87 GB,
 *    CRC32 0x0f55d747) im privaten App-Speicher (AppModelManager.getModelsDir).
 *    Import per USB:
 *      adb -d push models/gemma-4-E2B-it-gpu.litertlm \
 *          /sdcard/Download/SIS_Models/
 *    + In-App-Import via AppModelManager.importFromPublicDownloads
 *      (MediaStore-Scan auf Download/SIS_Models mit CRC-Pflichtpruefung).
 *
 * BENCH-Events (Paritaet zu E2BAIService, damit tools/bench/metrics.py
 * unveraendert funktioniert):
 *  - BENCH llm_load        : Engine geladen (backend, litert-Version, ms)
 *  - BENCH llm_first_token : erster Token (TTFT)
 *  - BENCH llm_chunk       : Chunk an die TTS (first=, words=, chars=)
 *  - BENCH llm_error       : Stream-/Inferenzfehler
 */
class LocalGemmaAssistant(private val context: Context) {

    /** Backend-Variante des letzten erfolgreichen [load]. */
    enum class BackendKind(val label: String) {
        GPU_MTP("GPU+MTP"),
        GPU("GPU"),
        CPU("CPU")
    }

    /** Konfigurationsinformation des geladenen Modells. */
    data class LoadInfo(
        val backend: BackendKind,
        val modelFile: String,
        val modelSizeMb: Long,
        val litertLmVersion: String,
        val mtpConfigurable: Boolean,
        val loadDurationMs: Long
    )

    companion object {
        private const val TAG = "LocalGemma"
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        /** Offizielles Basismodell (litert-community, multimodal inkl. Audio-Encoder).
         * Wird bevorzugt, wenn vorhanden — die -gpu-Variante ist Text-Decoder-only. */
        const val MODEL_FILE_MULTIMODAL = "gemma-4-E2B-it.litertlm"
        /** Erwartete CRC32 des litert-community QAT-Modells (lokal verifiziert). */
        const val MODEL_CRC32 = 0x0f55d747L
        const val LITERT_LM_VERSION = "0.17.1"
        /**
         * maxNumTokens in EngineConfig bestimmt den GPU-KV-Cache.
         * 8192 Tokens begrenzen den GPU-KV-Cache zuverlässig. Vor dem Limit
         * verdichtet Gemma den bisherigen Dialog selbst und startet mit dieser
         * Zusammenfassung in ein frisches Context Window.
         */
        const val MAX_NUM_TOKENS = 8192
        /** Reserviert Platz für die Kompaktierungsfrage und die nächste Audioeingabe. */
        private const val DIRECT_AUDIO_COMPACTION_THRESHOLD_TOKENS = 7168
        const val LLM_TEMPERATURE = 0.2
        const val LLM_TOP_K = 20
        const val LLM_TOP_P = 0.90
        fun getSystemPrompt(language: String = "en"): String = when (language) {
            "de" -> "Du bist Gemma, eine freundliche, hilfsbereite KI im schnellen " +
                    "Live-Sprachmodus. Antworte ausschließlich auf Deutsch, kurz, prägnant " +
                    "und direkt in 1 bis 2 Sätzen."
            else -> "You are Gemma, a friendly, helpful AI in fast " +
                    "live voice mode. Answer exclusively in English, short, concise " +
                    "and directly in 1 to 2 sentences."
        }
        /** Chunker-Paritaet zu E2BAIService: erster Chunk ab 2 Woertern an die TTS für minimale Latenz. */
        private const val FIRST_CHUNK_MIN_WORDS = 2
        /**
         * Maximal behaltene historische Einträge (User + Assistant) im Prompt (Sliding Window).
         * 24 Einträge = 12 vollständige Dialog-Turns (verdoppelt).
         */
        private const val MAX_HISTORY_ENTRIES = 24
        /** Sobald mehr als 24 Einträge in der Historie sind, wird der asynchrone Kompaktierer aktiv. */
        private const val COMPACTING_TRIGGER_ENTRIES = 26

        /**
         * Bereinigt LLM-Ausgaben von Steuerzeichen und Prompt-Template-Tags
         * wie <end_of_turn>, <start_of_turn>, <eos>, etc.
         */
        fun sanitizeLlmOutput(raw: String): String {
            if (raw.isEmpty()) return ""
            var s = raw
            val tags = listOf(
                "<start_of_turn>", "<end_of_turn>", "<eos>", "<pad>",
                "<|im_end|>", "<|im_start|>", "<|endoftext|>",
                "end_of_turn", "start_of_turn"
            )
            for (tag in tags) {
                s = s.replace(tag, "", ignoreCase = true)
            }
            s = s.replace(Regex("<[^>]+>"), "")
            return s.trim()
        }
    }

    data class DialogTurn(val role: String, val text: String)

    private val conversationHistory = mutableListOf<DialogTurn>()
    @Volatile
    private var runningSummary: String = ""
    private var isCompacting: Boolean = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var session: Session? = null

    @Volatile
    var currentBackend: BackendKind? = null
        private set

    @Volatile
    var lastLoadInfo: LoadInfo? = null
        private set

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    /** Default must match the bundled default voice (de_DE-kerstin-low). */
    var currentLanguage: String = "de"

    /**
     * Gemma 4 E2B MTP GPU Direct Audio Processing (Conversation-API):
     * Nativer Audio-Input über Content.AudioBytes + Content.Text.
     * Der E2B-Audio-Encoder wird über einen vollständigen 16-kHz WAV-Container gespeist.
     * Die Conversation-API dekodiert den Blob mit miniaudio; rohe PCM-Bytes sind kein
     * gültiger Eingabestream für diesen Decoder.
     */
    private fun writeWavFile(samples: FloatArray): File {
        val wavFile = File(context.cacheDir, "e2b_turn_${SystemClock.elapsedRealtimeNanos()}_${samples.size}.wav")
        DataOutputStream(BufferedOutputStream(FileOutputStream(wavFile))).use { dos ->
            val dataSize = samples.size * 2
            dos.writeBytes("RIFF")
            dos.writeInt(java.lang.Integer.reverseBytes(36 + dataSize))
            dos.writeBytes("WAVEfmt ")
            dos.writeInt(java.lang.Integer.reverseBytes(16))
            dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            dos.writeInt(java.lang.Integer.reverseBytes(16000))
            dos.writeInt(java.lang.Integer.reverseBytes(16000 * 2))
            dos.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
            dos.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            dos.writeBytes("data")
            dos.writeInt(java.lang.Integer.reverseBytes(dataSize))
            val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) pcm.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            dos.write(pcm.array())
        }
        return wavFile
    }

    private var activeConversation: com.google.ai.edge.litertlm.Conversation? = null

    /** Gibt Ressourcen frei (Engine + Session + Conversation). Idempotent. */
    fun release() {
        runCatching { activeConversation?.close() }
        activeConversation = null
        runCatching { session?.close() }
        session = null
        runCatching { engine?.close() }
        engine = null
        isReady = false
        currentBackend = null
        lastLoadInfo = null
    }

    private fun createDirectAudioConversation(
        e: Engine,
        compactedContext: String = ""
    ): com.google.ai.edge.litertlm.Conversation {
        val systemPrompt = getSystemPrompt(currentLanguage)
        val instruction = if (compactedContext.isBlank()) {
            systemPrompt
        } else {
            val contextLabel = when (currentLanguage) {
                "de" -> "Zusammenfassung des bisherigen Gesprächs (als Kontext fortführen)"
                else -> "Summary of the previous conversation (continue as context)"
            }
            "$systemPrompt\n\n$contextLabel:\n$compactedContext"
        }
        return e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(Content.Text(instruction)),
                samplerConfig = SamplerConfig(
                    topK = LLM_TOP_K,
                    topP = LLM_TOP_P,
                    temperature = LLM_TEMPERATURE
                )
            )
        )
    }

    /** Verdichtet den echten Conversation-KV-Kontext mit Gemma, bevor er voll läuft. */
    private suspend fun compactDirectAudioConversation(
        e: Engine,
        conv: com.google.ai.edge.litertlm.Conversation,
        tokensBeforeCompaction: Int
    ): com.google.ai.edge.litertlm.Conversation {
        val summary = StringBuilder()
        val done = CompletableDeferred<Boolean>()
        val compactPrompt = when (currentLanguage) {
            "de" -> "Der Kontext wird gleich erneuert. Fasse den bisherigen Dialog für dich selbst in höchstens 4 kurzen deutschen Sätzen zusammen: wichtige Fakten, offene Fragen, Wünsche und Gesprächsfaden. Keine Einleitung."
            else -> "The context will be refreshed soon. Summarize the previous conversation yourself in at most 4 short English sentences: important facts, open questions, wishes and conversation thread. No introduction."
        }
        conv.sendMessageAsync(
            Contents.of(Content.Text(compactPrompt)),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    summary.append(message.toString())
                }
                override fun onDone() { done.complete(true) }
                override fun onError(t: Throwable) {
                    Log.w(TAG, "Direkte Kontext-Kompaktierung fehlgeschlagen", t)
                    done.complete(false)
                }
            },
            mapOf("clear_kv_cache_before_prefill" to false)
        )
        val compacted = if (done.await()) sanitizeLlmOutput(summary.toString()) else ""
        runCatching { conv.close() }
        val next = createDirectAudioConversation(e, compacted)
        Log.i(
            "BENCH",
            "BENCH context_compacted previous=$tokensBeforeCompaction " +
                "summary_chars=${compacted.length} max=$MAX_NUM_TOKENS"
        )
        return next
    }

    /**
     * Gemma 4 E2B MTP GPU Direct Audio Processing (Conversation-API):
     * Nativer Audio-Input über Content.AudioBytes + Content.Text (16-kHz WAV).
     * Handhabt Aufnahmen >30s durch Stückelung in <=30s Abschnitte (480000 Samples).
     * Hält dieselbe Conversation bis zur Token-Grenze am Leben und lässt Gemma
     * den Kontext davor automatisch kompakt zusammenfassen.
     */
    suspend fun processAudioDirectly(
        floatSamples: FloatArray,
        onChunk: (String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        val e = engine
        if (!isReady || e == null || floatSamples.isEmpty()) return@withContext ""
        val audioDurationSec = floatSamples.size / 16000.0
        val peak = floatSamples.maxOf { kotlin.math.abs(it) }
        val rms = kotlin.math.sqrt(floatSamples.sumOf { (it * it).toDouble() } / floatSamples.size)
        Log.i(
            TAG,
            "Gemma E2B Direct Audio via Conversation (${"%.2f".format(audioDurationSec)}s, " +
                "${floatSamples.size} samples, peak=${"%.3f".format(peak)}, rms=${"%.4f".format(rms)})..."
        )

        val maxChunkSamples = 480000 // 30s bei 16kHz
        val chunks = mutableListOf<FloatArray>()
        var offset = 0
        while (offset < floatSamples.size) {
            val end = (offset + maxChunkSamples).coerceAtMost(floatSamples.size)
            chunks.add(floatSamples.copyOfRange(offset, end))
            offset = end
        }

        val wavFiles = try {
            chunks.map(::writeWavFile)
        } catch (t: Throwable) {
            Log.e(TAG, "WAV schreiben fehlgeschlagen", t)
            return@withContext ""
        }

        mutex.withLock {
            try {
                var conv = activeConversation ?: createDirectAudioConversation(e)
                activeConversation = conv
                val tokensBeforeTurn = conv.getTokenCount()
                if (tokensBeforeTurn >= DIRECT_AUDIO_COMPACTION_THRESHOLD_TOKENS) {
                    conv = compactDirectAudioConversation(e, conv, tokensBeforeTurn)
                    activeConversation = conv
                }

                val full = StringBuilder()
                val chunkAcc = StringBuilder()
                var firstToken = true
                var firstChunkSent = false

                val totalParts = wavFiles.size
                for (i in 0 until totalParts) {
                    val partWav = wavFiles[i]
                    val isLastPart = (i == totalParts - 1)
                    val done = CompletableDeferred<Boolean>()

                    val callback = object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (!isLastPart) return
                            val text = message.toString()
                            val cleaned = sanitizeLlmOutput(text)
                            if (cleaned.isEmpty()) return
                            if (firstToken) {
                                firstToken = false
                                Log.i("BENCH", "BENCH llm_first_token t_elapsed_ns=${SystemClock.elapsedRealtimeNanos()}")
                            }
                            full.append(text)
                            chunkAcc.append(text)
                            if (shouldFlushChunk(chunkAcc.toString(), isFirstChunk = !firstChunkSent)) {
                                val ready = sanitizeLlmOutput(chunkAcc.toString())
                                chunkAcc.clear()
                                if (ready.isNotEmpty()) {
                                    Log.i(
                                        "BENCH",
                                        "BENCH llm_chunk t_elapsed_ns=${SystemClock.elapsedRealtimeNanos()} " +
                                            "first=${!firstChunkSent} " +
                                            "words=${ready.split(Regex("\\s+")).size} chars=${ready.length}"
                                    )
                                    firstChunkSent = true
                                    onChunk(ready)
                                }
                            }
                        }

                        override fun onDone() { done.complete(true) }
                        override fun onError(t: Throwable) {
                            Log.e(TAG, "LLM-AudioStream-Fehler Teil ${i + 1}/$totalParts", t)
                            done.complete(false)
                        }
                    }

                    val promptText = when (currentLanguage) {
                        "de" -> when {
                            totalParts == 1 -> "Beantworte ausschließlich den gesprochenen Inhalt der beigefügten Audioaufnahme auf Deutsch. Wiederhole diese Anweisung nicht."
                            !isLastPart -> "Hier ist Teil ${i + 1} von $totalParts des Audiosignals. Höre aufmerksam zu und warte auf die restlichen Teile vor der finalen Antwort."
                            else -> "Hier ist der letzte Teil (${i + 1} von $totalParts) des Audiosignals. Verarbeite alle Teile zusammen mit dem bisherigen Dialog und antworte direkt auf Deutsch in 1 bis 2 Sätzen."
                        }
                        else -> when {
                            totalParts == 1 -> "Answer exclusively the spoken content of the attached audio recording in English. Do not repeat this instruction."
                            !isLastPart -> "Here is part ${i + 1} of $totalParts of the audio signal. Listen carefully and wait for the remaining parts before the final answer."
                            else -> "Here is the last part (${i + 1} of $totalParts) of the audio signal. Process all parts together with the previous conversation and answer directly in English in 1 to 2 sentences."
                        }
                    }

                    conv.sendMessageAsync(
                        // Die Conversation-API erwartet den vollständigen WAV-Container als Blob.
                        // Content.AudioFile übergibt bei diesem LiteRT-Build nur einen lokalen
                        // Pfad ohne nutzbaren Modell-Anhang; Gemma meldet dann "keine Aufnahme".
                        Contents.of(Content.AudioBytes(partWav.readBytes()), Content.Text(promptText)),
                        callback,
                        mapOf("clear_kv_cache_before_prefill" to false)
                    )
                    val ok = done.await()
                    if (!ok) {
                        throw IllegalStateException("Audio-Teil ${i + 1}/$totalParts fehlgeschlagen")
                    }
                }

                val remaining = sanitizeLlmOutput(chunkAcc.toString())
                if (remaining.isNotEmpty()) onChunk(remaining)
                wavFiles.forEach { runCatching { it.delete() } }
                val reply = sanitizeLlmOutput(full.toString())
                Log.i(TAG, "Gemma E2B Direct Audio Antwort generiert: \"$reply\"")
                Log.i("BENCH", "BENCH turn_completed reply=\"$reply\"")
                val contextTokens = conv.getTokenCount()
                Log.i(
                    "BENCH",
                    "BENCH context_usage used=$contextTokens max=$MAX_NUM_TOKENS " +
                        "percent=${contextTokens * 100 / MAX_NUM_TOKENS}"
                )
                if (reply.isNotEmpty()) {
                    synchronized(conversationHistory) {
                        conversationHistory.add(DialogTurn("user", "[Audio ${"%.1f".format(audioDurationSec)}s]"))
                        conversationHistory.add(DialogTurn("assistant", reply))
                    }
                }
                reply
            } catch (t: Throwable) {
                Log.e(TAG, "Gemma E2B Direct Audio processing failed, resetting conversation and falling back", t)
                runCatching { activeConversation?.close() }
                activeConversation = null
                wavFiles.forEach { runCatching { it.delete() } }
                val fallbackPrompt = when (currentLanguage) {
                    "de" -> "Der Gesprächspartner hat gesprochen (${"%.1f".format(audioDurationSec)}s). Antworte freundlich auf Deutsch:"
                    else -> "The conversation partner has spoken (${"%.1f".format(audioDurationSec)}s). Respond friendly in English:"
                }
                streamChatResponse(fallbackPrompt, onChunk)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Laden / Backend-Fallback-Kette
    // -------------------------------------------------------------------------

    /**
     * Lädt die Engine mit Fallback-Kette GPU+MTP -> GPU -> CPU.
     * Bei OOM/Delegate-Crash wird der exakte Fehler geloggt (Vorgabe) und der
     * nächste Backend-Kandidat versucht. Gibt [LoadInfo] des ersten Erfolgs
     * zurück, oder null, wenn alle Backends fehlschlagen.
     */
    suspend fun load(): LoadInfo? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (engine != null) return@withContext lastLoadInfo

            val modelDir = AppModelManager.getModelsDir(context)
            // Multimodal-Basis bevorzugen (Audio-Encoder), Fallback -gpu (Text-only)
            val multiFile = File(modelDir, MODEL_FILE_MULTIMODAL)
            val modelFile = if (multiFile.exists() && multiFile.length() > 1_000_000_000L) {
                Log.i(TAG, "Nutze multimodales Basismodell: ${multiFile.name} (${multiFile.length() / 1048576}MB)")
                multiFile
            } else {
                File(modelDir, MODEL_FILE)
            }
            if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
                Log.e(TAG, "Model fehlt oder zu klein: ${modelFile.absolutePath} " +
                    "(${modelFile.length()} bytes). Import via AppModelManager.importFromPublicDownloads.")
                return@withContext null
            }

            // Kandidaten in Fallback-Reihenfolge. MTP ist in 0.17.1 nicht über
            // EngineConfig konfigurierbar (siehe KDoc der Klasse) -> GPU_MTP
            // läuft derzeit identisch zu GPU und bleibt als Label erhalten.
            val candidates = listOf(BackendKind.GPU_MTP, BackendKind.GPU, BackendKind.CPU)
            for (kind in candidates) {
                val startedNs = SystemClock.elapsedRealtimeNanos()
                try {
                    val backend: Backend = when (kind) {
                        BackendKind.CPU -> Backend.CPU()
                        else -> Backend.GPU()
                    }
                    val cfg = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        // Kol-Hinweis (benjamin-wegener/kol): Audio-Encoder läuft auf CPU,
                        // auch wenn Text auf GPU liegt. Mit GPU-Audio wird
                        // TF_LITE_AUDIO_ENCODER_HW wegen Backend-Constraints geskippt
                        // und InputData.Audio hinkt ewig (kein Callback).
                        audioBackend = Backend.CPU(),
                        maxNumTokens = MAX_NUM_TOKENS
                    )
                    val e = Engine(cfg)
                    e.initialize() // kann OOM/Delegate-Crash werfen -> exakt loggen
                    val durationMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L
                    engine = e
                    isReady = true
                    currentBackend = kind
                    val info = LoadInfo(
                        backend = kind,
                        modelFile = modelFile.name,
                        modelSizeMb = modelFile.length() / (1024L * 1024L),
                        litertLmVersion = LITERT_LM_VERSION,
                        mtpConfigurable = false, // 0.17.1: keine MTP-Option in EngineConfig
                        loadDurationMs = durationMs
                    )
                    lastLoadInfo = info
                    Log.i(TAG, "Engine geladen backend=${kind.label} in ${durationMs}ms " +
                        "litert=$LITERT_LM_VERSION model=${modelFile.name} (${info.modelSizeMb}MB)")
                    Log.i(
                        "BENCH",
                        "BENCH llm_load t_elapsed_ns=${SystemClock.elapsedRealtimeNanos()} " +
                            "backend=${kind.label} load_ms=$durationMs " +
                            "litert_version=$LITERT_LM_VERSION mtp=${info.mtpConfigurable}"
                    )
                    return@withContext info
                } catch (t: Throwable) {
                    // Vorgabe: exaktes Log + Fallback-Config posten
                    Log.e(TAG, "Backend ${kind.label} FEHLGESCHLAGEN, versuche naechsten Kandidaten", t)
                    Log.w(
                        "BENCH",
                        "BENCH llm_load_failed backend=${kind.label} " +
                            "error=${t.javaClass.simpleName}: ${t.message}"
                    )
                    runCatching { engine?.close() }
                    engine = null
                    isReady = false
                }
            }
            Log.e(TAG, "Alle Backends fehlgeschlagen (GPU_MTP, GPU, CPU). Keine Inferenz moeglich.")
            null
        }
    }



    // MARKER_CHAT

    // -------------------------------------------------------------------------
    // Chat-Streaming (Drop-in für E2BAIService)
    // -------------------------------------------------------------------------

    /**
     * Streaming-Chat: sendet [utterance] mit System-Prompt und liefert Text
     * progressiv über [onChunk] (Chunker-Parität zu E2BAIService: erster Chunk
     * ab 3 Wörtern, danach satzorientiert). Der vollständige Antworttext ist
     * Rückgabewert. Rückgabe "" bei Fehler (BENCH llm_error geloggt).
     */
    suspend fun streamChatResponse(
        utterance: String,
        onChunk: (String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        val e = engine
        if (!isReady || e == null) {
            Log.w(TAG, "streamChatResponse ohne geladene Engine aufgerufen")
            return@withContext ""
        }
        mutex.withLock {
            try {
                val s = e.createSession(
                    SessionConfig(
                        samplerConfig = SamplerConfig(
                            topK = LLM_TOP_K,
                            topP = LLM_TOP_P,
                            temperature = LLM_TEMPERATURE
                        )
                    )
                )
                session = s

                val full = StringBuilder()
                val chunkAcc = StringBuilder()
                var firstToken = true
                var firstChunkSent = false
                val done = CompletableDeferred<Boolean>()

                val callback = object : ResponseCallback {
                    override fun onNext(text: String) {
                        val cleaned = sanitizeLlmOutput(text)
                        if (cleaned.isEmpty() && text.contains("<")) return
                        if (firstToken) {
                            firstToken = false
                            Log.i("BENCH", "BENCH llm_first_token t_elapsed_ns=${SystemClock.elapsedRealtimeNanos()}")
                        }
                        full.append(text)
                        chunkAcc.append(text)
                        if (shouldFlushChunk(chunkAcc.toString(), isFirstChunk = !firstChunkSent)) {
                            val ready = sanitizeLlmOutput(chunkAcc.toString())
                            chunkAcc.clear()
                            if (ready.isNotEmpty()) {
                                Log.i(
                                    "BENCH",
                                    "BENCH llm_chunk t_elapsed_ns=${SystemClock.elapsedRealtimeNanos()} " +
                                        "first=${!firstChunkSent} " +
                                        "words=${ready.split(Regex("\\s+")).size} chars=${ready.length}"
                                )
                                firstChunkSent = true
                                onChunk(ready)
                            }
                        }
                    }

                    override fun onDone() { done.complete(true) }

                    override fun onError(t: Throwable) {
                        Log.e(TAG, "LLM-Stream-Fehler", t)
                        Log.w("BENCH", "BENCH llm_error error=${t.javaClass.simpleName}: ${t.message}")
                        done.complete(false)
                    }
                }

                // Multi-Turn context assembly with native Gemma chat template
                val systemPrompt = getSystemPrompt(currentLanguage)
                val promptBuilder = StringBuilder()
                promptBuilder.append("<start_of_turn>user\n")
                promptBuilder.append(systemPrompt)
                val summaryLabel = when (currentLanguage) {
                    "de" -> "Bisherige Fakten aus früheren Dialogteilen:"
                    else -> "Previous facts from earlier conversation parts:"
                }
                if (runningSummary.isNotBlank()) {
                    promptBuilder.append("\n\n$summaryLabel\n").append(runningSummary)
                }
                promptBuilder.append("<end_of_turn>\n")
                promptBuilder.append("<start_of_turn>model\n")
                val ackMessage = when (currentLanguage) {
                    "de" -> "Verstanden. Ich antworte kurz und prägnant auf Deutsch.<end_of_turn>\n"
                    else -> "Understood. I will answer briefly and concisely in English.<end_of_turn>\n"
                }
                promptBuilder.append(ackMessage)

                val recentHistory = conversationHistory.takeLast(MAX_HISTORY_ENTRIES)
                for (turn in recentHistory) {
                    val turnRole = if (turn.role == "user") "user" else "model"
                    promptBuilder.append("<start_of_turn>$turnRole\n")
                    promptBuilder.append(turn.text).append("<end_of_turn>\n")
                }
                promptBuilder.append("<start_of_turn>user\n")
                promptBuilder.append(utterance).append("<end_of_turn>\n")
                promptBuilder.append("<start_of_turn>model\n")

                val promptString = promptBuilder.toString()
                // Genaue Token-Schätzung (Gemma Tokenizer: ~3.6 chars/token im Schnitt für deutsches Chat-Template)
                val estimatedPromptTokens = (promptString.length / 3.6).toInt().coerceAtLeast(1)
                val historyTurnCount = conversationHistory.size / 2
                Log.i(
                    "BENCH",
                    "BENCH context_state turn=$historyTurnCount history_entries=${conversationHistory.size} " +
                        "window_turns=${recentHistory.size / 2} prompt_chars=${promptString.length} " +
                        "est_prompt_tokens=$estimatedPromptTokens kv_max_tokens=$MAX_NUM_TOKENS " +
                        "kv_util_pct=%.1f".format((estimatedPromptTokens.toDouble() / MAX_NUM_TOKENS) * 100.0) +
                        " has_summary=${runningSummary.isNotBlank()}"
                )

                val inputs = listOf(InputData.Text(promptString))
                s.generateContentStream(inputs, callback)
                val ok = done.await()
                // Rest des Chunk-Akkumulators flushen (Parität zu E2BAIService).
                val remaining = chunkAcc.toString().trim()
                if (remaining.isNotEmpty()) onChunk(remaining)
                runCatching { s.close() }
                session = null

                val reply = sanitizeLlmOutput(full.toString())
                val estReplyTokens = (reply.length / 3.6).toInt().coerceAtLeast(1)
                val totalEstTokens = estimatedPromptTokens + estReplyTokens
                Log.i(TAG, "Gemma response generated: $reply")
                Log.i(
                    "BENCH",
                    "BENCH turn_completed turn=$historyTurnCount reply_chars=${reply.length} " +
                        "est_reply_tokens=$estReplyTokens total_turn_tokens=$totalEstTokens " +
                        "kv_max=$MAX_NUM_TOKENS"
                )

                if (ok && reply.isNotEmpty()) {
                    // Turn in Historie sichern
                    conversationHistory.add(DialogTurn("user", utterance))
                    conversationHistory.add(DialogTurn("assistant", reply))
                    // Puffer für lückenlose Langzeit-Historie vorhalten (bis zu 250 Turns)
                    if (conversationHistory.size > 250) {
                        conversationHistory.removeAt(0)
                        conversationHistory.removeAt(0)
                    }

                    // Hintergrund-Kompaktierung anstoßen, wenn genug Historie vorhanden
                    if (conversationHistory.size >= COMPACTING_TRIGGER_ENTRIES && !isCompacting) {
                        triggerBackgroundCompacting()
                    }

                    reply
                } else if (ok) {
                    when (currentLanguage) {
                        "de" -> "Ich habe dich verstanden."
                        else -> "I understood you."
                    }
                } else {
                    ""
                }
            } catch (t: Throwable) {
                Log.e(TAG, "streamChatResponse fehlgeschlagen", t)
                Log.w("BENCH", "BENCH llm_error error=${t.javaClass.simpleName}: ${t.message}")
                ""
            }
        }
    }

    /** Nicht-streamende Variante (Kompatibilität zu E2BAIService.getChatResponse). */
    suspend fun getChatResponse(utterance: String): String =
        streamChatResponse(utterance) { /* Chunks werden verworfen */ }

    /** Chunker-Logik, exakt wie E2BAIService.shouldFlushChunk. */
    private fun shouldFlushChunk(text: String, isFirstChunk: Boolean): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val words = trimmed.split(Regex("\\s+"))
        if (isFirstChunk) {
            // Erster Chunk: früher ausliefern für kurze TTFA (ab 3 Wörtern).
            return words.size >= FIRST_CHUNK_MIN_WORDS
        }
        // Danach: satzorientiert flushen (Punkt, !, ?) oder ab 12 Wörtern.
        return trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") ||
            words.size >= 12
    }

    /**
     * Führt eine asynchrone Kompaktierung der ältesten Dialog-Turns durch.
     * Erstellt im Hintergrund 1-2 prägnante Kernfakten-Sätze, die den
     * Prompt-Overhead minimieren und Langzeit-Erinnerung garantieren.
     */
    private fun triggerBackgroundCompacting() {
        scope.launch {
            if (isCompacting) return@launch
            isCompacting = true
            try {
                // Kopiere die älteren Turns, die aus dem Sliding-Window herausfallen
                val historySnapshot = synchronized(conversationHistory) {
                    if (conversationHistory.size <= MAX_HISTORY_ENTRIES) return@synchronized emptyList<DialogTurn>()
                    // Nimm die älteren Einträge bis zum Beginn des aktiven Windows
                    conversationHistory.dropLast(MAX_HISTORY_ENTRIES).take(10)
                }

                if (historySnapshot.isEmpty()) return@launch

                val compPrompt = StringBuilder()
                compPrompt.append("<start_of_turn>user\n")
                val compactInstruction = when (currentLanguage) {
                    "de" -> "Fasse die wichtigsten Fakten (Name, Ort, Beruf, Kernthemen) aus diesem Dialog in 1 bis 2 kurzen Sätzen zusammen:"
                    else -> "Summarize the most important facts (name, place, profession, core topics) from this conversation in 1 to 2 short sentences:"
                }
                compPrompt.append(compactInstruction).append("\n\n")
                for (t in historySnapshot) {
                    val r = if (t.role == "user") "User: " else "Gemma: "
                    compPrompt.append(r).append(t.text).append("\n")
                }
                compPrompt.append("<end_of_turn>\n<start_of_turn>model\n")

                val e = engine ?: return@launch
                // Mutex kurz akquirieren, um Interferenz mit Live-Inferenz zu vermeiden
                mutex.withLock {
                    val s = e.createSession(
                        SessionConfig(
                            samplerConfig = SamplerConfig(
                                topK = 10,
                                topP = 0.85,
                                temperature = 0.1
                            )
                        )
                    )
                    val compactSummary = StringBuilder()
                    val done = CompletableDeferred<Boolean>()
                    s.generateContentStream(
                        listOf(InputData.Text(compPrompt.toString())),
                        object : ResponseCallback {
                            override fun onNext(text: String) { compactSummary.append(text) }
                            override fun onDone() { done.complete(true) }
                            override fun onError(t: Throwable) { done.complete(false) }
                        }
                    )
                    done.await()
                    runCatching { s.close() }

                    val res = compactSummary.toString()
                        .replace("<end_of_turn>", "")
                        .replace("</end_of_turn>", "")
                        .replace("<start_of_turn>", "")
                        .replace("</start_of_turn>", "")
                        .trim()

                    if (res.isNotBlank()) {
                        runningSummary = res
                        Log.i("BENCH", "BENCH compacting_done summary=\"$runningSummary\"")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Hintergrund-Kompaktierung übersprungen: ${t.message}")
            } finally {
                isCompacting = false
            }
        }
    }
}
