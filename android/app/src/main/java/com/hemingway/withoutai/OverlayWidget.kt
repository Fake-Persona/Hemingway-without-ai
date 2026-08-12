package com.hemingway.withoutai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.TextView
import org.json.JSONObject

/**
 * The floating panel that sits above whatever app you are typing in.
 *
 * Analysis is not reimplemented here. An offscreen web view runs the same
 * `hemingway.html` the website ships and answers with counts, so the phone and
 * the site can never disagree about what counts as an adverb.
 */
class OverlayWidget(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var engine: WebView? = null
    private var isEngineReady = false

    /** Held so text arriving before the engine finishes loading is not lost. */
    private var pendingText: String? = null

    @SuppressLint("SetJavaScriptEnabled", "InflateParams", "ClickableViewAccessibility")
    fun show() {
        if (root != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_widget, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // NOT_FOCUSABLE is essential: without it the panel steals input and
            // you could not type in the app underneath it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 120
        }

        makeDraggable(view, params)

        // The engine web view is never added to the window — it exists purely to
        // run the analysis, so it has no size and is never drawn.
        engine = WebView(context).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    isEngineReady = true
                    pendingText?.let { update(it) }
                    pendingText = null
                }
            }
            loadUrl("file:///android_asset/hemingway.html")
        }

        windowManager.addView(view, params)
        root = view
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        engine?.destroy()
        engine = null
        isEngineReady = false
    }

    fun update(text: String) {
        val webView = engine ?: return
        if (!isEngineReady) {
            pendingText = text
            return
        }

        val literal = JSONObject.quote(text)
        webView.evaluateJavascript("window.hemingway.analyze($literal);") { encoded ->
            val json = decodeJsString(encoded) ?: return@evaluateJavascript
            runCatching { render(JSONObject(json)) }
        }
    }

    private fun render(result: JSONObject) {
        val view = root ?: return
        val totals = result.getJSONObject("totals")

        view.findViewById<TextView>(R.id.grade).text =
            context.getString(R.string.widget_grade, result.getInt("grade"))
        view.findViewById<TextView>(R.id.label).text = result.getString("label")
        view.findViewById<TextView>(R.id.counts).text = context.getString(
            R.string.widget_counts,
            totals.getInt("adverbs"),
            totals.getInt("passiveVoice"),
            totals.getInt("complex"),
            totals.getInt("hardSentences") + totals.getInt("veryHardSentences")
        )
    }

    /** Lets the panel be moved out of the way of whatever is underneath it. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun decodeJsString(encoded: String?): String? {
        if (encoded == null || encoded == "null") return null
        return runCatching { JSONObject("{\"v\":$encoded}").getString("v") }.getOrNull()
    }
}
