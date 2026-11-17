package com.screenocr.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Shows a frozen screenshot, dimmed, and lets the user drag a rectangle over it. */
class SelectionView(context: Context, private val bmp: Bitmap) : View(context) {

    var onSelectionChanged: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var sel: RectF? = null
    private var startX = 0f
    private var startY = 0f
    private val dst = Rect()

    private val dim = Paint().apply { color = 0xAA000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4C9AFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f * density
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bmp, null, dst, null)
        val s = sel
        if (s == null) {
            canvas.drawRect(dst, dim)
            canvas.drawText("Drag to select the text area", width / 2f, height * 0.3f, hint)
        } else {
            canvas.save()
            canvas.clipOutRect(s)
            canvas.drawRect(dst, dim)
            canvas.restore()
            canvas.drawRect(s, border)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x.coerceIn(0f, width.toFloat())
        val y = e.y.coerceIn(0f, height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                sel = RectF(x, y, x, y)
                onSelectionChanged?.invoke(false)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                sel?.set(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = sel
                val ok = s != null && s.width() > 24 * density && s.height() > 16 * density
                if (!ok) sel = null
                onSelectionChanged?.invoke(ok)
                invalidate()
            }
        }
        return true
    }

    /** Returns the selected region as a new bitmap, or null when nothing is selected. */
    fun crop(): Bitmap? {
        val s = sel ?: return null
        if (width == 0 || height == 0) return null
        val kx = bmp.width.toFloat() / width
        val ky = bmp.height.toFloat() / height
        val l = (s.left * kx).toInt().coerceIn(0, bmp.width - 1)
        val t = (s.top * ky).toInt().coerceIn(0, bmp.height - 1)
        val r = (s.right * kx).toInt().coerceIn(l + 1, bmp.width)
        val b = (s.bottom * ky).toInt().coerceIn(t + 1, bmp.height)
        return Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    }
}
