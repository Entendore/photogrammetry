package com.example.photogrammetry.sfm

import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import kotlin.math.max

object IntrinsicsEstimator {
    data class Cam(val fx: Double, val fy: Double, val cx: Double, val cy: Double)

    /** Priority: ARCore sidecar JSON > EXIF 35mm focal > heuristic. */
    fun forPhoto(photoDir: File, file: File, w: Int, h: Int): Cam {
        val sidecar = File(photoDir, file.nameWithoutExtension + ".json")
        if (sidecar.exists()) {
            try {
                val j = JSONObject(sidecar.readText())
                val s = w.toDouble() / j.getDouble("w")
                return Cam(j.getDouble("fx") * s, j.getDouble("fy") * s,
                           j.getDouble("cx") * s, j.getDouble("cy") * s)
            } catch (_: Exception) {}
        }
        try {
            val exif = ExifInterface(file.absolutePath)
            val f35 = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0)
            if (f35 > 0) {
                val focal = f35 / 36.0 * max(w, h)
                return Cam(focal, focal, w / 2.0, h / 2.0)
            }
        } catch (_: Exception) {}
        val focal = 1.2 * max(w, h)
        return Cam(focal, focal, w / 2.0, h / 2.0)
    }
}
