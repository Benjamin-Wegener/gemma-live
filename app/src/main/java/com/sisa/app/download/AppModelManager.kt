package com.sisa.app.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class DownloadProgress(
    val modelName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val isCompleted: Boolean = false,
    val error: String? = null
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) else 0f
}

object AppModelManager {

    // File names (single source of truth, also used by onboarding)
    /** Multimodales Gemma 4 mit dem für Direct Audio nötigen Audio-Encoder. */
    const val GEMMA_FILE = "gemma-4-E2B-it.litertlm"
    const val VOICE_MODEL_FILE = "de_DE-kerstin-low.onnx"
    const val VOICE_CONFIG_FILE = "de_DE-kerstin-low.onnx.json"
    const val VOICE_TOKENS_FILE = "tokens.txt"
    const val ESPEAK_DATA_DIR = "espeak-ng-data"
    private const val ESPEAK_SENTINEL = "espeak-ng-data/phontab"
    private const val MODEL_DOWNLOAD_FOLDER = "Download/models"

    private fun publicDownloadFolder(filename: String): String = MODEL_DOWNLOAD_FOLDER

    const val GEMMA_4_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    /** SHA-256 des von litert-community veröffentlichten LFS-Artefakts. */
    const val GEMMA_4_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    // Piper voice models by language
    data class VoiceModel(
        val id: String,
        val name: String,
        val modelFile: String,
        val configFile: String,
        val tokensFile: String,
        val downloadUrl: String,
        val crc32: Long
    )

    val PIPER_VOICES = listOf(
        VoiceModel("de_DE-kerstin-low", "Deutsch — Kerstin", "de_DE-kerstin-low.onnx", "de_DE-kerstin-low.onnx.json", "tokens.txt",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/de/de_DE/kerstin/low/de_DE-kerstin-low.onnx", 0x4071ce25L),
        VoiceModel("en_US-amy-medium", "English (US) — Amy", "en_US-amy-medium.onnx", "en_US-amy-medium.onnx.json", "en_US-amy-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx", 0L),
        VoiceModel("en_GB-alan-medium", "English (UK) — Alan", "en_GB-alan-medium.onnx", "en_GB-alan-medium.onnx.json", "en_GB-alan-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx", 0L),
        VoiceModel("fr_FR-gilles-medium", "Français — Gilles", "fr_FR-gilles-medium.onnx", "fr_FR-gilles-medium.onnx.json", "fr_FR-gilles-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/fr/fr_FR/gilles/medium/fr_FR-gilles-medium.onnx", 0L),
        VoiceModel("es_ES-davefx-medium", "Español — Davefx", "es_ES-davefx-medium.onnx", "es_ES-davefx-medium.onnx.json", "es_ES-davefx-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx", 0L),
        VoiceModel("it_IT-paola-medium", "Italiano — Paola", "it_IT-paola-medium.onnx", "it_IT-paola-medium.onnx.json", "it_IT-paola-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/it/it_IT/paola/medium/it_IT-paola-medium.onnx", 0L),
        VoiceModel("nl_NL-nathalie-medium", "Nederlands — Nathalie", "nl_NL-nathalie-medium.onnx", "nl_NL-nathalie-medium.onnx.json", "nl_NL-nathalie-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/nl/nl_NL/nathalie/medium/nl_NL-nathalie-medium.onnx", 0L),
        VoiceModel("ru_RU-denis-medium", "Русский — Денис", "ru_RU-denis-medium.onnx", "ru_RU-denis-medium.onnx.json", "ru_RU-denis-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/denis/medium/ru_RU-denis-medium.onnx", 0L),
        VoiceModel("ja_JP-misaki-medium", "日本語 — 美咲", "ja_JP-misaki-medium.onnx", "ja_JP-misaki-medium.onnx.json", "ja_JP-misaki-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/ja/ja_JP/misaki/medium/ja_JP-misaki-medium.onnx", 0L),
        VoiceModel("zh_CN-huayan-medium", "中文 — 华燕", "zh_CN-huayan-medium.onnx", "zh_CN-huayan-medium.onnx.json", "zh_CN-huayan-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx", 0L),
        VoiceModel("pt_BR-edresson-medium", "Português — Edresson", "pt_BR-edresson-medium.onnx", "pt_BR-edresson-medium.onnx.json", "pt_BR-edresson-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/edresson/medium/pt_BR-edresson-medium.onnx", 0L),
        VoiceModel("pl_PL-darkman-medium", "Polski — Darkman", "pl_PL-darkman-medium.onnx", "pl_PL-darkman-medium.onnx.json", "pl_PL-darkman-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/pl/pl_PL/darkman/medium/pl_PL-darkman-medium.onnx", 0L),
        VoiceModel("sv_SE-nst-medium", "Svenska — Nst", "sv_SE-nst-medium.onnx", "sv_SE-nst-medium.onnx.json", "sv_SE-nst-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/sv/sv_SE/nst/medium/sv_SE-nst-medium.onnx", 0L),
        VoiceModel("tr_TR-fahrettin-medium", "Türkçe — Fahrettin", "tr_TR-fahrettin-medium.onnx", "tr_TR-fahrettin-medium.onnx.json", "tr_TR-fahrettin-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/tr/tr_TR/fahrettin/medium/tr_TR-fahrettin-medium.onnx", 0L),
        VoiceModel("cs_CZ-jirka-medium", "Čeština — Jirka", "cs_CZ-jirka-medium.onnx", "cs_CZ-jirka-medium.onnx.json", "cs_CZ-jirka-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/cs/cs_CZ/jirka/medium/cs_CZ-jirka-medium.onnx", 0L),
        VoiceModel("ar_JO-kareem-medium", "العربية — كريم", "ar_JO-kareem-medium.onnx", "ar_JO-kareem-medium.onnx.json", "ar_JO-kareem-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/ar/ar_JO/kareem/medium/ar_JO-kareem-medium.onnx", 0L),
        VoiceModel("ko_KR-kss-medium", "한국어 — Kss", "ko_KR-kss-medium.onnx", "ko_KR-kss-medium.onnx.json", "ko_KR-kss-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/ko/ko_KR/kss/medium/ko_KR-kss-medium.onnx", 0L),
        VoiceModel("vi_VN-25hours-medium", "Tiếng Việt — 25hours", "vi_VN-25hours-medium.onnx", "vi_VN-25hours-medium.onnx.json", "vi_VN-25hours-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/vi/vi_VN/25hours/medium/vi_VN-25hours-medium.onnx", 0L),
        VoiceModel("uk_UA-mykyta-medium", "Українська — Микита", "uk_UA-mykyta-medium.onnx", "uk_UA-mykyta-medium.onnx.json", "uk_UA-mykyta-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/uk/uk_UA/mykyta/medium/uk_UA-mykyta-medium.onnx", 0L),
        VoiceModel("ca_ES-ona-medium", "Català — Ona", "ca_ES-ona-medium.onnx", "ca_ES-ona-medium.onnx.json", "ca_ES-ona-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/ca/ca_ES/ona/medium/ca_ES-ona-medium.onnx", 0L),
        VoiceModel("el_GR-rapunzelina-medium", "Ελληνικά — Rapunzelina", "el_GR-rapunzelina-medium.onnx", "el_GR-rapunzelina-medium.onnx.json", "el_GR-rapunzelina-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/el/el_GR/rapunzelina/medium/el_GR-rapunzelina-medium.onnx", 0L),
        VoiceModel("fi_FI-harri-medium", "Suomi — Harri", "fi_FI-harri-medium.onnx", "fi_FI-harri-medium.onnx.json", "fi_FI-harri-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/fi/fi_FI/harri/medium/fi_FI-harri-medium.onnx", 0L),
        VoiceModel("hu_HU-anna-medium", "Magyar — Anna", "hu_HU-anna-medium.onnx", "hu_HU-anna-medium.onnx.json", "hu_HU-anna-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/hu/hu_HU/anna/medium/hu_HU-anna-medium.onnx", 0L),
        VoiceModel("da_DK-naja-medium", "Dansk — Naja", "da_DK-naja-medium.onnx", "da_DK-naja-medium.onnx.json", "da_DK-naja-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/da/da_DK/naja/medium/da_DK-naja-medium.onnx", 0L),
        VoiceModel("hi_IN-naman-medium", "हिन्दी — नमन", "hi_IN-naman-medium.onnx", "hi_IN-naman-medium.onnx.json", "hi_IN-naman-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/naman/medium/hi_IN-naman-medium.onnx", 0L)
    )

    // Verified CRC32 checksums — mandatory check after every download / bundle copy
    const val VOICE_CONFIG_CRC: Long = 0x8aba0c34L
    const val VOICE_MODEL_CRC: Long = 0x4071ce25L // rhasspy kerstin-low + sherpa metadata
    const val VOICE_TOKENS_CRC: Long = 0xbd891de3L // from kerstin phoneme_id_map (130 symbols)
    const val GEMMA_4_GPU_CRC: Long = 0x0f55d747L

    /**
     * Primary storage (scoped storage): app-private ExternalFilesDir/models.
     * - No permission request needed, writable on all API levels
     * - Direct file path for LiteRT inference (mmap / random access)
     * MediaStore URIs alone are not sufficient for the inference engine.
     */
    fun getModelsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), ".")
        // File(parent, ".") normalisiert auf das Verzeichnis selbst
        val clean = dir.canonicalFile
        if (!clean.exists()) clean.mkdirs()
        return clean
    }

    fun getModelFile(context: Context, filename: String): File {
        val internalFile = File(File(context.filesDir, "models"), filename)
        if (internalFile.exists() && internalFile.length() > 0) {
            return internalFile
        }
        return File(getModelsDir(context), filename)
    }

    /**
     * Installation check: first private (inference-relevant), then public
     * (MediaStore Downloads/SIS_Models, visible to the user).
     * Additionally migration from the old legacy path.
     */
    fun isModelInstalled(context: Context, filename: String): Boolean {
        val private = getModelFile(context, filename)
        if (private.exists() && private.length() > 1024) return true
        if (isPublicCopyPresent(context, filename)) return true
        // Migration: alte Legacy-Datei übernehmen statt neu zu laden
        return migrateLegacyIfPresent(context, filename) != null
    }

    fun areAllModelsInstalled(context: Context): Boolean =
        isModelInstalled(context, GEMMA_FILE) && isModelInstalled(context, VOICE_MODEL_FILE)


    /**
     * For inference, only the private app storage counts (direct file
     * access). Public copies in Downloads are just for visibility.
     * Gating (onboarding continue) uses exclusively this check.
     */
    fun isPrivateModelInstalled(context: Context, filename: String): Boolean {
        return try {
            val f = getModelFile(context, filename)
            f.exists() && f.length() > 1024
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sisa voice (Kerstin, female) is in the APK (assets/models) — ONLY Gemma is downloaded.
     * Copies ONNX + JSON to app storage on first start and checks CRC.
     * Call on IO thread (63 MB copy). Returns true if both
     * files are present with verified CRC.
     */
    fun ensureVoiceFromBundle(context: Context): Boolean {
        var ok = copyAssetIfNeeded(context, "models/$VOICE_MODEL_FILE", VOICE_MODEL_FILE, VOICE_MODEL_CRC)
        ok = copyAssetIfNeeded(context, "models/$VOICE_CONFIG_FILE", VOICE_CONFIG_FILE, VOICE_CONFIG_CRC) && ok
        ok = copyAssetIfNeeded(context, "models/$VOICE_TOKENS_FILE", VOICE_TOKENS_FILE, VOICE_TOKENS_CRC) && ok
        ok = copyAssetDirIfNeeded(context, ESPEAK_DATA_DIR) && ok
        // Kleine Begleitfiles immer sichtbar in Downloads halten (5 KB, billig)
        publishToPublicDownloads(context, VOICE_CONFIG_FILE)
        publishToPublicDownloads(context, VOICE_TOKENS_FILE)
        return ok
    }

    /** Copies an asset directory (espeak-ng-data, 355 files) on first start. */
    private fun copyAssetDirIfNeeded(context: Context, assetDir: String): Boolean {
        return try {
            val destDir = File(getModelsDir(context), assetDir)
            if (File(getModelsDir(context), ESPEAK_SENTINEL).exists()) return true
            copyAssetRecursively(context, assetDir, destDir)
            File(getModelsDir(context), ESPEAK_SENTINEL).exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun copyAssetRecursively(context: Context, assetPath: String, dest: File) {
        val list = context.assets.list(assetPath)
        if (list == null || list.isEmpty()) {
            // Datei
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
        } else {
            // Verzeichnis
            dest.mkdirs()
            for (name in list) {
                copyAssetRecursively(context, "$assetPath/$name", File(dest, name))
            }
        }
    }
    private fun copyAssetIfNeeded(context: Context, assetPath: String, filename: String, expectedCrc: Long): Boolean {
        return try {
            val dest = getModelFile(context, filename)
            if (dest.exists() && calculateCrc32(dest) == expectedCrc) return true
            val tmp = File(getModelsDir(context), "$filename.bundle")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            if (calculateCrc32(tmp) != expectedCrc) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            publishToPublicDownloads(context, filename)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------- MediaStore (scoped) ---

    /** Checks via MediaStore if a visible copy exists in Downloads/SIS_Models. */
    fun isPublicCopyPresent(context: Context, filename: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val legacy = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${publicDownloadFolder(filename).removePrefix("Download/")}/$filename")
            return legacy.exists() && legacy.length() > 1024
        }
        return try {
            val projection = arrayOf(MediaStore.Downloads.SIZE)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(filename, "%${publicDownloadFolder(filename).removePrefix("Download/")}%")
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    cursor.getLong(sizeIdx) > 1024
                } else false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Publishes a copy of the verified file to Downloads/SIS_Models
     * via MediaStore (scoped storage, API 29+). Best effort: errors are
     * not fatal since the private file is sufficient for inference.
     */
    fun publishToPublicDownloads(context: Context, filename: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val src = getModelFile(context, filename)
        if (!src.exists()) return false
        return try {
            // Alte Einträge gleichen Namens entfernen (idempotenter Re-Publish),
            // inkl. vom System nummerierter Duplikate ("name (1).ext")
            val dotIdx = filename.lastIndexOf('.')
            val base = if (dotIdx > 0) filename.substring(0, dotIdx) else filename
            val sel = "(${MediaStore.Downloads.DISPLAY_NAME}=? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?) AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(filename, "$base (%", "%SIS_Models%")
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), sel, args, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idIdx))
                    try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, publicDownloadFolder(filename))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                src.inputStream().use { `in` -> `in`.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** One-time migration from the old legacy path to scoped storage. */
    private fun migrateLegacyIfPresent(context: Context, filename: String): File? {
        return try {
            @Suppress("DEPRECATION")
            val legacy = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SIS_Models/$filename")
            if (legacy.exists() && legacy.length() > 1024) {
                val dest = getModelFile(context, filename)
                if (!dest.exists()) {
                    legacy.copyTo(dest, overwrite = false)
                }
                // CRC gegenprüfen, falls bekannt
                val expected = when (filename) {
                    GEMMA_FILE -> GEMMA_4_GPU_CRC
                    VOICE_MODEL_FILE -> VOICE_MODEL_CRC
                    VOICE_CONFIG_FILE -> VOICE_CONFIG_CRC
                    else -> null
                }
                if (expected != null && calculateCrc32(dest) != expected) {
                    dest.delete() // korrupte Legacy-Datei nicht übernehmen
                    null
                } else dest
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- CRC32 ---

    fun calculateCrc32(file: File): Long {
        val crc = java.util.zip.CRC32()
        val buffer = ByteArray(256 * 1024)
        file.inputStream().use { input ->
            var bytes: Int
            while (input.read(buffer).also { bytes = it } != -1) {
                if (bytes > 0) crc.update(buffer, 0, bytes)
            }
        }
        return crc.value
    }

    fun calculateSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        file.inputStream().use { input ->
            var bytes: Int
            while (input.read(buffer).also { bytes = it } != -1) {
                if (bytes > 0) digest.update(buffer, 0, bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Imports an existing copy from
     * Downloads/SIS_Models (e.g. pushed via USB or from an older
     * app version) via MediaStore stream into app storage — without a
     * single network byte. With progress + CRC mandatory check.
     * @return Target file on success, null otherwise (then download normally).
     */
    /**
     * Public entry point for USB import (spec HANDOFF/Roadmap):
     * adb push to /sdcard/Download/SIS_Models + in-app import to
     * private app storage with CRC mandatory check. Called by
     * LocalGemmaAssistant.ensureModel() BEFORE a network
     * download is started — no re-download for 1.87 GB.
     */
    suspend fun importFromPublicDownloads(
        context: Context,
        filename: String,
        expectedCrc32: Long
    ): File? = tryImportFromPublicDownloads(context, filename, expectedCrc32, null) { _, _ -> }


    private suspend fun tryImportFromPublicDownloads(
        context: Context,
        filename: String,
        expectedCrc32: Long?,
        expectedSha256: String?,
        onProgress: suspend (bytesCopied: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        try {
            fun isValid(file: File): Boolean = when {
                expectedSha256 != null -> calculateSha256(file).equals(expectedSha256, ignoreCase = true)
                expectedCrc32 != null -> calculateCrc32(file) == expectedCrc32
                else -> false
            }
            val sel = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(filename, "%${publicDownloadFolder(filename).removePrefix("Download/")}%")
            data class Candidate(val uri: android.net.Uri, val size: Long)
            val candidates = mutableListOf<Candidate>()
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
                sel, args, null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (c.moveToNext()) {
                    val size = c.getLong(sizeIdx)
                    if (size > 1024) {
                        candidates += Candidate(
                            android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idIdx)
                            ), size
                        )
                    }
                }
            }
            val targetDir = getModelsDir(context)
            for ((uri, size) in candidates) {
                val tmp = File(targetDir, "$filename.import")
                try {
                    val input = context.contentResolver.openInputStream(uri) ?: continue
                    input.use { `in` ->
                        FileOutputStream(tmp).use { out ->
                            val buffer = ByteArray(256 * 1024)
                            var copied = 0L
                            var n: Int
                            var lastEmit = System.currentTimeMillis()
                            while (`in`.read(buffer).also { n = it } != -1) {
                                if (n <= 0) continue
                                out.write(buffer, 0, n)
                                copied += n
                                if (System.currentTimeMillis() - lastEmit > 300) {
                                    onProgress(copied, size)
                                    lastEmit = System.currentTimeMillis()
                                }
                            }
                            out.flush()
                        }
                    }
                    onProgress(tmp.length(), size)
                    if (isValid(tmp)) {
                        val dest = File(targetDir, filename)
                        if (!tmp.renameTo(dest)) {
                            tmp.copyTo(dest, overwrite = true)
                            tmp.delete()
                        }
                        return@withContext dest
                    } else {
                        tmp.delete() // CRC fail -> Kandidat verwerfen, nächsten prüfen
                    }
                } catch (_: Exception) {
                    try { tmp.delete() } catch (_: Exception) {}
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Download with resume (HTTP Range) and CRC32 mandatory check.
     * expectedCrc32 must not be null: without checksum no
     * model is accepted as valid (fail-closed).
     */
    fun downloadModel(
        context: Context,
        urlString: String,
        targetFileName: String,
        expectedCrc32: Long? = null,
        expectedSha256: String? = null
    ): Flow<DownloadProgress> = flow {
        require(expectedCrc32 != null || expectedSha256 != null) { "A checksum is mandatory (fail-closed)" }
        fun isValid(file: File): Boolean = when {
            expectedSha256 != null -> calculateSha256(file).equals(expectedSha256, ignoreCase = true)
            expectedCrc32 != null -> calculateCrc32(file) == expectedCrc32
            else -> false
        }

        val targetDir = getModelsDir(context)
        val targetFile = File(targetDir, targetFileName)
        val tempFile = File(targetDir, "$targetFileName.download")

        // Already valid file -> report ready immediately (checksum verified)
        if (targetFile.exists() && !tempFile.exists()) {
            if (isValid(targetFile)) {
                publishToPublicDownloads(context, targetFileName)
                emit(DownloadProgress(targetFileName, targetFile.length(), targetFile.length(), true))
                return@flow
            } else {
                // Corrupt -> continue as resume base
                targetFile.renameTo(tempFile)
            }
        }

        // File already in Downloads (USB, old version, other app identity)?
        // Always import via MediaStore first instead of re-downloading —
        // a complete local file beats any resume. On success discard old
        // partial download.
        val imported = tryImportFromPublicDownloads(context, targetFileName, expectedCrc32, expectedSha256) { copied, total ->
            emit(DownloadProgress(targetFileName, copied, total, false))
        }
        if (imported != null) {
            try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
            emit(DownloadProgress(targetFileName, imported.length(), imported.length(), true))
            return@flow
        }

        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20000
            connection.readTimeout = 30000

            if (existingBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
            }

            connection.connect()
            val responseCode = connection.responseCode
            val isPartial = (responseCode == HttpURLConnection.HTTP_PARTIAL)
            val isOk = (responseCode == HttpURLConnection.HTTP_OK)

            if (!isPartial && !isOk) {
                emit(DownloadProgress(targetFileName, existingBytes, 0, false, "Server-Fehler: $responseCode"))
                return@flow
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (isPartial) existingBytes + contentLength else contentLength

            val append = isPartial && existingBytes > 0
            // If server doesn't support resume, start from beginning
            if (!append && tempFile.exists()) tempFile.delete()

            connection.inputStream.use { input ->
                FileOutputStream(tempFile, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var bytesCopied: Long = if (append) existingBytes else 0L
                    var bytes: Int
                    var lastEmitTime = System.currentTimeMillis()

                    while (input.read(buffer).also { bytes = it } != -1) {
                        if (bytes <= 0) continue
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime > 300) {
                            emit(DownloadProgress(targetFileName, bytesCopied, totalBytes, false))
                            lastEmitTime = now
                        }
                    }
                    output.flush()
                    // bytesCopied final melden
                    emit(DownloadProgress(targetFileName, bytesCopied, totalBytes, false))
                }
            }

            // Mandatory checksum check before final rename (fail-closed).
            if (!withContext(Dispatchers.IO) { isValid(tempFile) }) {
                tempFile.delete()
                emit(
                    DownloadProgress(
                        targetFileName, 0, 0, false,
                        "Checksum verification failed — file discarded"
                    )
                )
                return@flow
            }

            // Atomic rename
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                try {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                } catch (ex: Exception) {
                    emit(DownloadProgress(targetFileName, 0, 0, false, "Fehler beim Verschieben: ${ex.message}"))
                    return@flow
                }
            }

            // Visible copy in Downloads/SIS_Models (best effort)
            publishToPublicDownloads(context, targetFileName)

            emit(DownloadProgress(targetFileName, targetFile.length(), targetFile.length(), true))
        } catch (e: Exception) {
            val downloadedSoFar = if (tempFile.exists()) tempFile.length() else existingBytes
            emit(DownloadProgress(targetFileName, downloadedSoFar, 0, false, e.localizedMessage ?: "Connection interrupted (resume ready)"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Import via file picker (Storage Access Framework): the user selects the
     * model file themselves (e.g. pushed via USB, from old app identity —
     * not readable via MediaStore for the new app). With progress +
     * CRC mandatory check. No network.
     */
    fun importFromUri(
        context: Context,
        uri: android.net.Uri,
        targetFileName: String,
        expectedCrc32: Long? = null
    ): Flow<DownloadProgress> = flow {
        requireNotNull(expectedCrc32) { "CRC32 checksum is mandatory (fail-closed)" }

        val targetDir = getModelsDir(context)
        val targetFile = File(targetDir, targetFileName)
        val tempFile = File(targetDir, "$targetFileName.download")

        if (targetFile.exists() && calculateCrc32(targetFile) == expectedCrc32) {
            emit(DownloadProgress(targetFileName, targetFile.length(), targetFile.length(), true))
            return@flow
        }

        val totalBytes = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (_: Exception) {
            0L
        }
        val tmp = File(targetDir, "$targetFileName.import")
        try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                emit(DownloadProgress(targetFileName, 0, 0, false, "File cannot be opened"))
                return@flow
            }
            input.use { `in` ->
                FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var n: Int
                    var lastEmit = System.currentTimeMillis()
                    while (`in`.read(buffer).also { n = it } != -1) {
                        if (n <= 0) continue
                        out.write(buffer, 0, n)
                        copied += n
                        if (System.currentTimeMillis() - lastEmit > 300) {
                            emit(DownloadProgress(targetFileName, copied, totalBytes, false))
                            lastEmit = System.currentTimeMillis()
                        }
                    }
                    out.flush()
                }
            }
            val actualCrc = withContext(Dispatchers.IO) { calculateCrc32(tmp) }
            if (actualCrc != expectedCrc32) {
                tmp.delete()
                emit(
                    DownloadProgress(
                        targetFileName, 0, 0, false,
                        "CRC check failed: file does not match (expected 0x${expectedCrc32.toString(16)}, got 0x${actualCrc.toString(16)})"
                    )
                )
                return@flow
            }
            if (!tmp.renameTo(targetFile)) {
                tmp.copyTo(targetFile, overwrite = true)
                tmp.delete()
            }
            try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
            publishToPublicDownloads(context, targetFileName)
            emit(DownloadProgress(targetFileName, targetFile.length(), targetFile.length(), true))
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            emit(DownloadProgress(targetFileName, 0, 0, false, e.localizedMessage ?: "Import aborted"))
        }
    }.flowOn(Dispatchers.IO)
}
