package com.hemingway.withoutai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.Toast
import org.json.JSONObject

/**
 * Entry point for the text-selection toolbar: select text in any app, tap
 * "Hemingway", read the analysis, optionally send an edited version back.
 *
 * The analysis engine is the same `hemingway.html` the website ships — one
 * self-contained file, bundled as an asset and loaded over `file://`. Nothing
 * is ported or reimplemented here, so the phone can never drift from the web
 * version, and the app needs no network permission to do its job.
 */
class ProcessTextActivity : Activity() {

    private lateinit var webView: WebView

    /**
     * When the host marks the selection read-only we cannot write back, so the
     * Replace button is hidden rather than left to fail silently.
     */
    private var isReadOnly = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_process_text)

        webView = findViewById(R.id.editor)
        val replaceButton = findViewById<Button>(R.id.replace)

        val selectedText = intent
            .getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            .orEmpty()

        isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        replaceButton.visibility = if (isReadOnly) View.GONE else View.VISIBLE
        replaceButton.setOnClickListener { returnEditedText() }

        // JavaScript is required: the page *is* the application. It only ever
        // loads a bundled local asset, never remote content, so this does not
        // expose the app to third-party script.
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // Push the selection in only after the page's own scripts have run,
        // otherwise window.hemingway does not exist yet.
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (selectedText.isNotEmpty()) setEditorText(selectedText)
            }
        }

        webView.loadUrl("file:///android_asset/hemingway.html")
    }

    /**
     * Hands text to the page. JSONObject.quote does the escaping, so quotes,
     * backslashes and newlines in the selection cannot break out of the string
     * literal and turn user text into executable script.
     */
    private fun setEditorText(text: String) {
        val literal = JSONObject.quote(text)
        webView.evaluateJavascript("window.hemingway.setText($literal);", null)
    }

    private fun returnEditedText() {
        webView.evaluateJavascript("window.hemingway.getText();") { encoded ->
            // evaluateJavascript hands back a JSON-encoded value, so a returned
            // string arrives still quoted and escaped and must be decoded.
            val edited = decodeJsString(encoded)
            if (edited == null) {
                Toast.makeText(this, R.string.could_not_read_text, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, edited))
            finish()
        }
    }

    private fun decodeJsString(encoded: String?): String? {
        if (encoded == null || encoded == "null") return null
        return runCatching { JSONObject("{\"v\":$encoded}").getString("v") }.getOrNull()
    }
}
