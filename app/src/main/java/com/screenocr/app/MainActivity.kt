package com.screenocr.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: MaterialButton

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, OverlayService::class.java)
                        .putExtra(OverlayService.EXTRA_CODE, result.resultCode)
                        .putExtra(OverlayService.EXTRA_DATA, data)
                )
                toast("Floating button started. Open any app and tap it.")
                Handler(Looper.getMainLooper()).postDelayed({ moveTaskToBack(true) }, 400)
            } else {
                toast("Screen capture permission was not granted")
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestProjection() // continue even if notifications were declined
        }

    private val overlaySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateUi()
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) ocrFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        toggle = findViewById(R.id.toggleBtn)

        toggle.setOnClickListener {
            if (OverlayService.running) {
                startService(
                    Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
                )
                Handler(Looper.getMainLooper()).postDelayed({ updateUi() }, 400)
            } else {
                begin()
            }
        }

        val group = findViewById<RadioGroup>(R.id.langGroup)
        group.setOnCheckedChangeListener { _, id ->
            Prefs.setLang(
                this,
                when (id) {
                    R.id.langEng -> Prefs.LANG_ENG
                    R.id.langUrd -> Prefs.LANG_URD
                    else -> Prefs.LANG_BOTH
                }
            )
        }

        findViewById<MaterialSwitch>(R.id.autoCopySwitch).apply {
            isChecked = Prefs.autoCopy(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setAutoCopy(this@MainActivity, checked) }
        }

        findViewById<MaterialButton>(R.id.pickImageBtn).setOnClickListener {
            pickImage.launch("image/*")
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val running = OverlayService.running
        status.text = if (running) {
            "Running. Open any app and tap the floating OCR button."
        } else {
            "Stopped"
        }
        toggle.text = if (running) "Stop floating button" else "Start floating button"

        val checked = when (Prefs.lang(this)) {
            Prefs.LANG_ENG -> R.id.langEng
            Prefs.LANG_URD -> R.id.langUrd
            else -> R.id.langBoth
        }
        findViewById<RadioGroup>(R.id.langGroup).check(checked)
    }

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow “Display over other apps” for Screen OCR, then come back and tap Start again")
            overlaySettingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // ------------------------------------------------------------ OCR from a picked image

    private fun ocrFromUri(uri: Uri) {
        toast(getString(R.string.extracting))
        Thread {
            val text: String? = try {
                val bmp = decode(uri) ?: throw IllegalStateException("decode failed")
                val raw = OcrEngine.get(this).recognize(bmp, Prefs.lang(this))
                bmp.recycle()
                TextAnalyzer.clean(raw)
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                when {
                    text == null -> toast("Could not read this image")
                    text.isBlank() -> toast("No text found")
                    else -> startActivity(
                        Intent(this, ResultActivity::class.java)
                            .putExtra(ResultActivity.EXTRA_TEXT, text)
                    )
                }
            }
        }.start()
    }

    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
