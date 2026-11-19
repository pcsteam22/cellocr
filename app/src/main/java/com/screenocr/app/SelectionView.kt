package com.screenocr.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View

/**
 * Shows the captured screenshot and lets the user pick a text area.
 *
 * - Drag on empty space to draw a rectangle.
 * - Afterwards drag a corner or an edge to resize, or drag inside to move it.
 * - A plain tap outside keeps the current selection.
 *
 * Public API is unchanged: [onSelectionChanged] and [crop]. New: [onSelectionSettled].
 */
class SelectionView(context: Context, private val bmp: Bitmap) : View(context) {

    /** Called with true when a usable selection exists (false while dragging / when empty). */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    /** Called when a gesture ends, with the selection rectangle in view coordinates (or null). */
    var onSelectionSettled: ((RectF?) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val model = SelectionModel(minW = 24f * density, minH = 16f * density, grab = 28f * density)
    private val dst = Rect()
    private val rect = RectF()

    private val dim = Paint().apply { color = 0xAA000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A90FF.toInt(); style = Paint.Style.STROKE; strokeWidth = density * 3f
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A90FF.toInt(); style = Paint.Style.STROKE; strokeWidth = density * 2f
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = density * 17f; textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        model.setBounds(w.toFloat(), h.toFloat())
        if (Build.VERSION.SDK_INT >= 29) {
            // keep the system back-gesture from stealing drags that start at the screen edge
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
    }

    override fun onDraw(canvas: Canvas) {
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bmp, null, dst, null)
        if (!model.has) {
            canvas.drawRect(dst, dim)
            canvas.drawText("Drag to select the text area", width / 2f, height * 0.3f, hint)
            return
        }
        rect.set(model.left, model.top, model.right, model.bottom)
        canvas.save()
        canvas.clipOutRect(rect)
        canvas.drawRect(dst, dim)
        canvas.restore()
        canvas.drawRect(rect, border)
        if (model.mode == SelectionModel.Mode.NEW) return // no handles while still drawing

        val r = density * 9f
        val cx = rect.centerX()
        val cy = rect.centerY()
        // corners
        for ((x, y) in listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom)) {
            canvas.drawCircle(x, y, r, handleFill)
            canvas.drawCircle(x, y, r, handleStroke)
        }
        // edge midpoints (only if the edge is long enough not to collide with the corners)
        val bw = density * 22f
        val bt = density * 5f
        if (rect.width() > 5 * r) {
            canvas.drawRoundRect(cx - bw / 2, rect.top - bt, cx + bw / 2, rect.top + bt, bt, bt, handleFill)
            canvas.drawRoundRect(cx - bw / 2, rect.bottom - bt, cx + bw / 2, rect.bottom + bt, bt, bt, handleFill)
        }
        if (rect.height() > 5 * r) {
            canvas.drawRoundRect(rect.left - bt, cy - bw / 2, rect.left + bt, cy + bw / 2, bt, bt, handleFill)
            canvas.drawRoundRect(rect.right - bt, cy - bw / 2, rect.right + bt, cy + bw / 2, bt, bt, handleFill)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                model.down(e.x, e.y)
                onSelectionChanged?.invoke(false)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                model.move(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val ok = model.up()
                onSelectionChanged?.invoke(ok)
                onSelectionSettled?.invoke(
                    if (ok) RectF(model.left, model.top, model.right, model.bottom) else null
                )
                invalidate()
            }
        }
        return true
    }

    /** The selected area cut out of the original screenshot, or null if nothing is selected. */
    fun crop(): Bitmap? {
        if (!model.has || width == 0 || height == 0) return null
        val kx = bmp.width.toFloat() / width
        val ky = bmp.height.toFloat() / height
        val l = (model.left * kx).toInt().coerceIn(0, bmp.width - 1)
        val t = (model.top * ky).toInt().coerceIn(0, bmp.height - 1)
        val r = (model.right * kx).toInt().coerceIn(l + 1, bmp.width)
        val b = (model.bottom * ky).toInt().coerceIn(t + 1, bmp.height)
        return Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    }
}
