package com.screenocr.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry for an adjustable selection rectangle. No Android classes, so it is unit-testable on a JVM.
 *
 * Touch behaviour:
 *  - drag on empty area      -> draw a new rectangle (a plain tap keeps the previous one)
 *  - drag a corner           -> resize from that corner
 *  - drag an edge            -> resize that edge only
 *  - drag inside             -> move the whole rectangle
 */
class SelectionModel(private val minW: Float, private val minH: Float, private val grab: Float) {

    enum class Mode { NONE, NEW, MOVE, LEFT, TOP, RIGHT, BOTTOM, TL, TR, BL, BR }

    var boundsW = 0f
        private set
    var boundsH = 0f
        private set
    var left = 0f
        private set
    var top = 0f
        private set
    var right = 0f
        private set
    var bottom = 0f
        private set
    var has = false
        private set
    var mode = Mode.NONE
        private set

    private var anchorX = 0f
    private var anchorY = 0f
    private var offX = 0f
    private var offY = 0f
    private var prev: FloatArray? = null

    val width get() = right - left
    val height get() = bottom - top

    fun setBounds(w: Float, h: Float) {
        if (has && boundsW > 0f && boundsH > 0f) {
            val kx = w / boundsW
            val ky = h / boundsH
            left *= kx; right *= kx; top *= ky; bottom *= ky
        }
        boundsW = w
        boundsH = h
    }

    fun clear() {
        has = false
        mode = Mode.NONE
        prev = null
    }

    /** Which part of the current rectangle (if any) is under the finger. */
    fun hitTest(x: Float, y: Float): Mode {
        if (!has) return Mode.NEW
        // never let the grab zone eat the whole body, so a small box can still be moved
        val g = min(grab, min(width, height) / 3f)
        val inX = x >= left - g && x <= right + g
        val inY = y >= top - g && y <= bottom + g
        if (!inX || !inY) return Mode.NEW
        val nl = abs(x - left) <= g
        val nr = abs(x - right) <= g
        val nt = abs(y - top) <= g
        val nb = abs(y - bottom) <= g
        return when {
            nl && nt -> Mode.TL
            nr && nt -> Mode.TR
            nl && nb -> Mode.BL
            nr && nb -> Mode.BR
            nl -> Mode.LEFT
            nr -> Mode.RIGHT
            nt -> Mode.TOP
            nb -> Mode.BOTTOM
            x > left && x < right && y > top && y < bottom -> Mode.MOVE
            else -> Mode.NEW
        }
    }

    fun down(rx: Float, ry: Float) {
        val x = rx.coerceIn(0f, boundsW)
        val y = ry.coerceIn(0f, boundsH)
        mode = hitTest(x, y)
        when (mode) {
            Mode.NEW -> {
                prev = if (has) floatArrayOf(left, top, right, bottom) else null
                anchorX = x; anchorY = y
                left = x; right = x; top = y; bottom = y
                has = true
            }
            Mode.MOVE -> { offX = x - left; offY = y - top }
            Mode.LEFT, Mode.TL, Mode.BL -> offX = x - left
            Mode.RIGHT, Mode.TR, Mode.BR -> offX = x - right
            else -> {}
        }
        when (mode) {
            Mode.TOP, Mode.TL, Mode.TR -> offY = y - top
            Mode.BOTTOM, Mode.BL, Mode.BR -> offY = y - bottom
            else -> {}
        }
    }

    fun move(rx: Float, ry: Float) {
        val x = rx.coerceIn(0f, boundsW)
        val y = ry.coerceIn(0f, boundsH)
        when (mode) {
            Mode.NEW -> {
                left = min(anchorX, x); right = max(anchorX, x)
                top = min(anchorY, y); bottom = max(anchorY, y)
            }
            Mode.MOVE -> {
                val w = width
                val h = height
                left = (x - offX).coerceIn(0f, max(0f, boundsW - w))
                top = (y - offY).coerceIn(0f, max(0f, boundsH - h))
                right = left + w
                bottom = top + h
            }
            else -> {
                if (mode == Mode.LEFT || mode == Mode.TL || mode == Mode.BL)
                    left = (x - offX).coerceIn(0f, right - minW)
                if (mode == Mode.RIGHT || mode == Mode.TR || mode == Mode.BR)
                    right = (x - offX).coerceIn(left + minW, boundsW)
                if (mode == Mode.TOP || mode == Mode.TL || mode == Mode.TR)
                    top = (y - offY).coerceIn(0f, bottom - minH)
                if (mode == Mode.BOTTOM || mode == Mode.BL || mode == Mode.BR)
                    bottom = (y - offY).coerceIn(top + minH, boundsH)
            }
        }
    }

    /** Finish the gesture. Returns true if there is a usable selection afterwards. */
    fun up(): Boolean {
        val m = mode
        mode = Mode.NONE
        if (m == Mode.NEW) {
            if (width > minW && height > minH) { prev = null; return true }
            val p = prev
            prev = null
            if (p != null) {            // a tap or a too-small drag must not destroy the previous selection
                left = p[0]; top = p[1]; right = p[2]; bottom = p[3]
                return true
            }
            has = false
            return false
        }
        return has && width > 0f && height > 0f
    }
}
