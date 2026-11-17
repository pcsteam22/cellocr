package com.screenocr.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.math.min

/**
 * Wraps Tesseract (LSTM). Language data lives in assets/tessdata and is copied to
 * internal storage on first use. Images are pre-processed (grayscale, dark-mode
 * inversion, upscaling of small crops, white margin) before recognition.
 */
class OcrEngine private constructor(private val app: Context) {

    companion object {
        @Volatile
        private var instance: OcrEngine? = null

        fun get(context: Context): OcrEngine =
            instance ?: synchronized(this) {
                instance ?: OcrEngine(context.applicationContext).also { instance = it }
            }

        private val FILES = listOf("eng.traineddata", "urd.traineddata")
    }

    private var api: TessBaseAPI? = null
    private var currentLang: String? = null

    @Synchronized
    fun recognize(source: Bitmap, langs: String): String {
        ensureData()
        var engine = api
        if (engine == null || currentLang != langs) {
            engine?.recycle()
            engine = null
            val fresh = TessBaseAPI()
            if (!fresh.init(app.filesDir.absolutePath, langs)) {
                fresh.recycle()
                throw IllegalStateException("Could not load OCR language data ($langs)")
            }
            fresh.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api = fresh
            currentLang = langs
            engine = fresh
        }

        val prepared = prepare(source)
        try {
            engine.setImage(prepared)
            val text = engine.getUTF8Text() ?: ""
            engine.clear()
            return text
        } finally {
            if (prepared !== source) prepared.recycle()
        }
    }

    private fun ensureData() {
        val dir = File(app.filesDir, "tessdata")
        dir.mkdirs()
        for (name in FILES) {
            val out = File(dir, name)
            if (out.exists() && out.length() > 0L) continue
            val tmp = File(dir, "$name.tmp")
            app.assets.open("tessdata/$name").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(out)) throw IllegalStateException("Could not install $name")
        }
    }

    private fun prepare(src: Bitmap): Bitmap {
        val minSide = min(src.width, src.height)
        var scale = when {
            minSide < 150 -> 3f
            minSide < 700 -> 2f
            else -> 1f
        }
        while (scale > 1f && src.width * scale * src.height * scale > 14_000_000f) scale -= 0.5f
        if (scale < 1f) scale = 1f

        // Dark backgrounds (dark-mode apps) confuse OCR: detect and invert.
        val small = Bitmap.createScaledBitmap(src, 48, 48, true)
        val px = IntArray(48 * 48)
        small.getPixels(px, 0, 48, 0, 0, 48, 48)
        if (small !== src) small.recycle()
        var sum = 0L
        for (p in px) sum += (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
        val dark = sum / px.size < 110

        val pad = 16
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        val out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val matrix = ColorMatrix().apply { setSaturation(0f) }
        if (dark) {
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val paint = Paint().apply {
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(src, null, Rect(pad, pad, pad + w, pad + h), paint)
        return out
    }
}
