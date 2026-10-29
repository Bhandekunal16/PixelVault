package com.example.data.local

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.example.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class StagedUploadFile(
    val path: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long
)

class PendingUploadFileStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIRECTORY_NAME)

    fun stage(uri: Uri, operationId: String): StagedUploadFile {
        ensureDirectory()
        val name = FileUtils.getFileName(appContext, uri)
            .trim()
            .takeIf(String::isNotBlank)
            ?: throw IOException("The selected image does not have a readable filename.")
        val mimeType = appContext.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: throw IOException("The selected file type could not be identified.")
        if (!mimeType.startsWith("image/", ignoreCase = true)) {
            throw IllegalArgumentException("Choose a supported image file.")
        }

        val temporaryFile = File(directory, "$operationId.part")
        val persistedFile = File(directory, "$operationId.upload")
        var persisted = false
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("The selected image is no longer accessible. Choose it again.")
            var size = 0L
            input.use { source ->
                FileOutputStream(temporaryFile).use { destination ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > FileUtils.MAX_IMAGE_BYTES) {
                            throw IllegalArgumentException(
                                "Selected image exceeds the 10 MiB upload limit."
                            )
                        }
                        destination.write(buffer, 0, read)
                    }
                    destination.fd.sync()
                }
            }
            if (size == 0L) throw IOException("The selected image is empty.")
            if (!temporaryFile.renameTo(persistedFile)) {
                throw IOException("Could not save the selected image for background upload.")
            }
            persisted = true
            return StagedUploadFile(
                path = persistedFile.canonicalPath,
                filename = name,
                mimeType = mimeType,
                sizeBytes = size
            )
        } finally {
            if (!persisted) {
                temporaryFile.delete()
                persistedFile.delete()
            }
        }
    }

    fun delete(path: String?) {
        if (path == null) return
        val file = safeFile(path) ?: return
        if (file.exists() && !file.delete()) {
            throw IOException("Could not remove the queued image from this device.")
        }
    }

    fun fileForRead(path: String): File =
        safeFile(path) ?: throw IOException("The queued image path is invalid.")

    fun isAvailable(path: String?): Boolean {
        if (path == null) return false
        return try {
            safeFile(path)?.isFile == true
        } catch (_: IOException) {
            false
        }
    }

    fun cleanupOrphans(referencedPaths: Set<String>) {
        ensureDirectory()
        val files = directory.listFiles()
            ?: throw IOException("Could not inspect pending upload storage.")
        files.forEach { file ->
            if (
                file.name.endsWith(".part") ||
                (file.name.endsWith(".upload") && file.canonicalPath !in referencedPaths)
            ) {
                if (!file.delete() && file.exists()) {
                    throw IOException("Could not clean up an unused queued image.")
                }
            }
        }
    }

    private fun safeFile(path: String): File? {
        val root = directory.canonicalFile
        val file = File(path).canonicalFile
        if (file.parentFile != root) {
            throw IOException("Queued image path is outside private upload storage.")
        }
        if (!file.name.endsWith(".upload") && !file.name.endsWith(".part")) {
            throw IOException("Queued image path is invalid.")
        }
        return file
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create private storage for queued images.")
        }
        if (!directory.isDirectory) {
            throw IOException("Private queued image storage is unavailable.")
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "pending_uploads"
        const val COPY_BUFFER_SIZE = 16 * 1024
    }
}
