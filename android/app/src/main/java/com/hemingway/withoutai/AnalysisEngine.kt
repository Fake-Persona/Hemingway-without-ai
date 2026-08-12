package com.hemingway.withoutai

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Runs the website's analysis, offscreen.
 *
 * The engine is deliberately not ported to Kotlin. This drives the same
 * `hemingway.html` the site ships, in a web view that is never attached to any
 * window, so the phone and the website can never disagree about what counts as
 * an adverb. Loading a bundled asset means no network, which is why the app can
 * declare no INTERNET permission.
 */
class AnalysisEngine(context: Context) {

    private var isReady = false

    /** Work arriving before the page finishes loading, replayed once it has. */
    private var pending: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    private val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                isReady = true
                pending?.invoke()
                pending = null
            }
        }
        loadUrl("file:///android_asset/hemingway.html")
    }

    fun stats(text: String, onResult: (JSONObject) -> Unit) =
        call("analyze", text) { json -> runCatching { onResult(JSONObject(json)) } }

    fun highlights(text: String, onResult: (List<HighlightRange>) -> Unit) =
        call("highlights", text) { json -> onResult(HighlightMapper.parseRanges(json)) }

    fun destroy() {
        pending = null
        webView.destroy()
    }

    private fun call(fn: String, text: String, onResult: (String) -> Unit) {
        val run = {
            // JSONObject.quote escapes the text, so quotes, backslashes and
            // newlines cannot break out of the string literal and become script.
            val literal = JSONObject.quote(text)
            webView.evaluateJavascript("window.hemingway.$fn($literal);") { encoded ->
                decode(encoded)?.let(onResult)
            }
        }
        if (isReady) run() else pending = run
    }

    /** evaluateJavascript returns a JSON-encoded value, so strings arrive quoted. */
    private fun decode(encoded: String?): String? {
        if (encoded == null || encoded == "null") return null
        return runCatching { JSONObject("{\"v\":$encoded}").getString("v") }.getOrNull()
    }
}
