package com.hemingway.withoutai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** One thing worth fixing, and where it sits in the text. */
data class Issue(val start: Int, val end: Int, val type: String, val text: String)

/**
 * The floating panel: score, totals, and a tappable list of what it found.
 *
 * Tapping an issue asks the service to select that range in the app underneath,
 * which is how the panel points at a problem without knowing anything about
 * where it is on screen. An earlier version painted colour over the words
 * themselves; that needed per-character pixel boxes, went stale the moment you
 * scrolled, and only worked in some apps.
 */
class OverlayWidget(
    private val context: Context,
    private val onIssueSelected: (Issue) -> Unit
) {

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
            // NOT_FOCUSABLE keeps the keyboard with the app underneath, so you
            // can still type while the panel is up; taps inside the panel are
            // still delivered to it. NOT_TOUCH_MODAL lets taps outside through.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 120
        }

        // Only the header drags, so dragging never swallows a tap on a row.
        makeDraggable(view.findViewById<View>(R.id.header), view, params)

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

        renderIssues(view.findViewById(R.id.issues), parseIssues(result))
        view.findViewById<TextView>(R.id.hint).visibility =
            if (parseIssues(result).isEmpty()) View.GONE else View.VISIBLE
    }

    private fun parseIssues(result: JSONObject): List<Issue> {
        val array = result.optJSONArray("issues") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Issue(o.optInt("start"), o.optInt("end"), o.optString("type"), o.optString("text"))
        }
    }

    private fun renderIssues(container: LinearLayout, issues: List<Issue>) {
        container.removeAllViews()

        for (issue in issues.take(MAX_ISSUES)) {
            container.addView(issueRow(issue))
        }
    }

    private fun issueRow(issue: Issue): View {
        val row = TextView(context).apply {
            text = label(issue)
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(colorFor(issue.type))
            }
            isClickable = true
            setOnClickListener { onIssueSelected(issue) }
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(3) }
        row.layoutParams = params
        return row
    }

    /**
     * Sentence-level findings are whole sentences, so they are trimmed to a
     * recognisable opening rather than filling the panel.
     */
    private fun label(issue: Issue): String {
        val clean = issue.text.replace(Regex("\\s+"), " ").trim()
        val shown = if (clean.length > MAX_LABEL) clean.take(MAX_LABEL - 1) + "…" else clean
        val kind = context.getString(
            when (issue.type) {
                "adverb" -> R.string.kind_adverb
                "qualifier" -> R.string.kind_qualifier
                "passive" -> R.string.kind_passive
                "complex" -> R.string.kind_complex
                "hardSentence" -> R.string.kind_hard
                "veryHardSentence" -> R.string.kind_very_hard
                else -> R.string.kind_other
            }
        )
        return "$shown  ·  $kind"
    }

    private fun colorFor(type: String): Int = when (type) {
        "adverb", "qualifier" -> Color.parseColor("#C4E3F3")
        "passive" -> Color.parseColor("#C4ED9D")
        "complex" -> Color.parseColor("#E3B7E8")
        "hardSentence" -> Color.parseColor("#F7ECB5")
        "veryHardSentence" -> Color.parseColor("#E4B9B9")
        else -> Color.LTGRAY
    }

    /** Dragging is bound to the header so it cannot swallow taps on the rows. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View, window: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        handle.setOnTouchListener { _, event ->
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
                    runCatching { windowManager.updateViewLayout(window, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private companion object {
        const val MAX_ISSUES = 30
        const val MAX_LABEL = 34
    }
}
