package com.screenocr.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.widget.doAfterTextChanged
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground service that owns the MediaProjection (screen capture permission),
 * draws the draggable floating button on top of other apps, captures the screen
 * on demand (full screen or user-selected area) and runs OCR on it.
 */
class OverlayService : Service() {

    companion object {
        @Volatile
        var running = false

        const val ACTION_STOP = "com.screenocr.app.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL = "ocr_overlay"
        private const val NOTIF_ID = 1001
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bg: Handler
    private val ocrPool = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val lock = Any()
    private var latest: Image? = null
    private var capW = 0
    private var capH = 0

    private var bubbleRoot: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menu: LinearLayout? = null
    private var selector: View? = null
    private var panel: View? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bgThread = HandlerThread("ocr-capture").also { it.start() }
        bg = Handler(bgThread.looper)
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        // Must become a foreground service (type mediaProjection) BEFORE using the projection.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            toast("Screen capture could not be started")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                ui.post { stopSelf() }
            }
        }
        projectionCallback = cb
        p.registerCallback(cb, ui) // required before createVirtualDisplay on Android 14+

        setupCapture(p)
        showBubble()
        running = true
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ capture

    private fun screenInfo(): Triple<Int, Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), resources.configuration.densityDpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener(ImageReader.OnImageAvailableListener { rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(lock) {
                    latest?.close()
                    latest = img
                }
            }
        }, bg)
        return r
    }

    private fun setupCapture(p: MediaProjection) {
        val (w, h, dpi) = screenInfo()
        capW = w
        capH = h
        val r = newReader(w, h)
        reader = r
        display = p.createVirtualDisplay(
            "screen-ocr", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = display ?: return
        val (w, h, dpi) = screenInfo()
        if (w == capW && h == capH) return
        val newReader = newReader(w, h)
        vd.resize(w, h, dpi)
        vd.surface = newReader.surface
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        reader?.close()
        reader = newReader
        capW = w
        capH = h
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val wide = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            plane.buffer.rewind()
            wide.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) {
                wide
            } else {
                Bitmap.createBitmap(wide, 0, 0, image.width, image.height).also { wide.recycle() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Hides our own overlay, waits for a clean frame, and returns it as a bitmap. */
    private fun grab(done: (Bitmap?) -> Unit) {
        bubbleRoot?.visibility = View.INVISIBLE
        bg.postDelayed({
            val bmp = synchronized(lock) { latest?.let { imageToBitmap(it) } }
            ui.post { done(bmp) }
        }, 350)
    }

    // ------------------------------------------------------------------ actions

    private fun onFullScreen() {
        if (busy) return
        busy = true
        collapseMenu()
        grab { bmp ->
            if (bmp == null) failCapture() else runOcr(bmp)
        }
    }

    private fun onSelectArea() {
        if (busy) return
        busy = true
        collapseMenu()
        grab { bmp ->
            if (bmp == null) failCapture() else showSelector(bmp)
        }
    }

    private fun failCapture() {
        busy = false
        bubbleRoot?.visibility = View.VISIBLE
        toast("Couldn't capture the screen. Please try again.")
    }

    private fun runOcr(bmp: Bitmap) {
        bubbleRoot?.visibility = View.VISIBLE
        toast(getString(R.string.extracting))
        val lang = Prefs.lang(this)
        ocrPool.execute {
            val result: String? = try {
                TextAnalyzer.clean(OcrEngine.get(this).recognize(bmp, lang))
            } catch (t: Throwable) {
                null
            }
            bmp.recycle()
            ui.post {
                busy = false
                deliver(result)
            }
        }
    }

    private fun deliver(text: String?) {
        when {
            text == null -> toast("Text recognition failed. Please try again.")
            text.isBlank() -> toast("No text found")
            else -> {
                if (Prefs.autoCopy(this)) {
                    copyToClipboard(text)
                    toast("Copied to clipboard")
                }
                // Shown as an overlay panel: phones like Vivo/OriginOS block background
                // services from opening a new activity, but overlay windows always work.
                showResultPanel(text)
            }
        }
    }

    private fun copyToClipboard(value: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OCR text", value))
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ UI: result panel

    private fun showResultPanel(result: String) {
        removePanel()
        bubbleRoot?.visibility = View.INVISIBLE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), 0xFFBBBBBB.toInt())
            }
        }

        val title = TextView(this).apply {
            text = "Extracted text"
            setTextColor(Color.BLACK)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val stats = TextView(this).apply {
            setTextColor(0xFF666666.toInt())
            textSize = 12f
        }
        val edit = EditText(this).apply {
            setText(result)
            setTextColor(Color.BLACK)
            textSize = 18f
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            maxLines = 12
            isVerticalScrollBarEnabled = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFFF1F3F5.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        fun refreshStats() {
            val s = TextAnalyzer.stats(edit.text.toString())
            stats.text = "${s.words} words · ${s.chars} characters · ${s.lines} lines · ${s.script}"
        }
        edit.doAfterTextChanged { refreshStats() }
        refreshStats()

        fun button(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(fg)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(12), dp(8), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(bg)
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
                setOnClickListener { onClick() }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        row.addView(button("Copy", 0xFF3B5BDB.toInt(), Color.WHITE) {
            copyToClipboard(edit.text.toString())
            toast("Copied to clipboard")
        })
        row.addView(button("Join lines", 0xFFE7EAF3.toInt(), Color.BLACK) {
            edit.setText(TextAnalyzer.reflow(edit.text.toString()))
        })
        row.addView(button("Close", 0xFFE7EAF3.toInt(), Color.BLACK) { removePanel() })

        root.addView(title)
        root.addView(stats)
        root.addView(edit)
        root.addView(row)

        // Focusable on purpose (no FLAG_NOT_FOCUSABLE) so the keyboard works for editing.
        val params = WindowManager.LayoutParams(
            (screenInfo().first * 0.94f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(70)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(root, params)
        panel = root
    }

    private fun removePanel() {
        panel?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // already removed
            }
        }
        panel = null
        bubbleRoot?.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ UI: floating bubble

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xEE202124.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    private fun showBubble() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = TextView(this).apply {
            text = "OCR"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE3B5BDB.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }

        val m = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        m.addView(pill("Full screen") { onFullScreen() })
        m.addView(pill("Select area") { onSelectArea() })
        m.addView(pill("Stop") { stopSelf() })

        root.addView(bubble)
        root.addView(m)
        menu = m

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(220)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        bubble.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (abs(dx) > slop || abs(dy) > slop) moved = true
                        if (moved) {
                            params.x = startX + dx
                            params.y = startY + dy
                            wm.updateViewLayout(root, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) toggleMenu()
                }
                return true
            }
        })

        wm.addView(root, params)
        bubbleRoot = root
        bubbleParams = params
    }

    private fun toggleMenu() {
        val m = menu ?: return
        if (m.visibility == View.VISIBLE) {
            collapseMenu()
        } else {
            m.visibility = View.VISIBLE
            // Keep the expanded menu on screen when the bubble is near the bottom.
            bubbleRoot?.post {
                val root = bubbleRoot ?: return@post
                val p = bubbleParams ?: return@post
                val screenH = screenInfo().second
                if (p.y + root.height > screenH) {
                    p.y = (screenH - root.height).coerceAtLeast(0)
                    wm.updateViewLayout(root, p)
                }
            }
        }
    }

    private fun collapseMenu() {
        menu?.visibility = View.GONE
    }

    // ------------------------------------------------------------------ UI: area selector

    private fun showSelector(bmp: Bitmap) {
        val sv = SelectionView(this, bmp)
        val container = FrameLayout(this)
        container.addView(
            sv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val extract = pill("Extract text") {
            val crop = sv.crop()
            removeSelector()
            if (crop == null) {
                bmp.recycle()
                busy = false
                bubbleRoot?.visibility = View.VISIBLE
            } else {
                if (crop !== bmp) bmp.recycle()
                runOcr(crop)
            }
        }
        val whole = pill("Whole screen") {
            removeSelector()
            runOcr(bmp)
        }
        val cancel = pill("Cancel") {
            removeSelector()
            bmp.recycle()
            busy = false
            bubbleRoot?.visibility = View.VISIBLE
        }
        extract.isEnabled = false
        extract.alpha = 0.4f
        sv.onSelectionChanged = { has ->
            extract.isEnabled = has
            extract.alpha = if (has) 1f else 0.4f
        }
        for (b in listOf(cancel, whole, extract)) {
            (b.layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
            (b.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4)
            bar.addView(b)
        }
        container.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(56) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(container, params)
        selector = container
    }

    private fun removeSelector() {
        selector?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // already removed
            }
        }
        selector = null
    }

    // ------------------------------------------------------------------ notification / teardown

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Floating OCR button", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Screen OCR is running")
            .setContentText("Tap the floating OCR button to capture text")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        removeSelector()
        removePanel()
        bubbleRoot?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // already removed
            }
        }
        bubbleRoot = null
        display?.release()
        display = null
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        reader?.close()
        reader = null
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        projection = null
        bgThread.quitSafely()
        ocrPool.shutdown()
        super.onDestroy()
    }
}
