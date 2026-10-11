package com.example.photogrammetry.sfm

import android.media.Image
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageQuality {
    /** Laplacian variance on the quarter-scale luma plane. > ~45 = sharp enough. */
    fun sharpness(image: Image): Double? {
        val y = image.planes[0]; val w = image.width; val h = image.height
        val mat = Mat(h, w, CvType.CV_8UC1)
        return try {
            if (y.rowStride == w && y.pixelStride == 1) {
                val tmp = ByteArray(w * h)
                y.buffer.get(tmp)
                mat.put(0, 0, tmp)
            } else {
                val row = ByteArray(w)
                for (r in 0 until h) {
                    y.buffer.position(r * y.rowStride)
                    y.buffer.get(row, 0, w)
                    mat.put(r, 0, row)
                }
            }
            val small = Mat()
            Imgproc.resize(mat, small,
                Size((w / 4.0).coerceAtLeast(1.0), (h / 4.0).coerceAtLeast(1.0)))
            val lap = Mat()
            Imgproc.Laplacian(small, lap, CvType.CV_16S)
            val mean = MatOfDouble(); val std = MatOfDouble()
            Core.meanStdDev(lap, mean, std)
            val v = std.get(0, 0)[0] * std.get(0, 0)[0]
            small.release(); lap.release(); mean.release(); std.release()
            v
        } catch (_: Throwable) {
            null
        } finally {
            mat.release()
        }
    }
}
