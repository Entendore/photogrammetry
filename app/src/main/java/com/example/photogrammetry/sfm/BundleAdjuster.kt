package com.example.photogrammetry.sfm

object BundleAdjuster {
    /** False if the native lib is missing — pipeline then continues without BA. */
    val available: Boolean = try {
        System.loadLibrary("ba_jni"); true
    } catch (_: Throwable) { false }

    external fun solve(
        camIds: IntArray, ptIds: IntArray, uv: DoubleArray,
        cams: DoubleArray, pts: DoubleArray, K: DoubleArray,
        nObs: Int, nCams: Int, nPts: Int,
        outCams: DoubleArray, outPts: DoubleArray
    ): Boolean
}
