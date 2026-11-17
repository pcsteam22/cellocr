package com.screenocr.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged

/** Shows the extracted text in an editable field with copy / share / join-lines actions. */
class ResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "text"
    }

    private lateinit var edit: EditText
    private lateinit var stats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        edit = findViewById(R.id.resultText)
        stats = findViewById(R.id.statsText)

        edit.doAfterTextChanged { updateStats() }
        edit.setText(intent.getStringExtra(EXTRA_TEXT) ?: "")

        findViewById<android.view.View>(R.id.copyBtn).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("OCR text", edit.text.toString()))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.shareBtn).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, edit.text.toString())
            startActivity(Intent.createChooser(send, "Share text"))
        }
        findViewById<android.view.View>(R.id.reflowBtn).setOnClickListener {
            edit.setText(TextAnalyzer.reflow(edit.text.toString()))
        }
        findViewById<android.view.View>(R.id.closeBtn).setOnClickListener {
            finishAndRemoveTask()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        edit.setText(intent.getStringExtra(EXTRA_TEXT) ?: "")
    }

    private fun updateStats() {
        val s = TextAnalyzer.stats(edit.text.toString())
        stats.text = "${s.words} words · ${s.chars} characters · ${s.lines} lines · ${s.script}"
    }
}
