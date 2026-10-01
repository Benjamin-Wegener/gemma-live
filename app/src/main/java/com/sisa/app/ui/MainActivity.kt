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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import com.sisa.app.tts.GemmaVoiceService
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class LiveState {
    LOADING, IDLE, LISTENING, THINKING, SPEAKING
}

data class ChatBubbleMessage(val text: String, val fromUser: Boolean)

class MainActivity : ComponentActivity() {

    private lateinit var androidTts: AndroidTtsService
    private lateinit var voiceService: com.sisa.app.tts.GemmaVoiceService
    private val gemmaVoice get() = voiceService
    private var sttManager: AndroidSttManager? = null
    private val speechBuffer = java.io.ByteArrayOutputStream()
    private val speechBufferLock = Any()
    @Volatile private var speechPreRoll = FloatArray(0)
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
    /** Direct-audio turns must never wait behind the legacy text/STT path. */
    private val directAudioInFlight = AtomicBoolean(false)
    /** Invalidiert Frames und Antworten eines gestoppten Mikrofon-Laufs. */
    private val audioSessionGeneration = AtomicLong(0)
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
    private val chatMessages = mutableStateListOf<ChatBubbleMessage>()
    private var volumeLevel = mutableFloatStateOf(0f)
    private var isHandsFreeActive = mutableStateOf(false)
    private var isEngineReady = mutableStateOf(false)
    private var engineStatus = mutableStateOf("Loading engine …")
    private var modelDownloadProgress = mutableFloatStateOf(0f)
    private var benchRunning = mutableStateOf(false)
    private var benchStatus = mutableStateOf("")
    private var benchReport = mutableStateOf<LlmBenchmark.Report?>(null)
    private var showBenchDialog = mutableStateOf(false)
    var showVoiceSelector = mutableStateOf(false)
    var selectedVoiceId = mutableStateOf("en_US-amy-medium")
    var isVoiceDownloading = mutableStateOf(false)
    var voiceDownloadProgress = mutableFloatStateOf(0f)
    var voiceDownloadError = mutableStateOf<String?>(null)
    var voiceDownloadingName = mutableStateOf("")
    var voiceDownloadDetail = mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This is a hands-free live conversation screen. Keep the display awake
        // while this foreground activity is visible; Android releases the flag
        // automatically when the app leaves the foreground.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audioSourceMode = loadAudioSourceMode()
        android.util.Log.i("LiveMode", "AudioSourceMode=$audioSourceMode")
        if (audioSourceMode == "capture") ensureCaptureHolder()

        androidTts = AndroidTtsService(this)
        voiceService = com.sisa.app.tts.GemmaVoiceService(this, androidTts)

        // Gemma E2B handles direct audio. Android STT remains an optional fallback.
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
        val voicePrefs = getPreferences(MODE_PRIVATE)
        val savedVoiceId = voicePrefs.getString("selected_voice_id", "en_US-amy-medium")
            ?: "en_US-amy-medium"
        selectedVoiceId.value = savedVoiceId
        val initialVoice = com.sisa.app.download.AppModelManager.PIPER_VOICES.find { it.id == savedVoiceId }
            ?: com.sisa.app.download.AppModelManager.PIPER_VOICES.first()
        val lang = initialVoice.id.substringBefore("_")
        localGemma.currentLanguage = lang
        voiceService.currentLanguage = lang
        androidTts.setLanguage(java.util.Locale.forLanguageTag(lang))
        voiceService.initAsync(initialVoice.modelFile) { }
        liveState.value = LiveState.LOADING
        isHandsFreeActive.value = false
        engineStatus.value = "Loading engine …"
        scope.launch(Dispatchers.Default) {
            var downloadedGemmaThisLaunch = false
            // Auto-download Gemma model if not found (with progress, resume, retry)
            if (!com.sisa.app.download.AppModelManager.isPrivateModelInstalled(this@MainActivity, com.sisa.app.download.AppModelManager.GEMMA_FILE)) {
                downloadedGemmaThisLaunch = true
                modelDownloadProgress.floatValue = 0f
                engineStatus.value = "Checking Download/models for Gemma…"
                android.util.Log.i("LiveMode", "Checking Download/models before network download")
                var retryCount = 0
                val maxRetries = 3
                var downloadSuccess = false
                while (retryCount < maxRetries && !downloadSuccess) {
                    try {
                        com.sisa.app.download.AppModelManager.downloadModel(
                            context = this@MainActivity,
                            urlString = com.sisa.app.download.AppModelManager.GEMMA_4_URL,
                            targetFileName = com.sisa.app.download.AppModelManager.GEMMA_FILE,
                            expectedSha256 = com.sisa.app.download.AppModelManager.GEMMA_4_SHA256
                        ).collect { progress ->
                            if (progress.isCompleted) {
                                android.util.Log.i("LiveMode", "Gemma model download completed")
                                downloadSuccess = true
                                withContext(Dispatchers.Main) {
                                    // 100% is reserved for a model that is actually usable.
                                    modelDownloadProgress.floatValue = 0.99f
                                    engineStatus.value = "Starting Gemma model… 99%"
                                }
                            } else if (progress.error != null) {
                                android.util.Log.e("LiveMode", "Gemma model download error: ${progress.error}")
                            } else {
                                // Keep the final 1% for SHA-256 verification and model load.
                                val fraction = progress.progressPercent.coerceAtMost(0.99f)
                                val percent = (fraction * 100).toInt()
                                val mb = progress.bytesDownloaded / (1024 * 1024)
                                val totalMb = progress.totalBytes / (1024 * 1024)
                                withContext(Dispatchers.Main) {
                                    modelDownloadProgress.floatValue = fraction
                                    val phase = if (progress.progressPercent >= 1f) "Verifying Gemma model" else "Downloading Gemma model"
                                    engineStatus.value = "$phase… $percent% ($mb/$totalMb MB)"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("LiveMode", "Gemma model download failed (attempt ${retryCount + 1}/$maxRetries)", e)
                        retryCount++
                        if (retryCount < maxRetries) {
                            withContext(Dispatchers.Main) {
                                engineStatus.value = "Download failed, retrying (${retryCount + 1}/$maxRetries)…"
                            }
                            kotlinx.coroutines.delay(2000)
                        }
                    }
                }
                if (!downloadSuccess) {
                    withContext(Dispatchers.Main) {
                        engineStatus.value = "Download failed after $maxRetries attempts. Check connection."
                    }
                }
            }
            // LiteRT exposes no sub-step callbacks while the engine maps and
            // prepares the model. Use a monotonic estimated progress value so
            // the bar also advances when a previously downloaded model loads.
            val engineProgressJob = scope.launch {
                if (downloadedGemmaThisLaunch) modelDownloadProgress.floatValue = 0.99f
                engineStatus.value = "Loading Gemma engine… ${"%.1f".format(modelDownloadProgress.floatValue * 100)}%"
                while (isActive) {
                    kotlinx.coroutines.delay(300)
                    val current = modelDownloadProgress.floatValue
                    val step = when {
                        current < 0.90f -> 0.015f
                        current < 0.99f -> 0.003f
                        else -> 0.001f
                    }
                    val next = (current + step).coerceAtMost(0.999f)
                    modelDownloadProgress.floatValue = next
                    engineStatus.value = "Loading Gemma engine… ${"%.1f".format(next * 100)}%"
                }
            }
            val loadInfo = localGemma.load()
            engineProgressJob?.cancel()
            withContext(Dispatchers.Main) {
                if (loadInfo != null) {
                    android.util.Log.i("LiveMode", "LocalGemma ready: ${loadInfo.backend.label}, ${loadInfo.modelFile}")
                    modelDownloadProgress.floatValue = 1f
                    engineStatus.value = "Gemma model ready… 100%"
                    kotlinx.coroutines.delay(250)
                    isEngineReady.value = true
                    engineStatus.value = "Ready (${loadInfo.backend.label})"
                    // Auto-start hands-free once engine is loaded
                    autoStartHandsFree()
                } else {
                    android.util.Log.w("LiveMode", "LocalGemma could not be loaded (model not imported?)")
                    isEngineReady.value = false
                    engineStatus.value = "Model missing or could not be loaded"
                    liveState.value = LiveState.IDLE
                }
            }
        }

        registerLiveBroadcastReceiver()

        // Show voice selector on first launch
        val prefs = getPreferences(MODE_PRIVATE)
        if (!prefs.getBoolean("voice_selected", false)) {
            showVoiceSelector.value = true
        }
        val voiceSelectorState = showVoiceSelector
        val selectedVoiceState = selectedVoiceId

        setContent {
            // Voice selector dialog (Issue #8: Inline-Fortschritt beim Nachladen)
            if (voiceSelectorState.value) {
                val voices = com.sisa.app.download.AppModelManager.PIPER_VOICES
                var selectedId by remember { mutableStateOf(selectedVoiceState.value) }
                val downloading = isVoiceDownloading.value
                val voiceProgress = voiceDownloadProgress.floatValue
                val voiceError = voiceDownloadError.value
                val voiceDetail = voiceDownloadDetail.value
                val downloadingName = voiceDownloadingName.value
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { if (!downloading) voiceSelectorState.value = false },
                    title = { Text("Select Voice", fontWeight = FontWeight.Bold) },
                    text = {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(scrollState)
                        ) {
                            voices.forEach { voice ->
                                val installed = remember(voice.modelFile) {
                                    com.sisa.app.download.AppModelManager.isModelInstalled(
                                        this@MainActivity, voice.modelFile
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !downloading) { selectedId = voice.id }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.RadioButton(
                                        selected = selectedId == voice.id,
                                        onClick = { if (!downloading) selectedId = voice.id },
                                        enabled = !downloading
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(voice.name, style = MaterialTheme.typography.bodyMedium)
                                        if (!installed) {
                                            Text(
                                                "Not downloaded — tap OK to fetch (~60 MB)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                    if (downloading && selectedId == voice.id) {
                                        Spacer(Modifier.width(8.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
                            // Inline-Download-Fortschritt (Issue #8, Punkte 1–3)
                            if (downloading) {
                                Spacer(Modifier.height(12.dp))
                                val pct = (voiceProgress.coerceIn(0f, 1f) * 100).toInt()
                                Text(
                                    "Downloading ${downloadingName.ifBlank { "voice" }}… $pct%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = voiceProgress.coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    voiceDetail.ifBlank { "Please keep the app open…" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (voiceError != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Download failed: $voiceError",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    "Check connection & retry.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (downloading) return@TextButton
                                // Falls vorheriger Fehler: erneut versuchen
                                if (voiceError != null) voiceDownloadError.value = null
                                val target = voices.find { it.id == selectedId } ?: return@TextButton
                                selectedVoiceState.value = selectedId
                                prefs.edit().putBoolean("voice_selected", true)
                                    .putString("selected_voice_id", selectedId).apply()
                                // Set language based on voice selection
                                val lang = selectedId.substringBefore("_")
                                localGemma.currentLanguage = lang
                                voiceService.currentLanguage = lang
                                androidTts.setLanguage(java.util.Locale.forLanguageTag(lang))
                                // Bereits installiert -> sofort laden & Dialog schließen
                                if (com.sisa.app.download.AppModelManager.isModelInstalled(this@MainActivity, target.modelFile)) {
                                    voiceSelectorState.value = false
                                    voiceService.loadVoice(target.modelFile)
                                    return@TextButton
                                }
                                // Noch nicht geladen -> Dialog offen halten, Inline-Progress zeigen.
                                // Schließen erst nach Erfolg (startVoiceDownload setzt Status zurück).
                                scope.launch(Dispatchers.Default) {
                                    val ok = startVoiceDownload(target)
                                    if (ok) {
                                        withContext(Dispatchers.Main) {
                                            voiceSelectorState.value = false
                                        }
                                    }
                                    // Bei Fehler: Dialog bleibt offen, Fehler wird inline angezeigt (Retry via OK)
                                }
                            },
                            enabled = !downloading
                        ) {
                            Text(
                                when {
                                    downloading -> "Downloading…"
                                    voiceError != null -> "Retry"
                                    else -> "OK"
                                }
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                if (downloading) return@TextButton
                                voiceSelectorState.value = false
                                prefs.edit().putBoolean("voice_selected", true).apply()
                            },
                            enabled = !downloading
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            LiveModeScreen(
                liveState = liveState.value,
                transcript = currentTranscript.value,
                aiResponse = lastAiResponse.value,
                chatMessages = chatMessages,
                volumeLevel = volumeLevel.floatValue,
                engineReady = isEngineReady.value,
                engineStatus = engineStatus.value,
                downloadProgress = modelDownloadProgress.floatValue,
                showVoiceSelector = voiceSelectorState.value,
                onShowVoiceSelector = { voiceSelectorState.value = it },
                onMicPulseTap = ::onManualMicTap,
                isVoiceDownloading = isVoiceDownloading.value,
                voiceDownloadProgress = voiceDownloadProgress.floatValue,
                voiceDownloadDetail = voiceDownloadDetail.value,
                voiceDownloadingName = voiceDownloadingName.value,
                voiceDownloadError = voiceDownloadError.value
            )
        }
    }

    /**
     * Issue #8: Voice-Download mit sichtbarem Fortschritt.
     * Steuert [isVoiceDownloading], [voiceDownloadProgress], [voiceDownloadDetail]
     * und [voiceDownloadError]; lädt nach Erfolg die Stimme via [voiceService].
     * @return true bei Erfolg, false bei Fehler.
     */
    private suspend fun startVoiceDownload(
        voice: com.sisa.app.download.AppModelManager.VoiceModel,
        onFinished: ((Boolean) -> Unit)? = null
    ): Boolean {
        withContext(Dispatchers.Main) {
            isVoiceDownloading.value = true
            voiceDownloadProgress.floatValue = 0f
            voiceDownloadError.value = null
            voiceDownloadingName.value = voice.name
            voiceDownloadDetail.value = "Starting download…"
            engineStatus.value = "Downloading voice: ${voice.name}…"
        }
        var success = false
        var failureMessage: String? = null
        try {
            com.sisa.app.download.AppModelManager.downloadModel(
                context = this,
                urlString = voice.downloadUrl,
                targetFileName = voice.modelFile,
                expectedCrc32 = if (voice.crc32 != 0L) voice.crc32 else null,
                expectedSha256 = voice.sha256
            ).collect { progress ->
                if (progress.isCompleted) {
                    success = true
                    withContext(Dispatchers.Main) {
                        voiceDownloadProgress.floatValue = 1f
                        voiceDownloadDetail.value = "Verifying & loading…"
                        engineStatus.value = "Voice downloaded: ${voice.name}"
                    }
                } else if (progress.error != null) {
                    android.util.Log.e("LiveMode", "Voice download error: ${progress.error}")
                    failureMessage = progress.error
                    withContext(Dispatchers.Main) {
                        voiceDownloadError.value = progress.error
                        voiceDownloadDetail.value = progress.error ?: "Download failed"
                        engineStatus.value = "Voice download failed: ${progress.error}"
                    }
                } else {
                    val fraction = progress.progressPercent.coerceIn(0f, 1f)
                    val pct = (fraction * 100).toInt()
                    val mb = progress.bytesDownloaded / (1024 * 1024)
                    val totalMb = progress.totalBytes / (1024 * 1024)
                    val detail = if (progress.totalBytes > 0) "$pct% ($mb/$totalMb MB)" else "$pct%"
                    withContext(Dispatchers.Main) {
                        voiceDownloadProgress.floatValue = fraction
                        voiceDownloadDetail.value = detail
                        engineStatus.value = "Downloading voice ${voice.name}: $detail"
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LiveMode", "Voice download failed", e)
            failureMessage = e.localizedMessage ?: e.message ?: "Download failed"
            withContext(Dispatchers.Main) {
                voiceDownloadError.value = failureMessage
                voiceDownloadDetail.value = failureMessage ?: "Download failed"
                engineStatus.value = "Voice download failed: $failureMessage"
            }
        }
        // Checksum-Mismatch / Abbruch ohne Completion als Fehler werten
        if (!success && failureMessage == null) {
            failureMessage = voiceDownloadError.value ?: "Download incomplete — check connection & retry"
            withContext(Dispatchers.Main) {
                if (voiceDownloadError.value == null) voiceDownloadError.value = failureMessage
                engineStatus.value = "Voice download failed: $failureMessage"
            }
        }
        if (success) {
            voiceService.loadVoice(voice.modelFile)
            withContext(Dispatchers.Main) {
                voiceDownloadProgress.floatValue = 1f
                voiceDownloadDetail.value = "Ready"
                // Kurzes 100%-Feedback, dann Status zurücksetzen (Issue #8, Punkt 4)
                engineStatus.value = "Voice ready: ${voice.name}"
            }
            kotlinx.coroutines.delay(700)
            withContext(Dispatchers.Main) {
                isVoiceDownloading.value = false
                voiceDownloadProgress.floatValue = 0f
                voiceDownloadDetail.value = ""
                voiceDownloadError.value = null
                voiceDownloadingName.value = ""
            }
        } else {
            withContext(Dispatchers.Main) {
                isVoiceDownloading.value = false
            }
        }
        withContext(Dispatchers.Main) { onFinished?.invoke(success) }
        return success
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
            engineStatus.value = "Microphone permission needed …"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    private fun runLlmBenchmark() {
        if (benchRunning.value) return
        benchRunning.value = true
        benchStatus.value = "Starting benchmark …"
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
                    benchStatus.value = "Done – ${report.runs.count { it.ok }}/${report.runs.size} runs ok"
                }
            } catch (t: Throwable) {
                android.util.Log.e("LiveMode", "Benchmark failed", t)
                withContext(Dispatchers.Main) { benchStatus.value = "Error: ${t.message}" }
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
                        gemmaVoice.speak(text) {
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
        chatMessages.add(ChatBubbleMessage(utterance, fromUser = true))
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
                        gemmaVoice.speak(chunk) {
                            if (isHandsFreeActive.value) {
                                liveState.value = LiveState.LISTENING
                            } else {
                                liveState.value = LiveState.IDLE
                            }
                        }
                    } else {
                        lastAiResponse.value = "${lastAiResponse.value} $chunk"
                        aiSpeakStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                        gemmaVoice.speak(chunk) {
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
                        chatMessages.add(ChatBubbleMessage(fullResponse, fromUser = false))
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
                    chatMessages.add(ChatBubbleMessage(fullResponse, fromUser = false))
                } else {
                    android.util.Log.e("LiveMode", "LocalGemma nicht verfügbar und kein Fallback aktiv.")
                    liveState.value = LiveState.IDLE
                    lastAiResponse.value = "Model is still loading or not available."
                }
            } finally {
                speechMutex.unlock()
            }
        }
    }

    /** Separates the Direct-Audio system-prompt envelope from speech output. */
    private fun parseDirectAudioReply(raw: String): Pair<String, String> {
        val rawTranscript = Regex("(?s)\\[TRANSCRIPT\\]\\s*(.*?)\\s*\\[/TRANSCRIPT\\]")
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val transcript = if (isPromptLeak(rawTranscript)) {
            Log.w("LiveMode", "Discarded prompt text incorrectly returned as audio transcript")
            "[unclear]"
        } else {
            rawTranscript
        }
        val answer = Regex("(?s)\\[ANSWER\\]\\s*(.*?)\\s*\\[/ANSWER\\]")
            .find(raw)?.groupValues?.getOrNull(1)?.trim()
            ?: raw.replace(Regex("(?s)\\[TRANSCRIPT\\].*?\\[/TRANSCRIPT\\]"), "").trim()
        return transcript to answer
    }

    /** A short/quiet first recording can make the model echo the audio instruction. */
    private fun isPromptLeak(text: String): Boolean {
        val normalized = text.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .filter(Char::isLetterOrDigit)
        return listOf(
            "beantworteausschliesslichdengesprocheneninhalt",
            "wiederholedieseanweisung",
            "transcript",
            "recognizedspokentext",
            "onlyaudiblewords",
            "systemprompt"
        ).any(normalized::contains)
    }

    private fun interruptAiSpeech() {
        if (liveState.value == LiveState.SPEAKING) {
            android.util.Log.i("BENCH", "BENCH barge_flush t_elapsed_ns=${android.os.SystemClock.elapsedRealtimeNanos()} reason=barge_in")
            gemmaVoice.stop(reason = "barge_in")
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
        // Der große Mikrofon-Puls ist der eine Live-Schalter: auch während Gemma
        // spricht stoppt ein Tippen TTS *und* die laufende Mikrofonaufnahme.
        if (liveState.value != LiveState.LOADING) toggleHandsFree()
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
            gemmaVoice.stop()
            liveState.value = LiveState.IDLE
        }
    }

    private fun startContinuousAudioLoop() {
        val sessionGeneration = audioSessionGeneration.incrementAndGet()
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
                loop.start(sourceOverride = sourceOverride) frameListener@ { frame ->
                    if (!isHandsFreeActive.value || audioSessionGeneration.get() != sessionGeneration) {
                        return@frameListener
                    }
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
                                val aiSpeaking = gemmaVoice.isSpeaking || liveState.value == LiveState.SPEAKING
                                segmentHadAiAudio = aiSpeaking
                                segmentBargeInAccepted = false
                                segmentPeak = frame.peak
                                segmentRmsDb = frame.rmsDb
                                if (aiSpeaking) {
                                    // Echo-Schutz: eigene Ausgabe kennen, nur echten Einwurf werten
                                    val elapsedMs = (nowNs - aiSpeakStartNs) / 1_000_000L
                                    val loudEnough = frame.peak >= BARGE_MIN_PEAK || frame.rmsDb >= BARGE_MIN_RMS_DB
                                    // Audio ist physisch vorbei, aber isSpeaking hält das Nachhall-Fenster
                                    // (gemmaVoice.ECHO_TAIL_MS) offen. Hier ist die 900ms-Anlaufzeit
                                    // nicht mehr relevant — entscheidet nur noch die Lautstärke.
                                    val tailMs = if (gemmaVoice.audioEndedNs > 0L) (nowNs - gemmaVoice.audioEndedNs) / 1_000_000L else Long.MAX_VALUE
                                    val inEchoTail = tailMs < GemmaVoiceService.ECHO_TAIL_MS
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
                                    // Eine volle Sekunde vor dem VAD-Start bewahren. Der VAD
                                    // erkennt Sprache erst nach einigen Frames; ohne Pre-Roll
                                    // fehlt Gemma häufig die erste Silbe bzw. das erste Wort.
                                    val ring = loop.snapshot()
                                    val samplesBeforeCurrentFrame = (ring.size - frame.samples.size).coerceAtLeast(0)
                                    val preRollStart = (samplesBeforeCurrentFrame - 16_000).coerceAtLeast(0)
                                    val preRollShorts = ring.copyOfRange(preRollStart, samplesBeforeCurrentFrame)
                                    speechPreRoll = FloatArray(preRollShorts.size) { index ->
                                        preRollShorts[index] / 32768.0f
                                    }
                                    speechBuffer.reset()
                                    isCollectingSpeech = true
                                    for (sample in preRollShorts) {
                                        speechBuffer.write(sample.toInt() and 0xff)
                                        speechBuffer.write((sample.toInt() shr 8) and 0xff)
                                    }
                                    // Puffer mit aktuellem Frame füllen
                                    val byteBuf = java.nio.ByteBuffer.allocate(frame.samples.size * 2)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    for (s in frame.samples) byteBuf.putShort(s)
                                    speechBuffer.write(byteBuf.array())
                                }
                            }
                            is TurnDetector.Event.SpeechStopped -> {
                                if (!isHandsFreeActive.value || audioSessionGeneration.get() != sessionGeneration) {
                                    synchronized(speechBufferLock) { speechBuffer.reset() }
                                    return@process
                                }
                                android.util.Log.i("LiveMode", "VAD SpeechStopped detected durationMs=${event.speechDurationMs}")
                                isCollectingSpeech = false
                                // Echo-Drop: lief die KI-Ausgabe während dieses Segments, ist es Echo —
                                // die eigene Ausgabe darf nie an STT. isSpeaking bleibt über Chunk-Grenzen
                                // true (chunk_switch), liveState kann schon LISTENING sein, und der VAD
                                // meldet wegen Nachhall gern ~300ms nach Audioende noch "Sprache".
                                // Ausnahme: angenommener Barge-In ist echter Nutzer-Turn.
                                val aiAudioInSegment = segmentHadAiAudio || gemmaVoice.isSpeaking
                                val aiState = liveState.value == LiveState.SPEAKING || liveState.value == LiveState.LISTENING
                                if (aiAudioInSegment && !segmentBargeInAccepted && aiState) {
                                    android.util.Log.i("BENCH", "BENCH echo_drop t_elapsed_ns=${android.os.SystemClock.elapsedRealtimeNanos()} durationMs=${event.speechDurationMs} startedDuringAi=${segmentHadAiAudio} stillSpeaking=${gemmaVoice.isSpeaking} peak=$segmentPeak rmsDb=$segmentRmsDb")
                                    synchronized(speechBufferLock) { speechBuffer.reset() }
                                    // VAD-Segment verwerfen, kein Whisper-Call
                                } else {
                                val sileroSamples = event.audioSamples
                                val preRoll = speechPreRoll
                                speechPreRoll = FloatArray(0)
                                    val pcmData: ShortArray = synchronized(speechBufferLock) {
                                        val bytes = speechBuffer.toByteArray()
                                        speechBuffer.reset()
                                        val shorts = ShortArray(bytes.size / 2)
                                        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                            .asShortBuffer().get(shorts)
                                        shorts
                                    }
                                val turnSamples = if (sileroSamples != null && sileroSamples.isNotEmpty()) {
                                    sileroSamples
                                } else if (pcmData.size >= 16000 * 0.25) {
                                    FloatArray(pcmData.size) { i -> pcmData[i] / 32768.0f }
                                } else null
                                val samplesToUse = turnSamples?.let { turn ->
                                    if (preRoll.isEmpty()) turn else preRoll + turn
                                }
                                    if (externalMicMode) {
                                        Log.i("LiveMode", "VAD turn ignored: external microphone supplies ACTION_USER_INPUT")
                                    } else if (samplesToUse == null || samplesToUse.isEmpty()) {
                                        Log.w("LiveMode", "VAD turn ignored: no usable audio samples")
                                    } else {
                                        // Do not queue this hand-off on Dispatchers.Main: a busy UI must never
                                        // make a completed VAD turn disappear without inference or a log entry.
                                        Log.i("LiveMode", "Direct audio turn queued (${samplesToUse.size} samples, preRoll=${preRoll.size})")
                                        if (!directAudioInFlight.compareAndSet(false, true)) {
                                            Log.w("LiveMode", "VAD turn skipped: direct-audio inference is already active")
                                            return@process
                                        }
                                        scope.launch(Dispatchers.Default) {
                                            try {
                                                if (!isHandsFreeActive.value || audioSessionGeneration.get() != sessionGeneration) {
                                                    Log.i("LiveMode", "Discarded audio turn from stopped microphone session")
                                                    return@launch
                                                }
                                                Log.i("LiveMode", "Direct audio turn acquired inference lock")
                                                val busy = liveState.value == LiveState.SPEAKING ||
                                                    liveState.value == LiveState.THINKING
                                                if (busy) {
                                                    Log.w("LiveMode", "VAD turn skipped: another turn is already active")
                                                    return@launch
                                                }
                                                // Post UI state without awaiting Dispatchers.Main. Awaiting it here
                                                // was the deadlock: the audio turn held the only inference lock while
                                                // the UI dispatcher did not run the continuation.
                                                scope.launch(Dispatchers.Main) {
                                                    currentTranscript.value = "Gemma E2B audio input (${"%.1f".format(samplesToUse.size / 16000.0)}s)…"
                                                    liveState.value = LiveState.THINKING
                                                }
                                                Log.i("LiveMode", "Gemma E2B Direct Audio Processing started (${samplesToUse.size} samples)")
                                                val onChunkReceived: (String) -> Unit = { chunk ->
                                                    scope.launch(Dispatchers.Main) {
                                                        // The final parsed message is rendered below; do not expose
                                                        // system-prompt envelope fragments while streaming.
                                                        lastAiResponse.value = "Recognizing speech…"
                                                    }
                                                }
                                                val rawResponse = localGemma.processAudioDirectly(samplesToUse, onChunkReceived)
                                                val (recognizedText, responseText) = parseDirectAudioReply(rawResponse)
                                                scope.launch(Dispatchers.Main) {
                                                    if (!isHandsFreeActive.value || audioSessionGeneration.get() != sessionGeneration) {
                                                        Log.i("LiveMode", "Discarded response from stopped microphone session")
                                                        return@launch
                                                    }
                                                    if (responseText.isBlank()) {
                                                        Log.w("LiveMode", "Gemma returned an empty direct-audio response")
                                                        liveState.value = LiveState.IDLE
                                                    } else {
                                                        if (recognizedText.isNotBlank()) {
                                                            currentTranscript.value = recognizedText
                                                            chatMessages.add(ChatBubbleMessage(recognizedText, fromUser = true))
                                                        }
                                                        lastAiResponse.value = responseText
                                                        chatMessages.add(ChatBubbleMessage(responseText, fromUser = false))
                                                        liveState.value = LiveState.SPEAKING
                                                        aiSpeakStartNs = SystemClock.elapsedRealtimeNanos()
                                                        gemmaVoice.speak(responseText) {
                                                            if (isHandsFreeActive.value) {
                                                                liveState.value = LiveState.LISTENING
                                                            } else {
                                                                liveState.value = LiveState.IDLE
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (t: Throwable) {
                                                Log.e("LiveMode", "Direct audio turn failed", t)
                                                scope.launch(Dispatchers.Main) { liveState.value = LiveState.IDLE }
                                            } finally {
                                                directAudioInFlight.set(false)
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
        audioSessionGeneration.incrementAndGet()
        isCollectingSpeech = false
        speechPreRoll = FloatArray(0)
        synchronized(speechBufferLock) { speechBuffer.reset() }
        turnDetector?.reset()
        turnDetector = null
        val loop = audioLoop
        audioLoop = null
        scope.launch(Dispatchers.Default) {
            runCatching { loop?.stop() }
                .onFailure { Log.w("LiveMode", "Could not stop microphone loop", it) }
        }
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
                engineStatus.value = "Engine still loading …"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        gemmaVoice.shutdown()
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
    chatMessages: List<ChatBubbleMessage> = emptyList(),
    volumeLevel: Float,
    engineReady: Boolean = true,
    engineStatus: String = "",
    downloadProgress: Float = 0f,
    showVoiceSelector: Boolean = false,
    onShowVoiceSelector: (Boolean) -> Unit = {},
    onMicPulseTap: () -> Unit = {},
    isVoiceDownloading: Boolean = false,
    voiceDownloadProgress: Float = 0f,
    voiceDownloadDetail: String = "",
    voiceDownloadingName: String = "",
    voiceDownloadError: String? = null
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
    val stateColor = when (liveState) {
        LiveState.LOADING -> Color(0xFFF59E0B)     // Amber / Laden
        LiveState.IDLE -> Color(0xFF64748B)
        LiveState.LISTENING -> Color(0xFF0EA5E9)   // Hellblau
        LiveState.THINKING -> Color(0xFFA855F7)    // Lila / Denken
        LiveState.SPEAKING -> Color(0xFF10B981)    // Smaragdgrün / Sprechen
    }

    val stateTitle = when (liveState) {
        LiveState.LOADING -> "Gemma loading …"
        LiveState.IDLE -> "Ready to listen"
        LiveState.LISTENING -> "Listening to you..."
        LiveState.THINKING -> "Gemma is thinking..."
        LiveState.SPEAKING -> "Gemma is speaking"
    }

    var showLicensesDialog by remember { mutableStateOf(false) }
    val chatListState = rememberLazyListState()

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            chatListState.animateScrollToItem(chatMessages.lastIndex)
        }
    }

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
                    Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    // Voice selection button
                    Button(
                        onClick = {
                            showLicensesDialog = false
                            onShowVoiceSelector(true)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Select Voice")
                    }

                    Text(
                        text = """
• Google Gemma 4 E2B (model weights)
  Copyright Google LLC. Use under the Gemma Terms of Use
  (separate from Apache 2.0; acceptance required to download).

• LiteRT-LM 0.17.1 (on-device inference runtime)
  Copyright Google LLC. Apache License 2.0.

• Sherpa-ONNX 1.13.8 & Next-gen Kaldi
  Copyright (c) 2022-2024 Xiaomi Corporation. Apache License 2.0.

• Piper TTS voices (downloaded at runtime, not bundled)
  Originals: Copyright (c) Michael Hansen (MIT); voice data
  per-voice CC0 / Open Audio License. Mirrors via Hugging Face.

• espeak-ng-data (bundled phoneme data)
  Copyright the espeak-ng contributors. GPL-3.0-or-later —
  see NOTICE for distributor obligations.

• Silero VAD (bundled model)
  Copyright (c) Silero Team. MIT License.

• AndroidX, Jetpack Compose & Material
  Copyright The Android Open Source Project. Apache License 2.0.

• Kotlin & Coroutines / OkHttp / Gson
  Copyright JetBrains / Square / Google. Apache License 2.0.

Full inventory: NOTICE file in the repository.
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
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
                            Text("Gemma Live", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            if (liveState == LiveState.SPEAKING) {
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "• Speaking",
                                    color = stateColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Toggle button: ? <-> X
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { showLicensesDialog = !showLicensesDialog }
                        ) {
                            Text(
                                text = if (showLicensesDialog) "✕" else "?",
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
            // Beim Hören und Nachdenken zeigt ausschließlich der Puls den Zustand.
            // Dadurch bleibt der Chat beim Übergang LISTENING <-> THINKING an derselben Stelle.
            if (liveState != LiveState.LISTENING && liveState != LiveState.THINKING) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stateTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = stateColor,
                    textAlign = TextAlign.Center
                )
                if (liveState == LiveState.LOADING) {
                    Spacer(Modifier.height(8.dp))
                    // Determinierter Downloadfortschritt: füllt sich nur von links nach rechts.
                    Box(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(stateColor.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight()
                                .fillMaxWidth(downloadProgress.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(3.dp))
                                .background(stateColor)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = engineStatus.ifBlank { "Loading engine … Hands-Free starts automatically" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = if (engineReady) "Fully automatic live mode active (Turn-Taking & Barge-In)"
                        else engineStatus.ifBlank { "Engine not ready" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Issue #8: prominenter Voice-Download-Banner über Mikrofon/Chat —
            // sichtbar auch wenn der Select-Voice-Dialog geschlossen wurde.
            if (isVoiceDownloading) {
                val voicePct = (voiceDownloadProgress.coerceIn(0f, 1f) * 100).toInt()
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Downloading voice ${voiceDownloadingName.ifBlank { "" }}… $voicePct%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = voiceDownloadProgress.coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            voiceDownloadDetail.ifBlank { "Please keep the app open…" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            if (voiceDownloadError != null && !isVoiceDownloading) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Voice download failed: $voiceDownloadError — retry via Select Voice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Zentraler Mikrofon-Puls: schaltet Live-Mikrofon und Gemma-Ausgabe gemeinsam.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onMicPulseTap)
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
                                contentDescription = "Loading",
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

            // Conversation bubbles fade softly beneath the microphone control.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = chatListState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(chatMessages) { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            modifier = Modifier.widthIn(max = 310.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    if (message.fromUser) "You" else "Gemma",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f)
                                )
                            )
                        )
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
