package com.screenocr.app

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Same public API as before: OcrEngine.get(ctx).recognize(bitmap, langs): String.
 *
 * What changed:
 *  1. Pre-processing (ImageEnhancer): best-contrast grayscale, automatic light/dark text detection,
 *     background flattening, sharpening and text-size based scaling instead of "invert if dark".
 *  2. Adaptive retry: if Tesseract is not confident, try the opposite polarity and a larger scale
 *     and keep the most confident result.
 *  3. "eng+urd" no longer loads both models into one engine (that made the Urdu model garble English
 *     and the English model garble Urdu). English runs first; Urdu runs only if some line is
 *     low-confidence, and the two results are merged line by line.
 */
class OcrEngine private constructor(private val app: Context) {

    private class Line(val text: String, val conf: Float, val l: Int, val t: Int, val r: Int, val b: Int)
    private class Pass(val text: String, val conf: Int, val lines: List<Line>) {
        val chars: Int get() = text.count { !it.isWhitespace() }
    }

    private val engines = HashMap<String, TessBaseAPI>()

    @Synchronized
    fun recognize(source: Bitmap, langs: String): String {
        ensureData()
        val bmp = toWorkingBitmap(source)
        try {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val enhancer = ImageEnhancer(px, w, h)
            val plan = enhancer.analyze()
            val cache = HashMap<ImageEnhancer.Plan, ImageEnhancer.Result>()

            val wantsBoth = langs.contains("eng") && langs.contains("urd")
            if (!wantsBoth) return readBest(langs, enhancer, plan, cache).text

            val eng = readBest("eng", enhancer, plan, cache)
            if (eng.lines.isNotEmpty() && eng.lines.all { it.conf >= LOW_LINE_CONF }) return eng.text
            val urd = readBest("urd", enhancer, plan, cache)
            return merge(eng, urd)
        } finally {
            if (bmp !== source) bmp.recycle()
        }
    }

    /** Run the primary candidate; if not confident, try alternates and keep the best. */
    private fun readBest(
        lang: String, enhancer: ImageEnhancer, plan: ImageEnhancer.Plan,
        cache: HashMap<ImageEnhancer.Plan, ImageEnhancer.Result>
    ): Pass {
        val api = engine(lang)
        val candidates = listOf(
            plan,
            plan.copy(darkInk = !plan.darkInk),
            plan.copy(scale = min(3f, plan.scale * 1.5f))
        )
        val seen = ArrayList<Pass>()
        var best: Pass? = null
        for (c in candidates) {
            val img = cache.getOrPut(c) { enhancer.render(c) }
            val pass = run(api, img)
            seen.add(pass)
            val longest = seen.maxOf { it.chars }
            // ignore candidates that produced far less text than the others: they are usually a wrong guess
            best = seen.filter { it.chars >= longest / 2 }.maxByOrNull { it.conf }
            if (best != null && best.conf >= GOOD_CONF) break
        }
        return best!!
    }

    private fun run(api: TessBaseAPI, img: ImageEnhancer.Result): Pass {
        api.setImage(img.pixels, img.width, img.height, 1, img.width)
        try {
            val text = api.getUTF8Text() ?: ""
            val conf = api.meanConfidence()
            val lines = ArrayList<Line>()
            val iter = api.getResultIterator()
            if (iter != null) {
                try {
                    val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                    iter.begin()
                    do {
                        val t = iter.getUTF8Text(level)?.trim()
                        if (!t.isNullOrEmpty()) {
                            val b = iter.getBoundingBox(level) // left, top, right, bottom in enhanced-image pixels
                            val s = img.scale
                            val p = ImageEnhancer.PAD
                            lines.add(
                                Line(
                                    t, iter.confidence(level),
                                    ((b[0] - p) / s).toInt(), ((b[1] - p) / s).toInt(),
                                    ((b[2] - p) / s).toInt(), ((b[3] - p) / s).toInt()
                                )
                            )
                        }
                    } while (iter.next(level))
                } finally {
                    iter.delete()
                }
            }
            return Pass(text, conf, lines)
        } finally {
            api.clear()
        }
    }

    /** Overlapping lines from the two engines: keep the more confident one. */
    private fun merge(a: Pass, b: Pass): String {
        val kept = ArrayList<Line>()
        for (l in (a.lines + b.lines).sortedByDescending { it.conf }) {
            if (kept.none { overlap(l, it) > 0.5f }) kept.add(l)
        }
        if (kept.isEmpty()) return if (a.chars >= b.chars) a.text else b.text
        return kept.sortedWith(compareBy({ it.t / 20 }, { it.l })).joinToString("\n") { it.text }
    }

    private fun overlap(a: Line, b: Line): Float {
        val iw = min(a.r, b.r) - max(a.l, b.l)
        val ih = min(a.b, b.b) - max(a.t, b.t)
        if (iw <= 0 || ih <= 0) return 0f
        val areaA = (a.r - a.l).toLong() * (a.b - a.t)
        val areaB = (b.r - b.l).toLong() * (b.b - b.t)
        return iw.toFloat() * ih / max(1L, min(areaA, areaB))
    }

    private fun engine(lang: String): TessBaseAPI {
        engines[lang]?.let { return it }
        val fresh = TessBaseAPI()
        if (!fresh.init(app.filesDir.absolutePath, lang)) {
            fresh.recycle()
            throw IllegalStateException("Could not load OCR language data ($lang)")
        }
        fresh.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        fresh.setVariable("user_defined_dpi", "300")
        engines[lang] = fresh
        return fresh
    }

    /** Hardware bitmaps cannot be read with getPixels, and huge ones would exhaust memory. */
    private fun toWorkingBitmap(src: Bitmap): Bitmap {
        var b = src
        if (b.config == Bitmap.Config.HARDWARE) {
            b = b.copy(Bitmap.Config.ARGB_8888, false) ?: src
        }
        val px = b.width.toLong() * b.height
        if (px > MAX_SOURCE_PIXELS) {
            val f = Math.sqrt(MAX_SOURCE_PIXELS.toDouble() / px)
            val scaled = Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
            if (b !== src) b.recycle()
            b = scaled
        }
        return b
    }

    private fun ensureData() {
        val dir = File(app.filesDir, "tessdata")
        dir.mkdirs()
        for (name in FILES) {
            val out = File(dir, name)
            if (!out.exists() || out.length() <= 0) {
                val tmp = File(dir, "$name.tmp")
                app.assets.open("tessdata/$name").use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(out)) throw IllegalStateException("Could not install $name")
            }
        }
    }

    companion object {
        private val FILES = listOf("eng.traineddata", "urd.traineddata")

        /** Mean word confidence at/above which a result is accepted without retrying (good reads measured 81+). */
        private const val GOOD_CONF = 75

        /** English pass with any line below this triggers the Urdu pass (bad reads measured <= 43). */
        private const val LOW_LINE_CONF = 65f

        private const val MAX_SOURCE_PIXELS = 6_000_000L

        @Volatile
        private var instance: OcrEngine? = null

        fun get(context: Context): OcrEngine =
            instance ?: synchronized(this) {
                instance ?: OcrEngine(context.applicationContext).also { instance = it }
            }
    }
}
