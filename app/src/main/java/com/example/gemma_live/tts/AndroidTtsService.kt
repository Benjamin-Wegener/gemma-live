package com.example.gemma_live.tts

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale

class AndroidTtsService(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pendingDone: (() -> Unit)? = null
    private var activeCount = 0

    fun setLanguage(locale: Locale) {
        tts?.setLanguage(locale)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let {
                val result = it.setLanguage(Locale.US)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isInitialized = true
                }
                it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        synchronized(lock) { activeCount++ }
                    }
                    override fun onDone(utteranceId: String?) {
                        onUtteranceEnd()
                    }
                    override fun onError(utteranceId: String?) {
                        onUtteranceEnd()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onUtteranceEnd()
                    }
                })
            }
        }
    }

    private fun onUtteranceEnd() {
        val cb = synchronized(lock) {
            if (activeCount > 0) activeCount--
            val c = pendingDone
            pendingDone = null
            c
        }
        cb?.let { mainHandler.post { it() } }
    }

    /** Wartet, bis keine Äußerung mehr läuft (Timeout setzt der Aufrufer). */
    suspend fun awaitIdle() {
        while (true) {
            val busy = synchronized(lock) { activeCount > 0 }
            if (!busy) return
            kotlinx.coroutines.delay(200)
        }
    }

    /**
     * Medienlautstärke prüfen. Bei Minimum: Toast „Lautstärke erhöhen" und false.
     * Verhindert stummes Sprechen ins Leere (häufigster „kein Ton"-Grund).
     */
    fun ensureAudible(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "Bitte Lautstärke erhöhen \u2013 sonst bleibt Gemma stumm.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return false
        }
        return true
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        if (!ensureAudible()) {
            onDone()
            return
        }
        if (isInitialized) {
            synchronized(lock) { pendingDone = onDone }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "GEMMA_TTS_${System.currentTimeMillis()}")
        } else {
            onDone()
        }
    }

    fun stop() {
        tts?.stop()
        val cb = synchronized(lock) {
            val c = pendingDone
            pendingDone = null
            c
        }
        cb?.let { mainHandler.post { it() } }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
