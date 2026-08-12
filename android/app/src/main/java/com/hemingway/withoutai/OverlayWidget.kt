package com.hemingway.withoutai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** One thing worth fixing, and where it sits in the text. */
data class Issue(val start: Int, val end: Int, val type: String, val text: String)

/**
 * The floating panel: a small score chip that expands into a tappable list of
 * what it found.
 *
 * It sits on top of someone's writing, so its resting state is deliberately
 * tiny — a grade and a count — and the list only appears when asked for. An
 * earlier version showed every issue permanently, which covered most of the
 * screen as soon as a paragraph had a few problems, hiding the very text it
 * was describing.
 *
 * Tapping an issue asks the service to select that range in the app underneath,
 * which is how the panel points at a problem without knowing where it is on
 * screen.
 */
class OverlayWidget(
    private val context: Context,
    private val onIssueSelected: (Issue) -> Unit
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var isExpanded = false

    /** Held so the list can be rebuilt when expanded without re-analysing. */
    private var lastIssues: List<Issue> = emptyList()

    @SuppressLint("InflateParams")
    fun show() {
        if (root != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_widget, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // NOT_FOCUSABLE keeps the keyboard with the app underneath, so you
            // can still type while the panel is up; taps inside it still land.
            // NOT_TOUCH_MODAL lets taps outside through.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(80)
        }

        bindHeader(view, params)
        windowManager.addView(view, params)
        root = view
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        isExpanded = false
    }

    fun render(result: JSONObject) {
        val view = root ?: return
        val totals = result.optJSONObject("totals") ?: return
        lastIssues = parseIssues(result)

        view.findViewById<TextView>(R.id.grade).text =
            context.getString(R.string.widget_grade, result.optInt("grade"))

        val issueCount = totals.optInt("adverbs") + totals.optInt("passiveVoice") +
            totals.optInt("complex") + totals.optInt("hardSentences") +
            totals.optInt("veryHardSentences")

        view.findViewById<TextView>(R.id.summary).text = if (issueCount == 0) {
            context.getString(R.string.widget_no_issues)
        } else {
            context.resources
                .getQuantityString(R.plurals.widget_issue_count, issueCount, issueCount)
        }

        view.findViewById<TextView>(R.id.breakdown).text = breakdown(totals)

        if (isExpanded) renderIssues(view.findViewById(R.id.issues))
    }

    /**
     * Every category, every time, including the ones at zero.
     *
     * Zeros are shown as numbers rather than being dropped or ticked: a "0"
     * reads as a checked category that came back clean, and keeps the line in
     * the same shape between edits, so a count changing is easy to spot.
     */
    private fun breakdown(totals: JSONObject): String {
        val categories = listOf(
            totals.optInt("adverbs") to R.string.count_adverbs,
            totals.optInt("passiveVoice") to R.string.count_passive,
            totals.optInt("complex") to R.string.count_complex,
            (totals.optInt("hardSentences") + totals.optInt("veryHardSentences"))
                to R.string.count_hard
        )

        return categories.joinToString("   ") { (count, nameRes) ->
            context.getString(R.string.breakdown_found, count, context.getString(nameRes))
        }
    }

    private fun setExpanded(expanded: Boolean) {
        val view = root ?: return
        isExpanded = expanded

        view.findViewById<View>(R.id.detail).visibility =
            if (expanded) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.chevron).setText(
            if (expanded) R.string.chevron_expanded else R.string.chevron_collapsed
        )

        if (expanded) renderIssues(view.findViewById(R.id.issues))
    }

    private fun parseIssues(result: JSONObject): List<Issue> {
        val array = result.optJSONArray("issues") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Issue(o.optInt("start"), o.optInt("end"), o.optString("type"), o.optString("text"))
        }
    }

    private fun renderIssues(container: LinearLayout) {
        container.removeAllViews()
        for (issue in lastIssues.take(MAX_ISSUES)) {
            container.addView(issueRow(issue))
        }
    }

    /**
     * A neutral row with a small coloured dot, rather than the website's full
     * colour wash. A wash is heavy at this size, and the pastels that work over
     * a white page are unreadable on a dark phone.
     */
    private fun issueRow(issue: Issue): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(context.getColor(R.color.row_bg))
            }
            isClickable = true
            setOnClickListener { onIssueSelected(issue) }
        }

        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(dotColorFor(issue.type)))
            }
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
        }

        // Two views rather than one string: the phrase is bounded and truncates,
        // while the kind is never allowed to. Ellipsizing a single combined
        // label would eat "· passive" off the end — exactly the part that says
        // what is wrong.
        val phrase = TextView(context).apply {
            text = trimmedPhrase(issue)
            setTextColor(context.getColor(R.color.panel_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            maxWidth = dp(148)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(7) }
        }

        val kind = TextView(context).apply {
            text = kindName(issue.type)
            setTextColor(context.getColor(R.color.panel_text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }

        row.addView(dot)
        row.addView(phrase)
        row.addView(kind)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(3) }
        return row
    }

    /**
     * The offending words, collapsed onto one line. Sentence findings are whole
     * sentences, so they are cut to a recognisable opening rather than filling
     * the panel; the width cap on the view handles the rest.
     */
    private fun trimmedPhrase(issue: Issue): String {
        val clean = issue.text.replace(Regex("\\s+"), " ").trim()
        return if (clean.length > MAX_LABEL) clean.take(MAX_LABEL - 1) + "…" else clean
    }

    private fun kindName(type: String): String = context.getString(
        when (type) {
            "adverb" -> R.string.kind_adverb
            "qualifier" -> R.string.kind_qualifier
            "passive" -> R.string.kind_passive
            "complex" -> R.string.kind_complex
            "hardSentence" -> R.string.kind_hard
            "veryHardSentence" -> R.string.kind_very_hard
            else -> R.string.kind_other
        }
    )

    private fun dotColorFor(type: String): Int = when (type) {
        "adverb", "qualifier" -> R.color.dot_adverb
        "passive" -> R.color.dot_passive
        "complex" -> R.color.dot_complex
        "hardSentence" -> R.color.dot_hard
        "veryHardSentence" -> R.color.dot_very_hard
        else -> R.color.panel_text_dim
    }

    /**
     * The header both drags and toggles, told apart by how far the finger moved:
     * under the system's touch slop it is a tap, beyond it a drag. Splitting
     * them across two controls would mean more panel, which is what this change
     * is trying to avoid.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindHeader(view: View, params: WindowManager.LayoutParams) {
        val handle = view.findViewById<View>(R.id.header)
        val slop = ViewConfiguration.get(context).scaledTouchSlop

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragged && kotlin.math.hypot(dx, dy) > slop) dragged = true
                    if (dragged) {
                        // Gravity is TOP|END, so x grows leftwards from the right edge.
                        params.x = (startX - dx).toInt()
                        params.y = (startY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) setExpanded(!isExpanded)
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
        const val MAX_LABEL = 30
    }
}
