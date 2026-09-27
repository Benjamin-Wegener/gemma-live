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

    // Dateinamen (Single Source of Truth, auch vom Onboarding benutzt)
    const val GEMMA_FILE = "gemma-4-E2B-it-gpu.litertlm"
    const val VOICE_MODEL_FILE = "de_DE-kerstin-low.onnx"
    const val VOICE_CONFIG_FILE = "de_DE-kerstin-low.onnx.json"
    const val VOICE_TOKENS_FILE = "tokens.txt"
    const val ESPEAK_DATA_DIR = "espeak-ng-data"
    private const val ESPEAK_SENTINEL = "espeak-ng-data/phontab"

    const val GEMMA_4_GPU_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm"

    // Verifizierte CRC32-Prüfsummen — Pflichtprüfung nach jedem Download / jeder Bundle-Kopie
    const val VOICE_CONFIG_CRC: Long = 0x8aba0c34L
    const val VOICE_MODEL_CRC: Long = 0x4071ce25L // rhasspy kerstin-low + sherpa-Metadaten
    const val VOICE_TOKENS_CRC: Long = 0xbd891de3L // aus kerstin phoneme_id_map (130 Symbole)
    const val GEMMA_4_GPU_CRC: Long = 0x0f55d747L

    /**
     * Primärspeicher (Scoped Storage): app-privates ExternalFilesDir/models.
     * - Kein Permission-Request nötig, auf allen API-Levels schreibbar
     * - Direkter File-Pfad für LiteRT-Inferenz (mmap / Random Access)
     * MediaStore-URIs allein reichen für die Inference-Engine nicht.
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
     * Installationscheck: erst privat (inferenzrelevant), dann öffentlich
     * (MediaStore Downloads/SIS_Models, für den Nutzer sichtbar).
     * Zusätzlich Migration aus dem alten Legacy-Pfad.
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
     * Für die Inferenz zählt nur der private App-Speicher (direkter Datei-
     * Zugriff). Öffentliche Kopien in Downloads sind nur Sichtbarkeit.
     * Gating (Onboarding-Weiter) nutzt ausschließlich diese Prüfung.
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
     * Sisa-Stimme (Kerstin, weiblich) liegt in der APK (assets/models) — NUR Gemma wird gesaugt.
     * Kopiert ONNX + JSON beim ersten Start in den App-Speicher und prüft CRC.
     * Auf IO-Thread aufrufen (63 MB Kopie). Gibt true zurück, wenn beide
     * Dateien CRC-geprüft vorliegen.
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

    /** Kopiert ein Asset-Verzeichnis (espeak-ng-data, 355 Dateien) beim 1. Start. */
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

    /** Prüft per MediaStore, ob eine sichtbare Kopie in Downloads/SIS_Models liegt. */
    fun isPublicCopyPresent(context: Context, filename: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val legacy = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SIS_Models/$filename")
            return legacy.exists() && legacy.length() > 1024
        }
        return try {
            val projection = arrayOf(MediaStore.Downloads.SIZE)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(filename, "%SIS_Models%")
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
     * Veröffentlicht eine Kopie der verifizierten Datei in Downloads/SIS_Models
     * über MediaStore (Scoped Storage, API 29+). Best effort: Fehler sind
     * nicht fatal, da die private Datei für die Inference ausreicht.
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
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/SIS_Models")
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

    /** Einmalige Migration aus dem alten Legacy-Pfad in den Scoped-Storage-Speicher. */
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

    /**
     * Übernimmt eine bereits auf dem Gerät liegende Kopie aus
     * Downloads/SIS_Models (z.B. per USB eingespielt oder von einer älteren
     * App-Version) per MediaStore-Stream in den App-Speicher — ohne einen
     * einzigen Netzwerk-Byte. Mit Fortschritt + CRC-Pflichtprüfung.
     * @return Zieldatei bei Erfolg, sonst null (dann normal downloaden).
     */
    /**
     * Oeffentlicher Einstieg fuer den USB-Import (Vorgabe HANDOFF/Roadmap):
     * adb push nach /sdcard/Download/SIS_Models + In-App-Import in den
     * privaten App-Speicher mit CRC-Pflichtpruefung. Wird von
     * LocalGemmaAssistant.ensureModel() aufgerufen, BEVOR ein Netzwerk-
     * Download angefasst wird — kein Re-Download bei 1,87 GB.
     */
    suspend fun importFromPublicDownloads(
        context: Context,
        filename: String,
        expectedCrc32: Long
    ): File? = tryImportFromPublicDownloads(context, filename, expectedCrc32) { _, _ -> }


    private suspend fun tryImportFromPublicDownloads(
        context: Context,
        filename: String,
        expectedCrc32: Long,
        onProgress: suspend (bytesCopied: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        try {
            val sel = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(filename, "%SIS_Models%")
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
                    if (calculateCrc32(tmp) == expectedCrc32) {
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
     * Download mit Resume (HTTP Range) und CRC32-PFLICHTPRÜFUNG.
     * expectedCrc32 darf nicht null sein: ohne Prüfsumme wird kein
     * Modell als gültig akzeptiert (fail-closed).
     */
    fun downloadModel(
        context: Context,
        urlString: String,
        targetFileName: String,
        expectedCrc32: Long? = null
    ): Flow<DownloadProgress> = flow {
        requireNotNull(expectedCrc32) { "CRC32-Prüfsumme ist Pflicht (fail-closed)" }

        val targetDir = getModelsDir(context)
        val targetFile = File(targetDir, targetFileName)
        val tempFile = File(targetDir, "$targetFileName.download")

        // Bereits gültige Datei -> sofort fertig melden (CRC verifiziert)
        if (targetFile.exists() && !tempFile.exists()) {
            if (calculateCrc32(targetFile) == expectedCrc32) {
                publishToPublicDownloads(context, targetFileName)
                emit(DownloadProgress(targetFileName, targetFile.length(), targetFile.length(), true))
                return@flow
            } else {
                // Korrupt -> als Resume-Basis weiterführen
                targetFile.renameTo(tempFile)
            }
        }

        // Datei liegt schon in Downloads (USB, alte Version, andere App-Identität)?
        // Immer zuerst per MediaStore-Import übernehmen statt neu zu laden —
        // eine fertige lokale Datei schlägt jedes Resume. Bei Erfolg alten
        // Teil-Download verwerfen.
        val imported = tryImportFromPublicDownloads(context, targetFileName, expectedCrc32) { copied, total ->
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
            // Falls Server kein Resume unterstützt, von vorn beginnen
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

            // CRC32-PFLICHTPRÜFUNG vor dem finalen Umbenennen (fail-closed)
            val actualCrc = withContext(Dispatchers.IO) { calculateCrc32(tempFile) }
            if (actualCrc != expectedCrc32) {
                tempFile.delete()
                emit(
                    DownloadProgress(
                        targetFileName, 0, 0, false,
                        "CRC-Prüfung fehlgeschlagen: erwartet 0x${expectedCrc32.toString(16)}, erhalten 0x${actualCrc.toString(16)} — Datei verworfen"
                    )
                )
                return@flow
            }

            // Atomar umbenennen
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

            // Sichtbare Kopie in Downloads/SIS_Models (best effort)
            publishToPublicDownloads(context, targetFileName)

            emit(DownloadProgress(targetFileName, targetFile.length(), targetFile.length(), true))
        } catch (e: Exception) {
            val downloadedSoFar = if (tempFile.exists()) tempFile.length() else existingBytes
            emit(DownloadProgress(targetFileName, downloadedSoFar, 0, false, e.localizedMessage ?: "Verbindung unterbrochen (Resume bereit)"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Import per Dateiauswahl (Storage Access Framework): Der Nutzer wählt die
     * Modelldatei selbst (z.B. per USB eingespielt, von alter App-Identität —
     * für die neue App per MediaStore nicht lesbar). Mit Fortschritt +
     * CRC-Pflichtprüfung. Kein Netzwerk.
     */
    fun importFromUri(
        context: Context,
        uri: android.net.Uri,
        targetFileName: String,
        expectedCrc32: Long? = null
    ): Flow<DownloadProgress> = flow {
        requireNotNull(expectedCrc32) { "CRC32-Prüfsumme ist Pflicht (fail-closed)" }

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
                emit(DownloadProgress(targetFileName, 0, 0, false, "Datei kann nicht geöffnet werden"))
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
                        "CRC-Prüfung fehlgeschlagen: Datei passt nicht (erwartet 0x${expectedCrc32.toString(16)}, erhalten 0x${actualCrc.toString(16)})"
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
            emit(DownloadProgress(targetFileName, 0, 0, false, e.localizedMessage ?: "Import abgebrochen"))
        }
    }.flowOn(Dispatchers.IO)
}
