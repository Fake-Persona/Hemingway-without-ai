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
import android.widget.TextView
import org.json.JSONObject

/**
 * The small summary panel that floats above whatever app you are typing in.
 *
 * Shows totals only. The colour over individual words is drawn separately by
 * [HighlightOverlay]; this is the part that has to stay readable and out of the
 * way, so it is draggable and deliberately small.
 */
class OverlayWidget(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null

    @SuppressLint("InflateParams")
    fun show() {
        if (root != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_widget, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // NOT_FOCUSABLE is essential: without it the panel takes input and
            // you could not type in the app underneath. NOT_TOUCH_MODAL lets
            // touches outside the panel reach that app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 120
        }

        makeDraggable(view, params)
        windowManager.addView(view, params)
        root = view
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    fun render(result: JSONObject) {
        val view = root ?: return
        val totals = result.optJSONObject("totals") ?: return

        view.findViewById<TextView>(R.id.grade).text =
            context.getString(R.string.widget_grade, result.optInt("grade"))
        view.findViewById<TextView>(R.id.label).text = result.optString("label")
        view.findViewById<TextView>(R.id.counts).text = context.getString(
            R.string.widget_counts,
            totals.optInt("adverbs"),
            totals.optInt("passiveVoice"),
            totals.optInt("complex"),
            totals.optInt("hardSentences") + totals.optInt("veryHardSentences")
        )
    }

    /** Lets the panel be moved off whatever it is covering. */
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
}
