package com.example.photogrammetry

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScanInfo(
    val id: String,
    val dir: File,
    val photoCount: Int,
    val hasCloud: Boolean,
    val date: Long
)

object Scans {
    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null), "scans").apply { mkdirs() }

    fun create(context: Context): String {
        val id = fmt.format(Date())
        File(root(context), id).apply {
            mkdirs(); File(this, "photos").mkdirs(); File(this, "out").mkdirs()
        }
        return id
    }

    fun photosDir(context: Context, id: String) = File(File(root(context), id), "photos")
    fun outDir(context: Context, id: String) = File(File(root(context), id), "out")

    fun list(context: Context): List<ScanInfo> =
        root(context).listFiles { f -> f.isDirectory }
            ?.map { d ->
                ScanInfo(
                    id = d.name,
                    dir = d,
                    photoCount = File(d, "photos")
                        .listFiles { f -> f.extension.lowercase() in listOf("jpg", "jpeg") }?.size ?: 0,
                    hasCloud = File(d, "out/sfm_points.ply").exists(),
                    date = d.lastModified()
                )
            }?.sortedByDescending { it.date } ?: emptyList()
}
