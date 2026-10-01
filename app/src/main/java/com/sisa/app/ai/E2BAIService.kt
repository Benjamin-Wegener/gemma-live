package com.sisa.app.ai

import android.util.Log
import com.sisa.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Streaming / Live Chat Service für Gemma 4 mit Sub-Sentence Chunker für minimale TTFA.
 */
class E2BAIService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val endpointUrl = if (BuildConfig.E2B_CONTAINER_URL.isNotBlank()) {
        "${BuildConfig.E2B_CONTAINER_URL}/v1/chat/completions"
    } else {
        "http://10.0.2.2:8080/v1/chat/completions"
    }

    /**
     * Streamt LLM-Antwort und übergibt Chunks an [onChunkReady], sobald eine
     * sinntragende Einheit vorliegt (z. B. 6–10 Wörter oder Interpunktion).
     */
    suspend fun streamChatResponse(
        userUtterance: String,
        onChunkReady: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val fullAccumulator = StringBuilder()
        val chunkAccumulator = StringBuilder()

        try {
            val json = JSONObject().apply {
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are Gemma, a friendly, helpful AI in fast live voice mode. Always answer in English, briefly and directly in one or two sentences, without revealing chain-of-thought.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userUtterance)
                    })
                })
                put("stream", true)
                put("max_tokens", 400)
                put("temperature", 0.7)
            }

            val request = Request.Builder()
                .url(endpointUrl)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .build()

            val response = client.newCall(request).execute()
            val stream = response.body?.byteStream() ?: return@withContext userUtterance
            val reader = BufferedReader(InputStreamReader(stream))

            var isFirstToken = true
            var firstChunkSent = false
            while (coroutineContext.isActive) {
                val line = reader.readLine() ?: break
                val l = line.trim()
                if (l.isEmpty() || l.startsWith(":")) continue
                if (l == "data: [DONE]") break
                if (l.startsWith("data: ")) {
                    val jsonStr = l.substring(6)
                    try {
                        val obj = JSONObject(jsonStr)
                        val choices = obj.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            if (delta != null && !delta.isNull("content")) {
                                val token = delta.optString("content", "")
                                if (token.isNotEmpty() && token != "null") {
                                    if (isFirstToken) {
                                        isFirstToken = false
                                        val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
                                        Log.i("BENCH", "BENCH llm_first_token t_elapsed_ns=$nowNs")
                                    }
                                    fullAccumulator.append(token)
                                    chunkAccumulator.append(token)

                                    if (shouldFlushChunk(chunkAccumulator.toString(), isFirstChunk = !firstChunkSent)) {
                                        val readyChunk = chunkAccumulator.toString().trim()
                                        chunkAccumulator.clear()
                                        if (readyChunk.isNotEmpty()) {
                                            // Messpunkt für die TTFA-Optimierung: erlaubt metrics.py
                                            // zu prüfen, ob der (erste) Chunk früher ausgeliefert wird.
                                            val flushNs = android.os.SystemClock.elapsedRealtimeNanos()
                                            Log.i(
                                                "BENCH",
                                                "BENCH llm_chunk t_elapsed_ns=$flushNs " +
                                                    "first=${!firstChunkSent} " +
                                                    "words=${readyChunk.split(Regex("\\s+")).size} " +
                                                    "chars=${readyChunk.length}"
                                            )
                                            firstChunkSent = true
                                            onChunkReady(readyChunk)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("E2BAIService", "Error parsing SSE chunk", e)
                    }
                }
            }

            val remaining = chunkAccumulator.toString().trim()
            if (remaining.isNotEmpty()) {
                onChunkReady(remaining)
            }

            fullAccumulator.toString().trim().ifEmpty { "I understood you." }
        } catch (e: Exception) {
            Log.e("E2BAIService", "Streaming failed", e)
            val fallback = "I understood you: $userUtterance"
            onChunkReady(fallback)
            fallback
        }
    }

    /**
     * Entscheidet, ob der aktuell gepufferte Chunk an die TTS geflusht werden soll.
     * Für den allerersten Chunk (Time-To-First-Audio-kritisch) reicht bereits ein
     * Mindestmaß von 3 Wörtern, damit Piper-VITS so früh wie möglich zu synthetisieren
     * beginnt (siehe HANDOFF.md/roadmap.md "TTFA-Reduktion"). Nachfolgende Chunks
     * behalten die konservativere 4-Wort-Schwelle, um TTS-Fragmentierung zu vermeiden.
     */
    private fun shouldFlushChunk(buffer: String, isFirstChunk: Boolean): Boolean {
        val trimmed = buffer.trim()
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordCount = words.size
        val minWords = if (isFirstChunk) 3 else 4
        if (wordCount < minWords) return false

        val lastChar = trimmed.lastOrNull()
        if (lastChar == '.' || lastChar == '!' || lastChar == '?' || lastChar == ':' || lastChar == ';') {
            return true
        }

        if (wordCount >= 6 && (lastChar == ',' || words.last().lowercase() in listOf("und", "aber", "oder"))) {
            return true
        }

        if (wordCount >= 14) {
            val openWords = setOf("weil", "dass", "um", "zu", "an", "auf", "mit", "nach")
            if (words.last().lowercase() !in openWords) {
                return true
            }
        }

        return false
    }

    suspend fun getChatResponse(userUtterance: String): String {
        val sb = StringBuilder()
        streamChatResponse(userUtterance) { chunk ->
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(chunk)
        }
        return sb.toString()
    }
}
