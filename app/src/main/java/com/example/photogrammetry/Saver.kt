package com.example.photogrammetry

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Saver {
    /** Copies a file into the user-visible Downloads folder. */
    fun copyToDownloads(context: Context, src: File, mime: String): Uri? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                }) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { o ->
                src.inputStream().use { it.copyTo(o) }
            }
            uri
        } else {
            val dst = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), src.name)
            src.copyTo(dst, overwrite = true)
            Uri.fromFile(dst)
        }
    } catch (e: Exception) {
        null
    }
}
