package com.example.photogrammetry

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object Share {
    fun file(context: Context, f: File, mime: String = "application/octet-stream") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share ${f.name}"))
    }
}
