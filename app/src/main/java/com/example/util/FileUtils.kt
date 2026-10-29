package com.example.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

object FileUtils {

    const val MAX_IMAGE_BYTES: Long = 10L * 1024L * 1024L // 10 MiB limit

    fun getFileName(context: Context, uri: Uri): String {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotBlank)
            ?: "image_${System.currentTimeMillis()}"
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size: Long? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1) {
                    size = it.getLong(index).takeIf { value -> value >= 0L }
                }
            }
        }
        return size ?: if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf(File::isFile)?.length() ?: 0L
        } else {
            0L
        }
    }

    fun uriToMultipartPart(
        context: Context,
        uri: Uri,
        partName: String = "image"
    ): MultipartBody.Part? {
        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.use { it.readBytes() }
            val filename = getFileName(context, uri)

            val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            MultipartBody.Part.createFormData(partName, filename, requestBody)
        } catch (e: Exception) {
            null
        }
    }

    fun fileToMultipartPart(file: File, filename: String, mimeType: String): MultipartBody.Part {
        if (!file.isFile || !file.canRead()) {
            throw IOException("The queued image is missing or cannot be read. Choose it again.")
        }
        val size = file.length()
        if (size <= 0L) throw IOException("The queued image is empty.")
        if (size > MAX_IMAGE_BYTES) {
            throw IllegalArgumentException("Selected image exceeds the 10 MiB upload limit.")
        }
        if (!mimeType.startsWith("image/", ignoreCase = true)) {
            throw IllegalArgumentException("The queued file is not a supported image.")
        }

        val bytes = file.inputStream().use { it.readBytes() }
        if (bytes.size.toLong() != size) {
            throw IOException("The queued image changed while it was being read.")
        }
        return MultipartBody.Part.createFormData(
            "image",
            filename,
            bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        )
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / 1024.0.pow(digitGroups.toDouble())
        return DecimalFormat("#,##0.#").format(value) + " " + units[digitGroups]
    }

    fun saveBytesToDownloads(
        context: Context,
        bytes: ByteArray,
        filename: String,
        mimeType: String
    ): Result<Uri> {
        return try {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ImageVault")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return Result.failure(Exception("Failed to create download record"))
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: return Result.failure(Exception("Failed to open output stream"))
                Result.success(uri)
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "ImageVault").apply { mkdirs() }
                val file = File(targetDir, filename)
                FileOutputStream(file).use { out ->
                    out.write(bytes)
                    out.flush()
                }
                Result.success(Uri.fromFile(file))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
