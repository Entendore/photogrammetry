package com.example.photogrammetry

import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PlyExporter {

    /** points = flat [x0,y0,z0, ...] in world space. */
    fun writeAscii(file: File, points: FloatArray, pointCount: Int): Boolean = try {
        PrintWriter(BufferedWriter(FileWriter(file), 1 shl 16)).use { out ->
            out.println("ply")
            out.println("format ascii 1.0")
            out.println("element vertex $pointCount")
            out.println("property float x")
            out.println("property float y")
            out.println("property float z")
            out.println("end_header")
            var i = 0
            repeat(pointCount) {
                out.println("${points[i]} ${points[i + 1]} ${points[i + 2]}")
                i += 3
            }
        }
        true
    } catch (e: Exception) {
        false
    }

    /** points = flat xyz triplets; rgbs = packed 0xRRGGBB per point. */
    fun writeAsciiColored(file: File, points: FloatArray, rgbs: IntArray, pointCount: Int): Boolean =
        try {
            PrintWriter(BufferedWriter(FileWriter(file), 1 shl 16)).use { out ->
                out.println("ply")
                out.println("format ascii 1.0")
                out.println("element vertex $pointCount")
                out.println("property float x")
                out.println("property float y")
                out.println("property float z")
                out.println("property uchar red")
                out.println("property uchar green")
                out.println("property uchar blue")
                out.println("end_header")
                var i = 0
                repeat(pointCount) {
                    val c = rgbs[it]
                    out.println("${points[i]} ${points[i + 1]} ${points[i + 2]} " +
                            "${(c shr 16) and 0xFF} ${(c shr 8) and 0xFF} ${c and 0xFF}")
                    i += 3
                }
            }
            true
        } catch (e: Exception) {
            false
        }

    /** Binary little-endian PLY — ~5x smaller and much faster than ASCII. */
    fun writeBinary(file: File, points: FloatArray, rgbs: IntArray?, pointCount: Int): Boolean =
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).use { out ->
                out.write("""ply
format binary_little_endian 1.0
element vertex $pointCount
property float x
property float y
property float z
 ${if (rgbs != null) "property uchar red\nproperty uchar green\nproperty uchar blue" else ""}
end_header
""".toByteArray())
                val stride = if (rgbs != null) 15 else 12
                val buf = ByteBuffer.allocate(stride).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until pointCount) {
                    buf.rewind()
                    buf.putFloat(points[3 * i]).putFloat(points[3 * i + 1]).putFloat(points[3 * i + 2])
                    if (rgbs != null) {
                        val c = rgbs[i]
                        buf.put(((c shr 16) and 0xFF).toByte())
                        buf.put(((c shr 8) and 0xFF).toByte())
                        buf.put((c and 0xFF).toByte())
                    }
                    out.write(buf.array())
                }
            }
            true
        } catch (e: Exception) {
            false
        }
}
