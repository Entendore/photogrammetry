package com.example.photogrammetry.sfm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// ---------- data types ----------

class Pose(val r: DoubleArray, val t: DoubleArray) {          // world -> camera
    fun apply(x: Double, y: Double, z: Double, out: DoubleArray) {
        out[0] = r[0]*x + r[1]*y + r[2]*z + t[0]
        out[1] = r[3]*x + r[4]*y + r[5]*z + t[1]
        out[2] = r[6]*x + r[7]*y + r[8]*z + t[2]
    }
    fun center() = doubleArrayOf(
        -(r[0]*t[0] + r[3]*t[1] + r[6]*t[2]),
        -(r[1]*t[0] + r[4]*t[1] + r[7]*t[2]),
        -(r[2]*t[0] + r[5]*t[1] + r[8]*t[2]))
    companion object {
        fun identity() = Pose(doubleArrayOf(1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0),
                              doubleArrayOf(0.0,0.0,0.0))
        fun fromMat(R: Mat, t: Mat): Pose {
            val rd = DoubleArray(9); R.get(0, 0, rd)
            val td = DoubleArray(3); t.get(0, 0, td)
            return Pose(rd, td)
        }
    }
}

class Track(val p: DoubleArray, var rgb: Int = 0xFF808080.toInt(), var valid: Boolean = true)

class Img(val file: File, val w: Int, val h: Int,
          val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    var kps: MatOfKeyPoint? = null
    var desc: Mat? = null
    var pose: Pose? = null
    private var kMat: Mat? = null
    fun k(): Mat = kMat ?: Mat(3, 3, CvType.CV_64FC1).apply {
        put(0, 0, doubleArrayOf(fx, 0.0, cx))
        put(1, 0, doubleArrayOf(0.0, fy, cy))
        put(2, 0, doubleArrayOf(0.0, 0.0, 1.0))
        kMat = this
    }
}

private class Corr(val featIdx: Int, val trackIdx: Int, val x: Double, val y: Double)

/** Single-bitmap color cache: one downscaled image at a time. */
private class ColorSampler(private val imgs: List<Img>) {
    private var loadedIdx = -1
    private var bmp: Bitmap? = null
    fun bitmap(i: Int): Bitmap? {
        if (loadedIdx == i) return bmp
        bmp?.recycle(); bmp = null; loadedIdx = -1
        val f = imgs[i].file
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        var s = 1
        while (max(b.outWidth, b.outHeight) / (s * 2) >= 512) s *= 2
        bmp = BitmapFactory.decodeFile(f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = s })
        if (bmp != null) loadedIdx = i
        return bmp
    }
    fun release() { bmp?.recycle(); bmp = null; loadedIdx = -1 }
}

// ---------- pipeline ----------

class SfmPipeline(
    private val photoDir: File,
    private val files: List<File>,
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (String) -> Unit
) {
    companion object {
        const val MAX_SIDE = 1280
        const val ORB_FEATURES = 4000
        const val MIN_INIT_MATCHES = 120
        const val MIN_REGISTER_CORR = 25
        const val MAX_TRACKS = 400_000
        const val BA_EVERY = 8
        const val BA_MAX_POINTS = 80_000
        const val CULL_PX2 = 16.0
    }

    val imgs = ArrayList<Img>()
    private val pairMatches = HashMap<Long, ArrayList<DMatch>>()
    val tracks = ArrayList<Track>()
    private val obs = ArrayList<HashMap<Int, Int>>()
    private val failed = HashSet<Int>()
    private val sampler = ColorSampler(imgs)

    suspend fun run(): Boolean = coroutineScope {
        OpenCVLoader.initLocal()
        if (!loadFeaturesParallel()) return@coroutineScope false
        matchAllParallel()
        val ok = initialize()
        if (ok) {
            grow()
            sampler.release()
        }
        ok && imgs.count { it.pose != null } >= 3 && tracks.count { it.valid } > 50
    }

    // ---- 1. features (parallel) ----

    private suspend fun loadFeaturesParallel(): Boolean = coroutineScope {
        val results = files.mapIndexed { idx, f ->
            async(Dispatchers.Default) {
                if (isCancelled()) return@async null
                onProgress("Features ${idx + 1}/${files.size}: ${f.name}")
                extractOne(f)
            }
        }.awaitAll()
        if (results.any { it == null }) return@coroutineScope false
        for (r in results) {
            imgs.add(r!!.first); obs.add(r.second)
        }
        return@coroutineScope imgs.size >= 3
    }

    private fun extractOne(f: File): Pair<Img, HashMap<Int, Int>>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeFile(f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null

        val rot = exifRotation(f)
        if (rot != 0f) {
            val rb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(rot) }, true)
            if (rb != bmp) bmp.recycle()
            bmp = rb
        }

        val rgba = Mat(); Utils.bitmapToMat(bmp, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val kp = MatOfKeyPoint(); val desc = Mat()
        ORB.create(ORB_FEATURES).detectAndCompute(gray, Mat(), kp, desc)  // ORB not thread-safe shared: create per call
        val intr = IntrinsicsEstimator.forPhoto(photoDir, f, bmp.width, bmp.height)
        val img = Img(f, bmp.width, bmp.height, intr.fx, intr.fy, intr.cx, intr.cy)
            .apply { kps = kp; this.desc = desc }
        gray.release(); rgba.release(); bmp.recycle()
        return img to HashMap()
    }

    private fun exifRotation(f: File): Float = try {
        when (ExifInterface(f.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) { 0f }

    // ---- 2. pairwise matching (parallel) ----

    private fun key(i: Int, j: Int) = i.toLong() * 100_000L + j

    private suspend fun matchAllParallel() = coroutineScope {
        val pairs = ArrayList<Pair<Int, Int>>()
        for (i in imgs.indices) for (j in i + 1 until imgs.size) pairs += i to j
        val total = pairs.size; val done = AtomicInteger()
        pairs.map { (i, j) ->
            async(Dispatchers.Default) {
                if (isCancelled()) return@async
                val di = imgs[i].desc ?: return@async
                val dj = imgs[j].desc ?: return@async
                val knn = BFMatcher(Core.NORM_HAMMING, false).knnMatch(di, dj, 2)
                val good = ArrayList<DMatch>(knn.size)
                for (km in knn) {
                    val a = km.toArray(); km.release()
                    if (a.size == 2 && a[0].distance < 0.75f * a[1].distance) good += a[0]
                }
                if (good.size >= 20) synchronized(pairMatches) { pairMatches[key(i, j)] = good }
                if (done.incrementAndGet() % 20 == 0) onProgress("Matching $done/$total pairs")
            }
        }.awaitAll()
    }

    private fun matchBetween(i: Int, j: Int): List<DMatch> =
        if (i < j) pairMatches[key(i, j)] ?: emptyList()
        else (pairMatches[key(j, i)] ?: emptyList()).map {
            DMatch(it.trainIdx, it.queryIdx, it.imgIdx, it.distance)
        }

    // ---- 3. initialization ----

    private fun initialize(): Boolean {
        onProgress("Selecting initial pair…")
        var bestI = -1; var bestJ = -1; var bestScore = 0.0
        for ((k, ms) in pairMatches) {
            if (ms.size < MIN_INIT_MATCHES) continue
            val i = (k / 100_000L).toInt(); val j = (k % 100_000L).toInt()
            val p1 = points2f(imgs[i].kps!!, ms) { it.queryIdx }
            val p2 = points2f(imgs[j].kps!!, ms) { it.trainIdx }
            val maskH = Mat()
            Calib3d.findHomography(p1, p2, Calib3d.RANSAC, 3.0, maskH)
            val hRatio = Core.countNonZero(maskH).toDouble() / ms.size
            maskH.release(); p1.release(); p2.release()
            if (hRatio > 0.8) continue          // planar scene or pure rotation — bad init
            val score = ms.size * (1.0 - hRatio)
            if (score > bestScore) { bestScore = score; bestI = i; bestJ = j }
        }
        if (bestI < 0) { onProgress("Init failed: no suitable pair"); return false }

        val ms = pairMatches[key(bestI, bestJ)]!!
        val p1 = points2f(imgs[bestI].kps!!, ms) { it.queryIdx }
        val p2 = points2f(imgs[bestJ].kps!!, ms) { it.trainIdx }
        val K = imgs[bestI].k()
        val maskE = Mat()
        val E = Calib3d.findEssentialMat(p1, p2, K, Calib3d.FM_RANSAC, 0.999, 1.0, maskE)
        val R = Mat(); val t = Mat(); val maskP = Mat()
        val passed = Calib3d.recoverPose(E, p1, p2, K, R, t, maskP)
        E.release(); maskE.release(); maskP.release(); p1.release(); p2.release()
        if (passed < MIN_INIT_MATCHES) { onProgress("Init failed: pose recovery"); return false }

        val td = DoubleArray(3); t.get(0, 0, td)
        val nrm = sqrt(td[0]*td[0] + td[1]*td[1] + td[2]*td[2])
        for (a in td.indices) td[a] /= nrm
        t.put(0, 0, td)

        imgs[bestI].pose = Pose.identity()
        imgs[bestJ].pose = Pose.fromMat(R, t)
        R.release(); t.release()

        val created = triangulateBatch(bestI, bestJ, ms, maxReproj = 1.5)
        onProgress("Initialized pair ($bestI,$bestJ): $created tracks")
        return tracks.isNotEmpty()
    }

    // ---- 4. incremental growth (+ periodic BA) ----

    private fun grow() {
        var guard = 0; var sinceBa = 0
        while (guard++ < imgs.size * 2 && tracks.size < MAX_TRACKS) {
            if (isCancelled()) return
            var bestIdx = -1; var bestCorr: List<Corr> = emptyList()
            for (i in imgs.indices) {
                if (imgs[i].pose != null || i in failed) continue
                val corr = collectCorrespondences(i)
                if (corr.size > bestCorr.size) { bestCorr = corr; bestIdx = i }
            }
            if (bestIdx < 0 || bestCorr.size < MIN_REGISTER_CORR) break
            if (register(bestIdx, bestCorr)) {
                failed.clear()
                if (++sinceBa >= BA_EVERY) { bundleAdjust(); sinceBa = 0 }
            } else failed.add(bestIdx)
        }
        bundleAdjust()   // final global pass
    }

    private fun collectCorrespondences(i: Int): List<Corr> {
        val kpI = imgs[i].kps!!.toArray()
        val out = ArrayList<Corr>()
        for (j in imgs.indices) {
            if (j == i || imgs[j].pose == null) continue
            for (d in matchBetween(i, j)) {
                if (obs[i].containsKey(d.queryIdx)) continue
                val ti = obs[j][d.trainIdx]?.takeIf { tracks[it].valid } ?: continue
                val p = kpI[d.queryIdx].pt
                out.add(Corr(d.queryIdx, ti, p.x, p.y))
            }
        }
        return out
    }

    private fun register(i: Int, corr: List<Corr>): Boolean {
        val img = imgs[i]
        onProgress("Registering image $i (${corr.size} correspondences)…")
        val obj = MatOfPoint3f(*corr.map {
            Point3(tracks[it.trackIdx].p[0], tracks[it.trackIdx].p[1], tracks[it.trackIdx].p[2])
        }.toTypedArray())
        val imgPts = MatOfPoint2f(*corr.map { Point(it.x, it.y) }.toTypedArray())
        val dist = MatOfDouble(0.0, 0.0, 0.0, 0.0)
        val rvec = Mat(); val tvec = Mat(); val inl = org.opencv.core.MatOfInt()

        val ok = Calib3d.solvePnPRansac(obj, imgPts, img.k(), dist, rvec, tvec,
            false, 2000, 3.0, 0.99, inl, Calib3d.SOLVEPNP_EPNP)
        val inliers = if (ok) inl.toArray() else IntArray(0)
        obj.release(); imgPts.release(); inl.release()
        if (inliers.size < 15) { rvec.release(); tvec.release(); return false }

        val objIn = MatOfPoint3f(*inliers.map {
            Point3(tracks[corr[it].trackIdx].p[0], tracks[corr[it].trackIdx].p[1],
                   tracks[corr[it].trackIdx].p[2])
        }.toTypedArray())
        val imgIn = MatOfPoint2f(*inliers.map { Point(corr[it].x, corr[it].y) }.toTypedArray())
        Calib3d.solvePnP(objIn, imgIn, img.k(), dist, rvec, tvec, true, Calib3d.SOLVEPNP_ITERATIVE)
        objIn.release(); imgIn.release(); dist.release()

        val R = Mat(); Calib3d.Rodrigues(rvec, R)
        img.pose = Pose.fromMat(R, tvec)
        R.release(); rvec.release(); tvec.release()

        val pose = img.pose!!
        for (c in corr) {
            val e = reprojErr(img, pose, tracks[c.trackIdx].p, c.x, c.y)
            if (e < 3.0 && !obs[i].containsKey(c.featIdx)) obs[i][c.featIdx] = c.trackIdx
        }

        var added = 0
        for (j in imgs.indices) {
            if (j == i || imgs[j].pose == null) continue
            added += triangulateBatch(i, j, matchBetween(i, j), maxReproj = 2.0)
        }
        onProgress("Registered $i/${imgs.size}: +$added points, total ${tracks.size}")
        return true
    }

    private fun reprojErr(img: Img, pose: Pose, X: DoubleArray, u: Double, v: Double): Double {
        val pr = project(img, X) ?: return Double.MAX_VALUE
        return sqrt(d2(pr, u, v))
    }

    // ---- 5. triangulation ----

    private fun triangulateBatch(i: Int, j: Int, ms: List<DMatch>, maxReproj: Double): Int {
        val A = imgs[i]; val B = imgs[j]
        val sel = ms.filter { !obs[i].containsKey(it.queryIdx) && !obs[j].containsKey(it.trainIdx) }
        if (sel.isEmpty()) return 0
        val kpA = A.kps!!.toArray(); val kpB = B.kps!!.toArray()
        val n1 = normalized(A, sel.map { kpA[it.queryIdx].pt })
        val n2 = normalized(B, sel.map { kpB[it.trainIdx].pt })
        val P1 = projMat(A); val P2 = projMat(B)
        val X = Mat()
        Calib3d.triangulatePoints(P1, P2, n1, n2, X)
        P1.release(); P2.release(); n1.release(); n2.release()

        val bmp = sampler.bitmap(i)
        val col = DoubleArray(4); val cam = DoubleArray(3)
        var created = 0
        sel.forEachIndexed { c, d ->
            X.col(c).get(0, 0, col)
            val w = col[3]
            if (abs(w) < 1e-12) return@forEachIndexed
            val x3 = doubleArrayOf(col[0] / w, col[1] / w, col[2] / w)

            A.pose!!.apply(x3[0], x3[1], x3[2], cam); val zA = cam[2]
            B.pose!!.apply(x3[0], x3[1], x3[2], cam); val zB = cam[2]
            if (zA <= 0 || zB <= 0) return@forEachIndexed

            val pa = project(A, x3) ?: return@forEachIndexed
            val pb = project(B, x3) ?: return@forEachIndexed
            val q = kpA[d.queryIdx].pt; val tr = kpB[d.trainIdx].pt
            val e2 = maxReproj * maxReproj
            if (d2(pa, q.x, q.y) > e2 || d2(pb, tr.x, tr.y) > e2) return@forEachIndexed

            val tIdx = tracks.size
            tracks.add(Track(x3, sampleColor(bmp, A, q.x, q.y)))
            obs[i][d.queryIdx] = tIdx
            obs[j][d.trainIdx] = tIdx
            created++
        }
        X.release()
        return created
    }

    // ---- 6. bundle adjustment + retriangulation + outlier culling ----

    private fun rvecOf(r: DoubleArray): DoubleArray {
        val R = Mat(3, 3, CvType.CV_64FC1).apply { put(0, 0, r) }
        val rv = Mat(); Calib3d.Rodrigues(R, rv); R.release()
        val out = DoubleArray(3); rv.get(0, 0, out); rv.release(); return out
    }
    private fun rotOf(rv: DoubleArray): DoubleArray {
        val rvM = Mat(3, 1, CvType.CV_64FC1).apply { put(0, 0, rv) }
        val R = Mat(); Calib3d.Rodrigues(rvM, R); rvM.release()
        val out = DoubleArray(9); R.get(0, 0, out); R.release(); return out
    }
    private fun normPt(im: Img, u: Double, v: Double): Mat =
        Mat(1, 1, CvType.CV_64FC2).apply {
            put(0, 0, doubleArrayOf((u - im.cx) / im.fx, (v - im.cy) / im.fy))
        }

    private fun bundleAdjust(): Int {
        if (!BundleAdjuster.available) {
            onProgress("BA skipped (native lib unavailable)"); return 0
        }
        val reg = imgs.withIndex().filter { it.value.pose != null }
        if (reg.size < 3) return 0

        val hits = HashMap<Int, ArrayList<Triple<Int, Double, Double>>>()
        for ((i, im) in reg) {
            val kp = im.kps!!.toArray()
            for ((feat, trk) in obs[i]) {
                if (!tracks[trk].valid) continue
                val p = kp[feat].pt
                hits.getOrPut(trk) { ArrayList() }.add(Triple(i, p.x, p.y))
            }
        }
        val usable = hits.filter { it.value.size >= 2 }.entries.toList()
        if (usable.size < 100) return 0
        val stride = max(1, usable.size / BA_MAX_POINTS)
        val selected = usable.filterIndexed { idx, _ -> idx % stride == 0 }

        val localOfTrack = HashMap<Int, Int>()
        val pts = DoubleArray(selected.size * 3)
        selected.forEachIndexed { li, (tid, _) ->
            localOfTrack[tid] = li
            val p = tracks[tid].p
            pts[3*li] = p[0]; pts[3*li+1] = p[1]; pts[3*li+2] = p[2]
        }
        val camLocal = HashMap<Int, Int>()
        val cams = DoubleArray(reg.size * 6); val K = DoubleArray(reg.size * 4)
        reg.forEachIndexed { ci, (i, im) ->
            camLocal[i] = ci
            val rv = rvecOf(im.pose!!.r)
            System.arraycopy(rv, 0, cams, ci*6, 3)
            System.arraycopy(im.pose!!.t, 0, cams, ci*6+3, 3)
            K[ci*4] = im.fx; K[ci*4+1] = im.fy; K[ci*4+2] = im.cx; K[ci*4+3] = im.cy
        }
        val obsList = ArrayList<Triple<Int, Int, Pair<Double, Double>>>()
        for ((tid, list) in selected) for ((i, u, v) in list)
            obsList.add(Triple(camLocal[i]!!, localOfTrack[tid]!!, u to v))

        val camIds = IntArray(obsList.size); val ptIds = IntArray(obsList.size)
        val uv = DoubleArray(obsList.size * 2)
        obsList.forEachIndexed { k, (c, p, xy) ->
            camIds[k] = c; ptIds[k] = p; uv[2*k] = xy.first; uv[2*k+1] = xy.second }

        val outCams = DoubleArray(cams.size); val outPts = DoubleArray(pts.size)
        val okBa = BundleAdjuster.solve(camIds, ptIds, uv, cams, pts, K,
            obsList.size, reg.size, selected.size, outCams, outPts)
        if (!okBa) return 0

        reg.forEachIndexed { ci, (i, im) ->
            val R = rotOf(outCams.copyOfRange(ci*6, ci*6+3))
            val t = outCams.copyOfRange(ci*6+3, ci*6+6)
            im.pose = Pose(R, t)
        }
        for ((tid, _) in selected) {
            val li = localOfTrack[tid]!!
            tracks[tid].p[0] = outPts[3*li]; tracks[tid].p[1] = outPts[3*li+1]
            tracks[tid].p[2] = outPts[3*li+2]
        }

        // Re-triangulate each selected point from its two most-parallax views
        var retri = 0
        for ((tid, list) in selected) {
            if (list.size < 2) continue
            val spread = intArrayOf(0, list.size/3, 2*list.size/3, list.size-1).distinct()
            var best = -1.0; var bi = 0; var bj = 1
            for (a in spread.indices) for (b in a+1 until spread.size) {
                val h1 = list[spread[a]]; val h2 = list[spread[b]]
                val A = imgs[h1.first]; val B = imgs[h2.first]
                val c1 = A.pose!!.center(); val c2 = B.pose!!.center()
                val x = tracks[tid].p
                val d1 = doubleArrayOf(x[0]-c1[0], x[1]-c1[1], x[2]-c1[2])
                val d2v = doubleArrayOf(x[0]-c2[0], x[1]-c2[1], x[2]-c2[2])
                val dot = d1[0]*d2v[0]+d1[1]*d2v[1]+d1[2]*d2v[2]
                val cosang = dot / (sqrt(d1[0]*d1[0]+d1[1]*d1[1]+d1[2]*d1[2]) *
                        sqrt(d2v[0]*d2v[0]+d2v[1]*d2v[1]+d2v[2]*d2v[2]) + 1e-12)
                if (cosang < best) { best = cosang; bi = spread[a]; bj = spread[b] }
            }
            val h1 = list[bi]; val h2 = list[bj]
            val A = imgs[h1.first]; val B = imgs[h2.first]
            val P1 = projMat(A); val P2 = projMat(B)
            val n1 = normPt(A, h1.second, h1.third); val n2 = normPt(B, h2.second, h2.third)
            val X = Mat()
            Calib3d.triangulatePoints(P1, P2, n1, n2, X)
            P1.release(); P2.release(); n1.release(); n2.release()
            val col = DoubleArray(4); X.col(0).get(0, 0, col); X.release()
            if (abs(col[3]) < 1e-12) continue
            val xn = doubleArrayOf(col[0]/col[3], col[1]/col[3], col[2]/col[3])

            fun maxErr(x: DoubleArray): Double {
                var m = 0.0
                for ((i, u, v) in list) {
                    val cam = DoubleArray(3)
                    imgs[i].pose!!.apply(x[0], x[1], x[2], cam)
                    if (cam[2] <= 1e-6) return Double.MAX_VALUE
                    val pr = project(imgs[i], x) ?: return Double.MAX_VALUE
                    m = maxOf(m, sqrt(d2(pr, u, v)))
                }
                return m
            }
            if (maxErr(xn) < maxErr(tracks[tid].p)) {
                tracks[tid].p[0] = xn[0]; tracks[tid].p[1] = xn[1]; tracks[tid].p[2] = xn[2]
                retri++
            }
        }

        // Cull outlier observations, starve dead tracks
        var culled = 0
        for ((i, im) in reg) {
            val kp = im.kps!!.toArray()
            val it = obs[i].entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val p = kp[e.key].pt
                val pr = project(im, tracks[e.value].p)
                if (pr == null || d2(pr, p.x, p.y) > CULL_PX2) { it.remove(); culled++ }
            }
        }
        val counts = HashMap<Int, Int>()
        for (m in obs) for (t in m.values) if (tracks[t].valid) counts.merge(t, 1, Int::plus)
        for ((t, c) in counts) if (c < 2) tracks[t].valid = false

        onProgress("BA: ${selected.size} pts, retriangulated $retri, culled $culled obs")
        return selected.size
    }

    // ---- geometry helpers ----

    private fun points2f(kp: MatOfKeyPoint, ms: List<DMatch>, sel: (DMatch) -> Int): MatOfPoint2f {
        val all = kp.toArray()
        return MatOfPoint2f(*ms.map { all[sel(it)].pt }.toTypedArray())
    }

    private fun normalized(img: Img, pts: List<Point>): Mat {
        val m = Mat(1, pts.size, CvType.CV_64FC2)
        val buf = DoubleArray(2)
        pts.forEachIndexed { c, p ->
            buf[0] = (p.x - img.cx) / img.fx
            buf[1] = (p.y - img.cy) / img.fy
            m.put(0, c, buf)
        }
        return m
    }

    private fun projMat(img: Img): Mat {
        val r = img.pose!!.r; val t = img.pose!!.t
        return Mat(3, 4, CvType.CV_64FC1).apply {
            put(0, 0, doubleArrayOf(r[0], r[1], r[2], t[0]))
            put(1, 0, doubleArrayOf(r[3], r[4], r[5], t[1]))
            put(2, 0, doubleArrayOf(r[6], r[7], r[8], t[2]))
        }
    }

    private fun project(img: Img, X: DoubleArray): DoubleArray? {
        val xc = DoubleArray(3)
        img.pose!!.apply(X[0], X[1], X[2], xc)
        if (xc[2] <= 1e-6) return null
        return doubleArrayOf(img.fx * xc[0] / xc[2] + img.cx, img.fy * xc[1] / xc[2] + img.cy)
    }

    private fun d2(p: DoubleArray, x: Double, y: Double) = (p[0]-x)*(p[0]-x) + (p[1]-y)*(p[1]-y)

    private fun sampleColor(bmp: Bitmap?, img: Img, u: Double, v: Double): Int {
        if (bmp == null) return 0xFF808080.toInt()
        val x = (u * bmp.width / img.w).toInt().coerceIn(0, bmp.width - 1)
        val y = (v * bmp.height / img.h).toInt().coerceIn(0, bmp.height - 1)
        return bmp.getPixel(x, y) and 0xFFFFFF
    }

    // ---- 7. exports ----

    fun export(outDir: File): Pair<File, File> {
        outDir.mkdirs()
        val good = tracks.filter { it.valid }
        val n = good.size
        val xyz = FloatArray(n * 3); val rgb = IntArray(n)
        good.forEachIndexed { k, t ->
            xyz[k*3] = t.p[0].toFloat(); xyz[k*3+1] = t.p[1].toFloat(); xyz[k*3+2] = t.p[2].toFloat()
            rgb[k] = t.rgb
        }
        val pf = File(outDir, "sfm_points.ply")
        PlyExporter.writeBinary(pf, xyz, rgb, n)

        val cams = imgs.filter { it.pose != null }
        val cxyz = FloatArray(cams.size * 3); val crgb = IntArray(cams.size) { Color.RED }
        cams.forEachIndexed { k, im ->
            val c = im.pose!!.center()
            cxyz[k*3] = c[0].toFloat(); cxyz[k*3+1] = c[1].toFloat(); cxyz[k*3+2] = c[2].toFloat()
        }
        val cf = File(outDir, "sfm_cameras.ply")
        PlyExporter.writeAsciiColored(cf, cxyz, crgb, cams.size)
        return pf to cf
    }

    private fun quatOf(r: DoubleArray): DoubleArray {          // [qw,qx,qy,qz]
        val tr = r[0] + r[4] + r[8]; val q = DoubleArray(4)
        if (tr > 0) { val s = sqrt(tr + 1.0) * 2
            q[0] = .25*s; q[1] = (r[5]-r[7])/s; q[2] = (r[6]-r[2])/s; q[3] = (r[1]-r[3])/s }
        else if (r[0] > r[4] && r[0] > r[8]) { val s = sqrt(1+r[0]-r[4]-r[8]) * 2
            q[0] = (r[5]-r[7])/s; q[1] = .25*s; q[2] = (r[3]+r[1])/s; q[3] = (r[6]+r[2])/s }
        else if (r[4] > r[8]) { val s = sqrt(1+r[4]-r[0]-r[8]) * 2
            q[0] = (r[6]-r[2])/s; q[1] = (r[3]+r[1])/s; q[2] = .25*s; q[3] = (r[7]+r[5])/s }
        else { val s = sqrt(1+r[8]-r[0]-r[4]) * 2
            q[0] = (r[1]-r[3])/s; q[1] = (r[6]+r[2])/s; q[2] = (r[7]+r[5])/s; q[3] = .25*s }
        return q
    }

    /** COLMAP sparse model in ORIGINAL photo resolution — seed for dense MVS on PC. */
    fun exportColmap(outDir: File) {
        outDir.mkdirs()
        val reg = imgs.withIndex().filter { it.value.pose != null }
        if (reg.isEmpty()) return

        class Entry(val q: DoubleArray, val t: DoubleArray,
                    val pts2d: ArrayList<Triple<Double, Double, Int>>)
        val entries = HashMap<Int, Entry>()
        val scale = HashMap<Int, Pair<Double, Double>>()
        val camIdOf = HashMap<Int, Int>()
        reg.forEachIndexed { ci, (i, im) ->
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(im.file.absolutePath, b)
            val sx = b.outWidth.toDouble() / im.w; val sy = b.outHeight.toDouble() / im.h
            scale[i] = sx to sy
            entries[i] = Entry(quatOf(im.pose!!.r), im.pose!!.t.copyOf(), ArrayList())
            camIdOf[i] = ci + 1
        }

        val pt2dOf = HashMap<Int, HashMap<Int, Int>>()
        for ((i, _) in reg) {
            val im = imgs[i]; val kp = im.kps!!.toArray(); val (sx, sy) = scale[i]!!
            for ((feat, trk) in obs[i].entries.sortedBy { it.key }) {
                if (!tracks[trk].valid) continue
                val p = kp[feat].pt
                entries[i]!!.pts2d.add(Triple(p.x * sx, p.y * sy, trk))
                pt2dOf.getOrPut(trk) { HashMap() }[i] = entries[i]!!.pts2d.size - 1
            }
        }
        val exported = pt2dOf.filterValues { it.size >= 3 }.keys

        File(outDir, "cameras.txt").bufferedWriter().use { w ->
            reg.forEachIndexed { ci, (i, im) ->
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(im.file.absolutePath, b)
                val s = b.outWidth.toDouble() / im.w
                w.write("${ci+1} PINHOLE ${b.outWidth} ${b.outHeight} " +
                        "${im.fx*s} ${im.fy*s} ${im.cx*s} ${im.cy*s}\n")
            }
        }
        File(outDir, "images.txt").bufferedWriter().use { w ->
            for ((i, _) in reg) {
                val e = entries[i]!!
                w.write("${i + 1} ${e.q.joinToString(" ")} ${e.t.joinToString(" ")} " +
                        "${camIdOf[i]} ${imgs[i].file.name}\n")
                val line = e.pts2d.joinToString(" ") { (x, y, trk) ->
                    "$x $y ${if (trk in exported) trk else -1}" }
                w.write(if (line.isEmpty()) "\n" else line + "\n")
            }
        }
        File(outDir, "points3D.txt").bufferedWriter().use { w ->
            for ((trk, perImg) in pt2dOf) {
                if (perImg.size < 3) continue
                val t = tracks[trk]; val x = t.p
                var errSum = 0.0; var n = 0
                val trackStr = StringBuilder()
                for ((i, idx) in perImg) {
                    val im = imgs[i]
                    val pr = project(im, x) ?: continue
                    val (sx, sy) = scale[i]!!
                    val e = entries[i]!!
                    errSum += sqrt(d2(pr, e.pts2d[idx].first / sx, e.pts2d[idx].second / sy)); n++
                    trackStr.append("$i $idx ")
                }
                if (n < 3) continue
                val c = t.rgb
                w.write("$trk ${x[0]} ${x[1]} ${x[2]} ${(c shr 16) and 0xFF} " +
                        "${(c shr 8) and 0xFF} ${c and 0xFF} ${errSum/n} $n $trackStr\n")
            }
        }
    }
}
