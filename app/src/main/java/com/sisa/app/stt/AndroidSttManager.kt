package com.sisa.app.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * STT-Engine über Android SpeechRecognizer.
 * Strategie: zuerst On-Device (offline), bei fehlendem deutschem Sprachpaket
 * (LANGUAGE_NOT_SUPPORTED/UNAVAILABLE — auf vielen Geräten der Fall) genau
 * einmal automatisch online neu versuchen. Erst wenn beides scheitert, Fehler.
 */
class AndroidSttManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit = {},
    private val onError: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var lastPreferOffline = true

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            val googleTtsComponent = android.content.ComponentName(
                "com.google.android.tts",
                "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
            )
            val recognizer = try {
                SpeechRecognizer.createSpeechRecognizer(context, googleTtsComponent)
            } catch (t: Throwable) {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            speechRecognizer = recognizer.apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onListeningStateChanged(true)
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        onListeningStateChanged(false)
                    }

                    override fun onError(error: Int) {
                        onListeningStateChanged(false)
                        // Deutsches Offline-Paket fehlt? Einmal online neu versuchen.
                        if (lastPreferOffline &&
                            (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                             error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                        ) {
                            startListening(preferOffline = false)
                            return
                        }
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No input (timeout)"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English speech recognition is unavailable (offline and online)"
                            else -> "Speech recognition error: $error"
                        }
                        onError(errorMsg)
                    }

                    override fun onResults(results: Bundle?) {
                        onListeningStateChanged(false)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0]
                            val nowNs = SystemClock.elapsedRealtimeNanos()
                            Log.i("BENCH", "BENCH stt_final t_elapsed_ns=$nowNs text=\"$text\"")
                            onResult(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val partial = matches[0]
                            if (partial.isNotBlank()) {
                                val nowNs = SystemClock.elapsedRealtimeNanos()
                                Log.i("BENCH", "BENCH stt_partial t_elapsed_ns=$nowNs text=\"$partial\"")
                                onPartialResult(partial)
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    fun startListening(preferOffline: Boolean = true) {
        lastPreferOffline = preferOffline
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now…")
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        onListeningStateChanged(false)
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
