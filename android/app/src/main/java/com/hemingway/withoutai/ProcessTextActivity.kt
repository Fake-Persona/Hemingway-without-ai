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
 * Shows the analysis for a piece of text handed over by another app.
 *
 * Reached three ways, because no single one covers every app:
 *
 *  - **Selection toolbar** (`PROCESS_TEXT`) — the good path, and the only one
 *    that can write edited text back. Works wherever the app uses Android's own
 *    selection menu, e.g. WhatsApp.
 *  - **Share sheet** (`SEND`) — for apps that draw their own selection menu and
 *    so never show a `PROCESS_TEXT` entry, but do offer Share.
 *  - **Opened directly** — [HomeActivity] shows the same editor to paste into,
 *    which is the last resort for apps offering neither.
 *
 * The analysis engine is the same `hemingway.html` the website ships, bundled
 * as an asset and loaded over `file://`. Nothing is reimplemented here, so the
 * phone cannot drift from the web version, and no network permission is needed.
 */
open class ProcessTextActivity : Activity() {

    private lateinit var webView: WebView

    /**
     * Only the selection-toolbar path can return text to where it came from.
     * Shared text has no channel back, so Replace is hidden rather than left to
     * fail silently.
     */
    private var canReplace = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_process_text)

        webView = findViewById(R.id.editor)
        val replaceButton = findViewById<Button>(R.id.replace)

        val incoming = readIncomingText()
        canReplace = incoming.canWriteBack
        replaceButton.visibility = if (canReplace) View.VISIBLE else View.GONE
        replaceButton.setOnClickListener { returnEditedText() }

        // JavaScript is required: the page *is* the application. It only ever
        // loads a bundled local asset, never remote content, so this does not
        // expose the app to third-party script.
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // Push the text in only after the page's own scripts have run,
        // otherwise window.hemingway does not exist yet.
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (incoming.text.isNotEmpty()) setEditorText(incoming.text)
            }
        }

        webView.loadUrl("file:///android_asset/hemingway.html")
    }

    private data class Incoming(val text: String, val canWriteBack: Boolean)

    private fun readIncomingText(): Incoming = when (intent?.action) {
        Intent.ACTION_PROCESS_TEXT -> {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            Incoming(text, canWriteBack = !readOnly)
        }
        Intent.ACTION_SEND -> Incoming(
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty(),
            canWriteBack = false
        )
        else -> Incoming("", canWriteBack = false)
    }

    /**
     * Hands text to the page. JSONObject.quote does the escaping, so quotes,
     * backslashes and newlines in the text cannot break out of the string
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
