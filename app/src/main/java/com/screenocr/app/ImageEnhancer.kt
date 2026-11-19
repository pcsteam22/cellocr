package com.screenocr.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * OCR pre-processing that copes with blur, uneven / photographic backgrounds, colour-on-colour text,
 * light-on-dark text and tiny text.
 *
 * Pure Kotlin on purpose: no Android classes, no OpenCV, so it can be unit-tested on a plain JVM.
 * Pixels are row-major FloatArrays in 0..255 where 0 = ink (dark) and 255 = paper.
 *
 * Typical use:  val e = ImageEnhancer(argb, w, h);  val plan = e.analyze();  val r = e.render(plan)
 */
class ImageEnhancer(private val argb: IntArray, private val w: Int, private val h: Int) {

    /** What [analyze] decided. Copy it with another polarity / scale to get retry candidates. */
    data class Plan(val darkInk: Boolean, val scale: Float, val charH: Float)

    /** 8-bit grayscale image, dark text on a flat light background, with a white border. */
    class Result(val pixels: ByteArray, val width: Int, val height: Int, val scale: Float)

    private val gray: FloatArray by lazy { toGrayPca() }

    /** Decide polarity (dark-on-light or light-on-dark) and a sensible up/down-scale for this image. */
    fun analyze(): Plan {
        val g = gray
        val r0 = max(8, min(h, w) / 30)
        val dark = polarityDarkInk(g, w, h, r0)
        val gi = if (dark) g else invert(g)
        val ink = sauvolaInk(gi, w, h, max(15, r0 * 2 + 1), 0.25f)
        val ch = estimateCharHeight(ink, w, h)
        val minSide = min(w, h)
        var sc = when {
            ch == null -> if (minSide < 150) 3f else if (minSide < 700) 2f else 1f
            ch > TARGET_CHAR_H * 2.2f -> (TARGET_CHAR_H * 1.4f / ch).coerceIn(0.4f, 1f)
            // The estimate is noisy on photo backgrounds, so never enlarge by more than 2.5x.
            ch < TARGET_CHAR_H * 0.85f -> (TARGET_CHAR_H / ch).coerceIn(1f, 2.5f)
            else -> 1f
        }
        sc = capScale(sc)
        return Plan(dark, sc, ch ?: 20f)
    }

    /** Render one candidate image for the given plan. */
    fun render(plan: Plan): Result {
        val sc = capScale(plan.scale)
        var gi = if (plan.darkInk) gray.copyOf() else invert(gray)
        var cw = w
        var chh = h
        if (abs(sc - 1f) > 0.02f) {
            val nw = max(1, (w * sc).toInt())
            val nh = max(1, (h * sc).toInt())
            gi = resize(gi, w, h, nw, nh)
            cw = nw
            chh = nh
        }
        val chs = plan.charH * sc

        // Unsharp mask: recovers edges of blurry text.
        val soft = gaussApprox(gi, cw, chh, max(1, (chs * 0.08f).roundToInt()))
        for (i in gi.indices) gi[i] = (gi[i] + 1.2f * (gi[i] - soft[i])).coerceIn(0f, 255f)

        // Background flattening: how far each pixel is below its (large-scale) local background.
        val bg = gaussApprox(gi, cw, chh, max(8, (chs * 1.2f).toInt()))
        for (i in gi.indices) bg[i] = gi[i] - bg[i]
        val lo = min(percentiles(bg, doubleArrayOf(1.0))[0], -1f)

        val ow = cw + 2 * PAD
        val oh = chh + 2 * PAD
        val out = ByteArray(ow * oh)
        java.util.Arrays.fill(out, 0xFF.toByte())
        for (y in 0 until chh) {
            var o = (y + PAD) * ow + PAD
            var i = y * cw
            for (x in 0 until cw) {
                val a = (bg[i] / lo).coerceIn(0f, 1f)
                out[o] = (255f - a * 255f).toInt().toByte()
                o++; i++
            }
        }
        return Result(out, ow, oh, sc)
    }

    private fun capScale(s: Float): Float {
        var sc = s
        while (sc > 0.1f && w.toDouble() * sc * h.toDouble() * sc > MAX_PIXELS) sc *= 0.9f
        return sc
    }

    // ---- grayscale: project RGB on the axis of greatest variance, so red-on-green etc. keeps contrast ----
    private fun toGrayPca(): FloatArray {
        val sy = max(1, h / 200)
        val sx = max(1, w / 200)
        var n = 0.0
        val m = DoubleArray(3)
        val c = Array(3) { DoubleArray(3) }
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = argb[y * w + x]
                val v = doubleArrayOf(((p shr 16) and 255).toDouble(), ((p shr 8) and 255).toDouble(), (p and 255).toDouble())
                n++
                for (i in 0..2) {
                    m[i] += v[i]
                    for (j in 0..2) c[i][j] += v[i] * v[j]
                }
                x += sx
            }
            y += sy
        }
        for (i in 0..2) m[i] /= n
        for (i in 0..2) for (j in 0..2) c[i][j] = c[i][j] / n - m[i] * m[j]
        // power iteration for the dominant eigenvector
        var ax = doubleArrayOf(0.30, 0.59, 0.11)
        var eig = 0.0
        for (it in 0 until 30) {
            val nx = DoubleArray(3) { i -> c[i][0] * ax[0] + c[i][1] * ax[1] + c[i][2] * ax[2] }
            val norm = sqrt(nx[0] * nx[0] + nx[1] * nx[1] + nx[2] * nx[2])
            if (norm < 1e-9) break
            for (i in 0..2) nx[i] /= norm
            ax = nx
            eig = norm
        }
        if (eig < 1e-3) ax = doubleArrayOf(0.30, 0.59, 0.11)
        if (ax[0] + ax[1] + ax[2] < 0) for (i in 0..2) ax[i] = -ax[i]
        val g = FloatArray(w * h)
        val a0 = ax[0].toFloat(); val a1 = ax[1].toFloat(); val a2 = ax[2].toFloat()
        val m0 = m[0].toFloat(); val m1 = m[1].toFloat(); val m2 = m[2].toFloat()
        for (i in g.indices) {
            val p = argb[i]
            g[i] = (((p shr 16) and 255) - m0) * a0 + (((p shr 8) and 255) - m1) * a1 + ((p and 255) - m2) * a2
        }
        val pr = percentiles(g, doubleArrayOf(0.5, 99.5))
        val span = max(1e-3f, pr[1] - pr[0])
        for (i in g.indices) g[i] = ((g[i] - pr[0]) / span * 255f).coerceIn(0f, 255f)
        return g
    }

    companion object {
        const val TARGET_CHAR_H = 30f
        const val MAX_PIXELS = 5_000_000.0
        const val PAD = 16

        fun invert(a: FloatArray): FloatArray = FloatArray(a.size) { 255f - a[it] }

        /** Box blur with edge replication, O(N) regardless of radius. */
        fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
            val r = max(1, radius)
            val k = (2 * r + 1).toDouble()
            val tmp = FloatArray(src.size)
            for (y in 0 until h) {
                val row = y * w
                var s = 0.0
                for (i in -r..r) s += src[row + i.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[row + x] = (s / k).toFloat()
                    s += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
                }
            }
            val out = FloatArray(src.size)
            val col = DoubleArray(w)
            for (i in -r..r) {
                val base = i.coerceIn(0, h - 1) * w
                for (x in 0 until w) col[x] += tmp[base + x].toDouble()
            }
            for (y in 0 until h) {
                val base = y * w
                for (x in 0 until w) out[base + x] = (col[x] / k).toFloat()
                val add = (y + r + 1).coerceAtMost(h - 1) * w
                val sub = (y - r).coerceAtLeast(0) * w
                for (x in 0 until w) col[x] += (tmp[add + x] - tmp[sub + x]).toDouble()
            }
            return out
        }

        /** Three box passes approximate a Gaussian. */
        fun gaussApprox(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            var x = a
            for (i in 0 until 3) x = boxBlur(x, w, h, r)
            return x
        }

        /** Approximate percentiles (histogram based, 2048 bins). */
        fun percentiles(a: FloatArray, ps: DoubleArray): FloatArray {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (v in a) { if (v < lo) lo = v; if (v > hi) hi = v }
            if (hi - lo < 1e-6f) return FloatArray(ps.size) { lo }
            val bins = 2048
            val hist = IntArray(bins)
            val scale = (bins - 1) / (hi - lo)
            for (v in a) hist[((v - lo) * scale).toInt()]++
            return FloatArray(ps.size) { pi ->
                val target = ps[pi] / 100.0 * a.size
                var acc = 0L
                var b = 0
                while (b < bins - 1 && acc + hist[b] < target) { acc += hist[b]; b++ }
                lo + b / scale
            }
        }

        /** True if text is darker than its surroundings (dark ink on light paper). */
        fun polarityDarkInk(g: FloatArray, w: Int, h: Int, r: Int): Boolean {
            val blur = gaussApprox(g, w, h, r)
            for (i in blur.indices) blur[i] = g[i] - blur[i]
            val p = percentiles(blur, doubleArrayOf(2.0, 98.0))
            return abs(p[0]) >= abs(p[1])
        }

        /** Sauvola local threshold; true where the pixel is clearly darker than its neighbourhood. */
        fun sauvolaInk(g: FloatArray, w: Int, h: Int, win: Int, k: Float): BooleanArray {
            val r = win / 2
            val m = boxBlur(g, w, h, r)
            val sq = FloatArray(g.size) { g[it] * g[it] }
            val m2 = boxBlur(sq, w, h, r)
            val ink = BooleanArray(g.size)
            for (i in g.indices) {
                val s = sqrt(max(m2[i] - m[i] * m[i], 0f))
                ink[i] = g[i] < m[i] * (1f + k * (s / 128f - 1f))
            }
            return ink
        }

        /** Median height of connected ink blobs ~ text height; null if there is too little to judge. */
        fun estimateCharHeight(ink: BooleanArray, w: Int, h: Int): Float? {
            val labels = IntArray(ink.size)
            var parent = IntArray(1024)
            var next = 1
            fun find(a: Int): Int {
                var x = a
                while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
                return x
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!ink[i]) continue
                    var best = 0
                    val n0 = if (x > 0) labels[i - 1] else 0
                    val n1 = if (y > 0 && x > 0) labels[i - w - 1] else 0
                    val n2 = if (y > 0) labels[i - w] else 0
                    val n3 = if (y > 0 && x < w - 1) labels[i - w + 1] else 0
                    for (nb in intArrayOf(n0, n1, n2, n3)) if (nb != 0 && (best == 0 || nb < best)) best = nb
                    if (best == 0) {
                        if (next >= parent.size) parent = parent.copyOf(parent.size * 2)
                        parent[next] = next
                        best = next++
                    } else {
                        for (nb in intArrayOf(n0, n1, n2, n3)) if (nb != 0) {
                            val a = find(nb); val b = find(best)
                            if (a != b) { if (a < b) parent[b] = a else parent[a] = b }
                        }
                        best = find(best)
                    }
                    labels[i] = best
                }
            }
            val minY = IntArray(next) { Int.MAX_VALUE }
            val maxY = IntArray(next) { -1 }
            val minX = IntArray(next) { Int.MAX_VALUE }
            val maxX = IntArray(next) { -1 }
            for (y in 0 until h) for (x in 0 until w) {
                val l = labels[y * w + x]
                if (l == 0) continue
                val r = find(l)
                if (y < minY[r]) minY[r] = y
                if (y > maxY[r]) maxY[r] = y
                if (x < minX[r]) minX[r] = x
                if (x > maxX[r]) maxX[r] = x
            }
            val hist = IntArray(h + 2)
            var count = 0
            for (l in 1 until next) {
                if (parent[l] != l || maxY[l] < 0) continue
                val bh = maxY[l] - minY[l] + 1
                val bw = maxX[l] - minX[l] + 1
                if (bh >= 4 && bh < h * 0.5 && bw < h * 0.9) { hist[bh]++; count++ }
            }
            if (count < 8) return null
            var acc = 0
            for (v in hist.indices) { acc += hist[v]; if (acc * 2 >= count) return v.toFloat() }
            return null
        }

        /** Bilinear resize; down-scaling is pre-blurred to avoid aliasing. */
        fun resize(src: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
            var s = src
            if (nw < w || nh < h) {
                val rr = max(1, (max(w.toFloat() / nw, h.toFloat() / nh) / 2f).toInt())
                s = boxBlur(src, w, h, rr)
            }
            val out = FloatArray(nw * nh)
            val xi0 = IntArray(nw); val xi1 = IntArray(nw); val xf = FloatArray(nw)
            for (x in 0 until nw) {
                val sx = ((x + 0.5f) * w / nw - 0.5f).coerceIn(0f, (w - 1).toFloat())
                xi0[x] = sx.toInt(); xi1[x] = min(w - 1, xi0[x] + 1); xf[x] = sx - xi0[x]
            }
            for (y in 0 until nh) {
                val sy = ((y + 0.5f) * h / nh - 0.5f).coerceIn(0f, (h - 1).toFloat())
                val y0 = sy.toInt(); val y1 = min(h - 1, y0 + 1); val fy = sy - y0
                val r0 = y0 * w; val r1 = y1 * w; val o = y * nw
                for (x in 0 until nw) {
                    val top = s[r0 + xi0[x]] * (1 - xf[x]) + s[r0 + xi1[x]] * xf[x]
                    val bot = s[r1 + xi0[x]] * (1 - xf[x]) + s[r1 + xi1[x]] * xf[x]
                    out[o + x] = top * (1 - fy) + bot * fy
                }
            }
            return out
        }
    }
}
