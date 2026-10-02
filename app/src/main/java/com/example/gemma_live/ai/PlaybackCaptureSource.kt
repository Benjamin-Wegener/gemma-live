package com.example.gemma_live.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Audio-Quelle für In-Emulator-Duplex-Tests: greift per Playback-Capture-API
 * (API 29+) digital den Sound der Companion-App (OutsideVoice) ab — ohne
 * Host-Mikrofon, ohne TCP-Injection, ohne Raumakustik.
 *
 * Benötigt ein MediaProjection-Token (einmalige Nutzer-Zustimmung pro Boot,
 * im Test per ACTION_REQUEST_CAPTURE + ADB-Tap automatisiert). Erfasst NUR
 * die Companion-UID (eigene Stimme + Systemtöne ausgeschlossen), daher
 * bleiben VAD/Barge-In-Semantik erhalten.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackCaptureSource(
    private val context: Context,
    private val projection: MediaProjection,
    private val config: AudioLoop.Config = AudioLoop.Config(),
    private val targetPackage: String = "com.example.voice.companion",
) : AudioFrameSource {

    private var audioRecord: AudioRecord? = null

    override fun start() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "PlaybackCapture braucht API 29+"
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        check(granted) { "RECORD_AUDIO not granted" }

        val uid = companionUid()
        // Einziger Builder (API 29-34): braucht das MediaProjection-Token.
        // Usage-Match statt UID-Match: Google-TTS rendert ggf. im Engine-Prozess
        // (fremde UID); USAGE_MEDIA trifft die Companion-Wiedergabe zuverlässig.
        // Gemmas eigene Stimme (USAGE_ASSISTANT) bleibt ausgeschlossen.
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(config.audioEncoding)
            .setSampleRate(config.sampleRateHz)
            .setChannelMask(config.channelConfig)
            .build()
        val minBufferBytes = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            config.channelConfig,
            config.audioEncoding,
        )
        check(minBufferBytes > 0) { "AudioRecord minBuffer invalid: $minBufferBytes" }
        // Capture-Config IMPLIZIERT die Quelle: setAudioSource() ist verboten
        // ("Cannot both set audio source and set playback capture config").
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBufferBytes, config.frameSizeSamples * 2 * 4))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "PlaybackCapture init failed state=${record.state}"
        }
        record.startRecording()
        audioRecord = record
        Log.i("GemmaVoice", "PlaybackCaptureSource aktiv (uid=$uid pkg=$targetPackage)")
    }

    private fun companionUid(): Int {
        val pm = context.packageManager
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(targetPackage, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(targetPackage, 0)
        }
        return ai.uid
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
        runCatching { projection.stop() }
    }
}
