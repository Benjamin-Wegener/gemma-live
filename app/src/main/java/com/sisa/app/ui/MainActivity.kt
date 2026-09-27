package com.sisa.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sisa.app.ai.AudioLoop
import com.sisa.app.ai.E2BAIService
import com.sisa.app.ai.LlmBenchmark
import com.sisa.app.ai.LocalGemmaAssistant
import com.sisa.app.ai.TurnDetector
import com.sisa.app.stt.AndroidSttManager
import com.sisa.app.tts.AndroidTtsService
import com.sisa.app.tts.SisaVoiceService
import kotlinx.coroutines.*

enum class LiveState {
    LOADING, IDLE, LISTENING, THINKING, SPEAKING
}

class MainActivity : ComponentActivity() {

    private lateinit var androidTts: AndroidTtsService
    private lateinit var sisaVoice: SisaVoiceService
    private var sttManager: AndroidSttManager? = null
    private val speechBuffer = java.io.ByteArrayOutputStream()
    private val speechBufferLock = Any()
    @Volatile private var isCollectingSpeech = false
    /**
     * Echo-Schutz über die gesamte VAD-Segmentdauer, nicht nur beim Segmentende:
     * lief die KI während des Segments, ist es Echo — auch wenn die Ausgabe vorher
     * aufgehört hat und der VAD wegen Nachhall ~300ms später SpeechStopped meldet.
     */
    @Volatile private var segmentHadAiAudio = false
    /** Echter Barge-In in diesem Segment -> Segment gehört dem Nutzer, nicht verwerfen. */
    @Volatile private var segmentBargeInAccepted = false
    /** Lautheit beim Segmentstart — dient der Echo-Diagnose im Log. */
    @Volatile private var segmentPeak = 0
    @Volatile private var segmentRmsDb = 0f
    private var audioLoop: AudioLoop? = null
    private var turnDetector: TurnDetector? = null
    private val aiService = E2BAIService()
    private lateinit var localGemma: LocalGemmaAssistant
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val speechMutex = kotlinx.coroutines.sync.Mutex()
    // Zeitpunkt (elapsedRealtime) des letzten echten User-Inputs via Broadcast.
    // Verhindert im BENCHMARK_MODE Doppel-Antworten: feuert VAD-SpeechStopped kurz
    // nach einem Broadcast (externer Loop spricht), wird der Hardcode-Turn
    // übersprungen, weil der echte Inhalt bereits läuft.
    @Volatile
    private var lastBroadcastInputNs: Long = 0L
    /**
     * Barge-In / Echo-Schutz: KI kennt ihre eigene Redezeit.
     * - aiSpeakStartNs: wann der aktuelle TTS-Chunk gestartet hat (elapsedRealtimeNanos)
     * - BARGE_GRACE_MS: eigene Ausgabe in den ersten ms nie als Unterbrechung werten (Echo)
     * - BARGE_MIN_PEAK: Mindest-Lautstärke für echten Einwurf (Echo ist leiser als Nahbesprechung)
     * Mic bleibt dabei immer offen und nimmt weiter auf — nur die Bewertung wird gefiltert.
     */
    @Volatile private var aiSpeakStartNs: Long = 0L
    private val BARGE_GRACE_MS = 900L
    private val BARGE_MIN_PEAK = 9000
    private val BARGE_MIN_RMS_DB = -30f
    /**
     * Externer-Mikrofon-Modus (Mensch spricht via Mac-Mikro -> TCP-Inject):
     * VAD läuft weiter (Barge-In per Dazwischenreden bleibt aktiv), aber
     * SpeechStopped löst NIE einen Hardcode-Turn aus — der Inhalt kommt immer
     * per ACTION_USER_INPUT (Host-STT) als Broadcast. Umschaltung zur Laufzeit:
     * `adb shell "am broadcast -a com.sisa.app.live.ACTION_SET_EXTERNAL_MIC
     * --ez enabled true"`.
     */
    @Volatile
    private var externalMicMode: Boolean = false
    /**
     * Audio-Quellen-Modus (persistent, wirksam ab Loop-Neustart):
     * - "auto": BENCHMARK_MODE -> TCP, sonst Mikrofon (bisheriges Verhalten).
     * - "capture": PlaybackCaptureSource (OutsideVoice-Companion per
     *   AudioPlaybackCaptureConfiguration, API 29+) — Duplex-Test komplett
     *   im Emulator ohne Host-Audio.
     * Umschaltung: `adb shell "am broadcast -a
     * com.sisa.app.live.ACTION_SET_AUDIO_SOURCE --es mode capture"`
     * (danach Activity neu starten, z. B. force-stop + start).
     */
    @Volatile
    private var audioSourceMode: String = "auto"
    /**
     * MediaProjection-Token für PlaybackCapture (einmalige Nutzer-Zustimmung
     * pro Boot). Anforderung: `adb shell "am broadcast -a
     * com.sisa.app.live.ACTION_REQUEST_CAPTURE"` (Activity muss im Vordergrund
     * sein), danach Consent-Dialog per Tap bestätigen (im Test automatisiert).
     */
    @Volatile
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private val REQ_CAPTURE = 301

    private fun loadAudioSourceMode(): String =
        getPreferences(MODE_PRIVATE).getString("audio_source_mode", "auto") ?: "auto"

    private fun saveAudioSourceMode(mode: String) {
        getPreferences(MODE_PRIVATE).edit().putString("audio_source_mode", mode).apply()
        audioSourceMode = mode
    }

    // Observable states
    private var liveState = mutableStateOf(LiveState.IDLE)
    private var currentTranscript = mutableStateOf("")
    private var lastAiResponse = mutableStateOf("")
    private var volumeLevel = mutableFloatStateOf(0f)
    private var isHandsFreeActive = mutableStateOf(false)
    private var isEngineReady = mutableStateOf(false)
    private var engineStatus = mutableStateOf("Engine wird geladen …")
    private var benchRunning = mutableStateOf(false)
    private var benchStatus = mutableStateOf("")
    private var benchReport = mutableStateOf<LlmBenchmark.Report?>(null)
    private var showBenchDialog = mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioSourceMode = loadAudioSourceMode()
        android.util.Log.i("LiveMode", "AudioSourceMode=$audioSourceMode")
        if (audioSourceMode == "capture") ensureCaptureHolder()

        androidTts = AndroidTtsService(this)
        sisaVoice = SisaVoiceService(this, androidTts)
        sisaVoice.initAsync { }

        // STT-Engine: fest Gemma E2B Direct Audio (Whisper entfernt).
        // AndroidSttManager nur als optionaler Fallback bereitgestellt (ohne Endlos-Spin)
        sttManager = AndroidSttManager(
            context = this,
            onResult = { text ->
                if (text.isNotBlank()) {
                    currentTranscript.value = text
                    handleUserSpeechFinished(text)
                }
            },
            onPartialResult = { partial ->
                if (partial.isNotBlank()) {
                    currentTranscript.value = partial
                }
            },
            onError = { errorMsg ->
                android.util.Log.w("LiveMode", "STT onError: $errorMsg")
            },
            onListeningStateChanged = { listening ->
                if (listening) {
                    liveState.value = LiveState.LISTENING
                } else if (liveState.value == LiveState.LISTENING) {
                    liveState.value = LiveState.IDLE
                }
            }
        )

        localGemma = LocalGemmaAssistant(this)
        liveState.value = LiveState.LOADING
        isHandsFreeActive.value = false
        engineStatus.value = "Engine wird geladen …"
        scope.launch(Dispatchers.Default) {
            val loadInfo = localGemma.load()
            withContext(Dispatchers.Main) {
                if (loadInfo != null) {
                    android.util.Log.i("LiveMode", "LocalGemma bereit: ${loadInfo.backend.label}, ${loadInfo.modelFile}")
                    isEngineReady.value = true
                    engineStatus.value = "Bereit (${loadInfo.backend.label})"
                    // Hands-Free automatisch aktivieren sobald Engine geladen
                    autoStartHandsFree()
                } else {
                    android.util.Log.w("LiveMode", "LocalGemma konnte nicht geladen werden (Modell noch nicht importiert?)")
                    isEngineReady.value = false
                    engineStatus.value = "Modell fehlt oder konnte nicht geladen werden"
                    liveState.value = LiveState.IDLE
                }
            }
        }

        registerLiveBroadcastReceiver()

        setContent {
            LiveModeScreen(
                liveState = liveState.value,
                transcript = currentTranscript.value,
                aiResponse = lastAiResponse.value,
                volumeLevel = volumeLevel.floatValue,
                engineReady = isEngineReady.value,
                engineStatus = engineStatus.value
            )
        }
    }

    private fun autoStartHandsFree() {
        if (!isEngineReady.value) return
        if (isHandsFreeActive.value) return
        // Pure Live-Mode: Automatisch Hands-Free / Full-Duplex starten
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            isHandsFreeActive.value = true
            startContinuousAudioLoop()
            liveState.value = LiveState.LISTENING
        } else {
            // Ladeanzeige bleibt aktiv bis Permission da ist
            liveState.value = LiveState.LOADING
            engineStatus.value = "Mikrofon-Berechtigung nötig …"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    private fun runLlmBenchmark() {
        if (benchRunning.value) return
        benchRunning.value = true
        benchStatus.value = "Starte Benchmark …"
        benchReport.value = null
        scope.launch(Dispatchers.Default) {
            try {
                val report = LlmBenchmark.run(
                    context = this@MainActivity,
                    aiService = aiService,
                    repeats = 3,
                    onProgress = { p -> scope.launch(Dispatchers.Main) { benchStatus.value = p } }
                )
                withContext(Dispatchers.Main) {
                    benchReport.value = report
                    showBenchDialog.value = true
                    benchStatus.value = "Fertig – ${report.runs.count { it.ok }}/${report.runs.size} Runs ok"
                }
            } catch (t: Throwable) {
                android.util.Log.e("LiveMode", "Benchmark failed", t)
                withContext(Dispatchers.Main) { benchStatus.value = "Fehler: ${t.message}" }
            } finally {
                withContext(Dispatchers.Main) { benchRunning.value = false }
            }
        }
    }

    private fun registerLiveBroadcastReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction("com.sisa.app.live.ACTION_SPEAK")
            addAction("com.sisa.app.live.ACTION_USER_INPUT")
            addAction("com.sisa.app.live.ACTION_INTERRUPT")
            addAction("com.sisa.app.live.ACTION_TOGGLE_HANDSFREE")
            addAction("com.sisa.app.live.ACTION_RUN_BENCHMARK")
            addAction("com.sisa.app.live.ACTION_SET_EXTERNAL_MIC")
            addAction("com.sisa.app.live.ACTION_SET_AUDIO_SOURCE")
            addAction("com.sisa.app.live.ACTION_REQUEST_CAPTURE")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.sisa.app.live.ACTION_RUN_BENCHMARK" -> {
                        android.util.Log.i("LiveMode", "Received ACTION_RUN_BENCHMARK")
                        runLlmBenchmark()
                    }
                    "com.sisa.app.live.ACTION_SPEAK" -> {
                        val text = intent.getStringExtra("text") ?: return
                        android.util.Log.i("LiveMode", "Received ACTION_SPEAK: $text")
                        lastAiResponse.value = text
                        liveState.value = LiveState.SPEAKING
                        aiSpeakStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                        sisaVoice.speak(text) {
                            if (isHandsFreeActive.value) {
                                liveState.value = LiveState.LISTENING
                            } else {
                                liveState.value = LiveState.IDLE
                            }
                        }
                    }
                    "com.sisa.app.live.ACTION_USER_INPUT" -> {
                        val text = intent.getStringExtra("text") ?: return
                        android.util.Log.i("LiveMode", "Received ACTION_USER_INPUT: $text")
                        lastBroadcastInputNs = android.os.SystemClock.elapsedRealtimeNanos()
                        currentTranscript.value = text
                        handleUserSpeechFinished(text)
                    }
                    "com.sisa.app.live.ACTION_INTERRUPT" -> {
                        android.util.Log.i("LiveMode", "Received ACTION_INTERRUPT")
                        interruptAiSpeech()
                    }
                    "com.sisa.app.live.ACTION_TOGGLE_HANDSFREE" -> {
                        android.util.Log.i("LiveMode", "Received ACTION_TOGGLE_HANDSFREE")
                        toggleHandsFree()
                    }
                    "com.sisa.app.live.ACTION_SET_EXTERNAL_MIC" -> {
                        externalMicMode = intent.getBooleanExtra("enabled", false)
                        android.util.Log.i("LiveMode", "ExternalMicMode=$externalMicMode")
                    }
                    "com.sisa.app.live.ACTION_SET_AUDIO_SOURCE" -> {
                        val mode = intent.getStringExtra("mode") ?: "auto"
                        saveAudioSourceMode(mode)
                        android.util.Log.i("LiveMode", "AudioSourceMode=$mode (wirksam ab Loop-Neustart)")
                    }
                    "com.sisa.app.live.ACTION_REQUEST_CAPTURE" -> {
                        android.util.Log.i("LiveMode", "ACTION_REQUEST_CAPTURE empfangen")
                        ensureCaptureHolder()
                        requestCapturePermission()
                    }
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun handleUserSpeechFinished(utterance: String) {
        liveState.value = LiveState.THINKING
        scope.launch {
            speechMutex.lock()
            try {
                var firstChunk = true
                val onChunkReceived: (String) -> Unit = { chunk ->
                    if (firstChunk) {
                        firstChunk = false
                        liveState.value = LiveState.SPEAKING
                        lastAiResponse.value = chunk
                        aiSpeakStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                        sisaVoice.speak(chunk) {
                            if (isHandsFreeActive.value) {
                                liveState.value = LiveState.LISTENING
                            } else {
                                liveState.value = LiveState.IDLE
                            }
                        }
                    } else {
                        lastAiResponse.value = "${lastAiResponse.value} $chunk"
                        aiSpeakStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                        sisaVoice.speak(chunk) {
                            if (isHandsFreeActive.value) {
                                liveState.value = LiveState.LISTENING
                            } else {
                                liveState.value = LiveState.IDLE
                            }
                        }
                    }
                }

                if (::localGemma.isInitialized && (localGemma.isReady || localGemma.load() != null)) {
                    android.util.Log.i("LiveMode", "Using on-device LocalGemmaAssistant")
                    val fullResponse = localGemma.streamChatResponse(utterance, onChunkReceived)
                    if (fullResponse.isNotBlank()) {
                        lastAiResponse.value = fullResponse
                    } else {
                        android.util.Log.w("LiveMode", "LocalGemma returned empty response, checking fallback")
                    }
                } else if (com.sisa.app.BuildConfig.BENCHMARK_MODE) {
                    android.util.Log.w(
                        "LiveMode",
                        "BENCH LocalGemma not available, FALLBACK to E2BAIService (llama-server/10.0.2.2)"
                    )
                    val fullResponse = aiService.streamChatResponse(utterance, onChunkReceived)
                    lastAiResponse.value = fullResponse
                } else {
                    android.util.Log.e("LiveMode", "LocalGemma nicht verfügbar und kein Fallback aktiv.")
                    liveState.value = LiveState.IDLE
                    lastAiResponse.value = "Modell wird noch geladen oder ist nicht vorhanden."
                }
            } finally {
                speechMutex.unlock()
            }
        }
    }

    private fun interruptAiSpeech() {
        if (liveState.value == LiveState.SPEAKING) {
            android.util.Log.i("BENCH", "BENCH barge_flush t_elapsed_ns=${android.os.SystemClock.elapsedRealtimeNanos()} reason=barge_in")
            sisaVoice.stop(reason = "barge_in")
            turnDetector?.reset()
            // Mic lief weiter — Puffer für Nutzer-Einwurf frisch starten
            synchronized(speechBufferLock) { speechBuffer.reset() }
            isCollectingSpeech = true
                                                                                if (isHandsFreeActive.value) {
                                                                                    liveState.value = LiveState.LISTENING
                                                                                } else {
                liveState.value = LiveState.IDLE
            }
        }
    }

    private fun onManualMicTap() {
        when (liveState.value) {
            LiveState.SPEAKING -> interruptAiSpeech()
            LiveState.LISTENING -> {
                sttManager?.stopListening()
                liveState.value = LiveState.IDLE
            }
            LiveState.IDLE -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    currentTranscript.value = ""
                    liveState.value = LiveState.LISTENING
                } else {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                }
            }
            LiveState.THINKING -> {}
            LiveState.LOADING -> {}
        }
    }

    private fun toggleHandsFree() {
        if (!isHandsFreeActive.value) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                return
            }
            isHandsFreeActive.value = true
            startContinuousAudioLoop()
            liveState.value = LiveState.LISTENING
        } else {
            isHandsFreeActive.value = false
            stopContinuousAudioLoop()
            sttManager?.stopListening()
            sisaVoice.stop()
            liveState.value = LiveState.IDLE
        }
    }

    private fun startContinuousAudioLoop() {
        scope.launch(Dispatchers.Default) {
            try {
                val detector = TurnDetector(this@MainActivity).also { turnDetector = it }
                detector.prepare()
                val loop = AudioLoop(this@MainActivity).also { audioLoop = it }
                val sourceOverride: com.sisa.app.ai.AudioFrameSource? = when (audioSourceMode) {
                    "capture" -> {
                        val proj = mediaProjection
                        if (proj == null) {
                            android.util.Log.w("LiveMode", "capture ohne Projection-Token (ACTION_REQUEST_CAPTURE + Consent nötig), fallback auto")
                            null
                        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            try {
                                com.sisa.app.ai.PlaybackCaptureSource(this@MainActivity, proj)
                            } catch (t: Throwable) {
                                android.util.Log.e("LiveMode", "Capture-Source fehlgeschlagen, fallback auto", t)
                                null
                            }
                        } else {
                            android.util.Log.w("LiveMode", "capture braucht API 29+, fallback auto")
                            null
                        }
                    }
                    else -> null
                }
                loop.start(sourceOverride = sourceOverride) { frame ->
                    volumeLevel.floatValue = (frame.peak.toFloat() / 32768f).coerceIn(0f, 1f)

                    // Mic bleibt IMMER offen und nimmt auf — auch während KI redet.
                    // Nur die Bewertung (Barge-In vs. Echo) wird unten gefiltert.
                    if (isCollectingSpeech) {
                        synchronized(speechBufferLock) {
                            val byteBuf = java.nio.ByteBuffer.allocate(frame.samples.size * 2)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (s in frame.samples) byteBuf.putShort(s)
                            speechBuffer.write(byteBuf.array())
                        }
                    }

                    detector.process(frame) { event ->
                        when (event) {
                            is TurnDetector.Event.SpeechStarted -> {
                                val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
                                val aiSpeaking = sisaVoice.isSpeaking || liveState.value == LiveState.SPEAKING
                                segmentHadAiAudio = aiSpeaking
                                segmentBargeInAccepted = false
                                segmentPeak = frame.peak
                                segmentRmsDb = frame.rmsDb
                                if (aiSpeaking) {
                                    // Echo-Schutz: eigene Ausgabe kennen, nur echten Einwurf werten
                                    val elapsedMs = (nowNs - aiSpeakStartNs) / 1_000_000L
                                    val loudEnough = frame.peak >= BARGE_MIN_PEAK || frame.rmsDb >= BARGE_MIN_RMS_DB
                                    // Audio ist physisch vorbei, aber isSpeaking hält das Nachhall-Fenster
                                    // (sisaVoice.ECHO_TAIL_MS) offen. Hier ist die 900ms-Anlaufzeit
                                    // nicht mehr relevant — entscheidet nur noch die Lautstärke.
                                    val tailMs = if (sisaVoice.audioEndedNs > 0L) (nowNs - sisaVoice.audioEndedNs) / 1_000_000L else Long.MAX_VALUE
                                    val inEchoTail = tailMs < SisaVoiceService.ECHO_TAIL_MS
                                    if (inEchoTail && loudEnough) {
                                        android.util.Log.i("LiveMode", "VAD SpeechStarted = Nutzer direkt nach Antwortende (${tailMs}ms nach Audioende, peak=${frame.peak})")
                                        android.util.Log.i("BENCH", "BENCH barge_in t_elapsed_ns=$nowNs peak=${frame.peak} tailMs=$tailMs")
                                        segmentBargeInAccepted = true
                                    } else if (elapsedMs < BARGE_GRACE_MS && !inEchoTail) {
                                        android.util.Log.i("BENCH", "BENCH barge_ignored t_elapsed_ns=$nowNs reason=echo_grace elapsedMs=$elapsedMs peak=${frame.peak}")
                                        // Trotzdem puffern, falls Nutzer früh einsteigt — Auswertung erst bei Stopped
                                    } else if (!loudEnough) {
                                        android.util.Log.i("BENCH", "BENCH barge_ignored t_elapsed_ns=$nowNs reason=echo_quiet peak=${frame.peak} rmsDb=${frame.rmsDb} tailMs=$tailMs")
                                    } else {
                                        android.util.Log.i("LiveMode", "VAD SpeechStarted = echter Barge-In (peak=${frame.peak} rms=${frame.rmsDb} nach ${elapsedMs}ms)")
                                        android.util.Log.i("BENCH", "BENCH barge_in t_elapsed_ns=$nowNs peak=${frame.peak}")
                                        segmentBargeInAccepted = true
                                        scope.launch(Dispatchers.Main) {
                                            interruptAiSpeech()
                                        }
                                    }
                                } else {
                                    android.util.Log.i("LiveMode", "VAD SpeechStarted detected")
                                }
                                synchronized(speechBufferLock) {
                                    speechBuffer.reset()
                                    isCollectingSpeech = true
                                    // Puffer mit aktuellem Frame füllen
                                    val byteBuf = java.nio.ByteBuffer.allocate(frame.samples.size * 2)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    for (s in frame.samples) byteBuf.putShort(s)
                                    speechBuffer.write(byteBuf.array())
                                }
                            }
                            is TurnDetector.Event.SpeechStopped -> {
                                android.util.Log.i("LiveMode", "VAD SpeechStopped detected durationMs=${event.speechDurationMs}")
                                isCollectingSpeech = false
                                // Echo-Drop: lief die KI-Ausgabe während dieses Segments, ist es Echo —
                                // die eigene Ausgabe darf nie an STT. isSpeaking bleibt über Chunk-Grenzen
                                // true (chunk_switch), liveState kann schon LISTENING sein, und der VAD
                                // meldet wegen Nachhall gern ~300ms nach Audioende noch "Sprache".
                                // Ausnahme: angenommener Barge-In ist echter Nutzer-Turn.
                                val aiAudioInSegment = segmentHadAiAudio || sisaVoice.isSpeaking
                                val aiState = liveState.value == LiveState.SPEAKING || liveState.value == LiveState.LISTENING
                                if (aiAudioInSegment && !segmentBargeInAccepted && aiState) {
                                    android.util.Log.i("BENCH", "BENCH echo_drop t_elapsed_ns=${android.os.SystemClock.elapsedRealtimeNanos()} durationMs=${event.speechDurationMs} startedDuringAi=${segmentHadAiAudio} stillSpeaking=${sisaVoice.isSpeaking} peak=$segmentPeak rmsDb=$segmentRmsDb")
                                    synchronized(speechBufferLock) { speechBuffer.reset() }
                                    // VAD-Segment verwerfen, kein Whisper-Call
                                } else {
                                    val sileroSamples = event.audioSamples
                                    val pcmData: ShortArray = synchronized(speechBufferLock) {
                                        val bytes = speechBuffer.toByteArray()
                                        speechBuffer.reset()
                                        val shorts = ShortArray(bytes.size / 2)
                                        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                            .asShortBuffer().get(shorts)
                                        shorts
                                    }
                                    scope.launch(Dispatchers.Main) {
                                        val busy = liveState.value == LiveState.SPEAKING ||
                                            liveState.value == LiveState.THINKING
                                        if (!busy && !externalMicMode) {
                                            sttManager?.stopListening()
                                            val samplesToUse = if (sileroSamples != null && sileroSamples.isNotEmpty()) {
                                                sileroSamples
                                            } else if (pcmData.size >= 16000 * 0.25) {
                                                FloatArray(pcmData.size) { i -> pcmData[i] / 32768.0f }
                                            } else null
                                            if (samplesToUse != null && samplesToUse.isNotEmpty()) {
                                                Log.i("LiveMode", "Gemma E2B MTP GPU Direct Audio Processing (${samplesToUse.size} samples)")
                                                currentTranscript.value = "Gemma E2B Audio-Eingabe (${"%.1f".format(samplesToUse.size / 16000.0)}s)..."
                                                liveState.value = LiveState.THINKING
                                                scope.launch(Dispatchers.Default) {
                                                    speechMutex.lock()
                                                    try {
                                                        var firstChunk = true
                                                        val onChunkReceived: (String) -> Unit = { chunk ->
                                                            scope.launch(Dispatchers.Main) {
                                                                if (firstChunk) {
                                                                    firstChunk = false
                                                                    liveState.value = LiveState.SPEAKING
                                                                    lastAiResponse.value = chunk
                                                                    aiSpeakStartNs = SystemClock.elapsedRealtimeNanos()
                                                                    sisaVoice.speak(chunk) {
                                                                        if (isHandsFreeActive.value) {
                                                                            liveState.value = LiveState.LISTENING
                                                                        } else {
                                                                            liveState.value = LiveState.IDLE
                                                                        }
                                                                    }
                                                                } else {
                                                                    lastAiResponse.value = "${lastAiResponse.value} $chunk"
                                                                    aiSpeakStartNs = SystemClock.elapsedRealtimeNanos()
                                                                    sisaVoice.speak(chunk) {
                                                                        if (isHandsFreeActive.value) {
                                                                            liveState.value = LiveState.LISTENING
                                                                        } else {
                                                                            liveState.value = LiveState.IDLE
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        val resp = localGemma.processAudioDirectly(samplesToUse, onChunkReceived)
                                                        if (resp.isNotBlank()) {
                                                            withContext(Dispatchers.Main) { lastAiResponse.value = resp }
                                                        }
                                                    } finally {
                                                        speechMutex.unlock()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("LiveMode", "Loop error", t)
            }
        }
    }

    private fun stopContinuousAudioLoop() {
        // AudioLoop stops when activity closes or detached
    }

    /** Startet den Dummy-FGS (Typ mediaProjection) für getMediaProjection(). */
    private fun ensureCaptureHolder() {
        try {
            val svc = android.content.Intent(this, com.sisa.app.ai.CaptureHolderService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (t: Throwable) {
            android.util.Log.e("LiveMode", "ensureCaptureHolder fehlgeschlagen", t)
        }
    }

    /** Fordert die MediaProjection-Zustimmung (Consent-Dialog) an. */
    private fun requestCapturePermission() {
        try {
            val mgr = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            if (mgr == null) {
                android.util.Log.e("LiveMode", "kein MediaProjectionManager")
                return
            }
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (t: Throwable) {
            android.util.Log.e("LiveMode", "requestCapturePermission fehlgeschlagen", t)
        }
    }

    @Deprecated("klassischer Request-Code-Pfad (minSdk 26)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            try {
                val mgr = getSystemService(android.media.projection.MediaProjectionManager::class.java)
                    ?: return
                mediaProjection = mgr.getMediaProjection(resultCode, data)
                android.util.Log.i("LiveMode", "MediaProjection granted, starte Loop mit Capture-Source neu")
                scope.launch(Dispatchers.Default) {
                    try {
                        audioLoop?.stop()
                    } catch (t: Throwable) {
                        android.util.Log.w("LiveMode", "Loop-Stop: ${t.message}")
                    }
                    startContinuousAudioLoop()
                }
            } catch (t: Throwable) {
                android.util.Log.e("LiveMode", "getMediaProjection fehlgeschlagen", t)
            }
        } else {
            android.util.Log.w("LiveMode", "MediaProjection consent VERWEIGERT (result=$resultCode)")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            android.util.Log.i("LiveMode", "Microphone permission granted, starting live loop")
            if (isEngineReady.value) {
                autoStartHandsFree()
            } else {
                // Engine lädt noch – autoStartHandsFree feuert nach load()
                engineStatus.value = "Engine lädt noch …"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        sisaVoice.shutdown()
        androidTts.shutdown()
        sttManager?.destroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveModeScreen(
    liveState: LiveState,
    transcript: String,
    aiResponse: String,
    volumeLevel: Float,
    engineReady: Boolean = true,
    engineStatus: String = ""
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (liveState == LiveState.SPEAKING || liveState == LiveState.LISTENING) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    // Eigener Ladebalken + Spinner (kein Material3-Progress, wegen BOM-Konflikt):
    val loadingFraction by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loadingBar"
    )

    val stateColor = when (liveState) {
        LiveState.LOADING -> Color(0xFFF59E0B)     // Amber / Laden
        LiveState.IDLE -> Color(0xFF64748B)
        LiveState.LISTENING -> Color(0xFF0EA5E9)   // Hellblau
        LiveState.THINKING -> Color(0xFFA855F7)    // Lila / Denken
        LiveState.SPEAKING -> Color(0xFF10B981)    // Smaragdgrün / Sprechen
    }

    val stateTitle = when (liveState) {
        LiveState.LOADING -> "Gemma lädt …"
        LiveState.IDLE -> "Bereit zum Zuhören"
        LiveState.LISTENING -> "Ich höre dir zu..."
        LiveState.THINKING -> "Gemma denkt nach..."
        LiveState.SPEAKING -> "Gemma spricht"
    }

    var showLicensesDialog by remember { mutableStateOf(false) }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Open Source Lizenzen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = """
• Google Gemma & LiteRT-LM
  Copyright Google LLC. Apache License 2.0 / Gemma Terms of Use.

• Sherpa-ONNX & Next-gen Kaldi
  Copyright (c) 2022-2024 Xiaomi Corporation. Apache License 2.0.

• Piper TTS & Thorsten Voice
  Copyright (c) Michael Hansen, Thorsten Müller. MIT / CC0 / Open Audio License.

• Silero VAD
  Copyright (c) Silero Team. MIT License.

• AndroidX & Jetpack Compose
  Copyright The Android Open Source Project. Apache License 2.0.

• Kotlin Coroutines
  Copyright 2000-2024 JetBrains s.r.o. Apache License 2.0.
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text("Schließen")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(stateColor)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Gemma Live Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        // ? Piktogramm in kleinem Kreis oben rechts
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { showLicensesDialog = true },
                                        onLongPress = { showLicensesDialog = true }
                                    )
                                }
                        ) {
                            Text(
                                text = "?",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Status-Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stateTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = stateColor,
                    textAlign = TextAlign.Center
                )
                if (liveState == LiveState.LOADING) {
                    Spacer(Modifier.height(8.dp))
                    // Eigener Ladebalken (Box-basiert, indeterminiert animiert)
                    Box(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(stateColor.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight()
                                .fillMaxWidth(loadingFraction)
                                .clip(RoundedCornerShape(3.dp))
                                .background(stateColor)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = engineStatus.ifBlank { "Engine wird geladen … Hands-Free startet automatisch" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = if (engineReady) "Vollautomatischer Live-Modus aktiv (Turn-Taking & Barge-In)"
                        else engineStatus.ifBlank { "Engine nicht bereit" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Zentraler lebendiger Audio-Orb (reine Anzeige, kein Button)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(190.dp)
                    .clip(CircleShape)
            ) {
                // Äußerer Pulsier-Ring
                Box(
                    modifier = Modifier
                        .size((160 * (1f + (volumeLevel * 0.5f))).dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(stateColor.copy(alpha = 0.2f))
                )
                // Mittlerer Ring
                Box(
                    modifier = Modifier
                        .size(125.dp)
                        .clip(CircleShape)
                        .background(stateColor.copy(alpha = 0.4f))
                )
                // Kern-Orb
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(stateColor, stateColor.copy(alpha = 0.85f))
                            )
                        )
                ) {
                    if (liveState == LiveState.LOADING) {
                        // Spinner-Ersatz: pulsierendes Hourglass-Icon (kein Material3-Progress)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(38.dp).scale(pulseScale)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HourglassTop,
                                contentDescription = "Lädt",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = when (liveState) {
                                LiveState.SPEAKING -> Icons.Default.VolumeUp
                                LiveState.THINKING -> Icons.Default.HourglassTop
                                LiveState.LISTENING -> Icons.Default.Mic
                                LiveState.IDLE -> Icons.Default.MicNone
                                LiveState.LOADING -> Icons.Default.HourglassTop
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
            }

            // Dialog-Karten (Transkription & Antwort)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Was der Nutzer gesagt hat
                if (transcript.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Du:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(transcript, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                // Was die KI antwortet
                if (aiResponse.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Gemma:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(aiResponse, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
