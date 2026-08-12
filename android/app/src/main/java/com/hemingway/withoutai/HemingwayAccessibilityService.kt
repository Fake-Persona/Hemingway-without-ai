package com.hemingway.withoutai

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the text field you are typing in, anywhere on the device, and keeps
 * the floating panel in step with it.
 *
 * This is the only route Android offers to feedback inside other apps that does
 * not depend on being handed text through a menu, which is why it reaches
 * editors that draw their own selection popup.
 *
 * It reads text from the focused node and nothing else. **Your text is never
 * logged, stored or transmitted** — the app declares no INTERNET permission, so
 * it structurally cannot be. The diagnostics in [report] deliberately carry only
 * a package name, a stage and character counts, never the text itself.
 */
class HemingwayAccessibilityService : AccessibilityService() {

    private var engine: AnalysisEngine? = null
    private var panel: OverlayWidget? = null

    /** Skips redundant work when an event repeats text that has not changed. */
    private var lastText: String? = null

    /** Last diagnostic line, so [report] only logs when the outcome changes. */
    private var lastReport: String? = null

    /**
     * Android may call this more than once on the same instance — on rebind, or
     * after a configuration change — so anything left from a previous
     * connection is torn down first. Without that, a second call abandoned the
     * old panel on screen and replaced the reference to it, so it could never
     * be removed.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        teardown()
        engine = AnalysisEngine(this)
        panel = OverlayWidget(this, ::selectInHostApp).also { it.show() }
    }

    // onDestroy is not guaranteed to arrive promptly when a service is switched
    // off, and toggling with an accessibility shortcut unbinds first, so the
    // panel is taken down here too rather than being left over the screen.
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    private fun teardown() {
        panel?.hide()
        engine?.destroy()
        panel = null
        engine = null
        // Cleared so a fresh connection re-renders immediately rather than
        // waiting for the text to differ from a previous session's.
        lastText = null
        lastReport = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> Unit

            // Focus moving means a different field, so the cached text no longer
            // describes what is in front of you. Without clearing it, moving
            // between two fields holding identical text kept the previous
            // field's positions and selected in the wrong place.
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> lastText = null

            else -> return
        }

        // Re-attach if the panel has gone missing. The window can be torn down
        // without this service hearing about it, and a returning false here is
        // free, so this doubles as a cheap recovery rather than needing the
        // service to be switched off and on by hand.
        if (panel?.show() == true) lastText = null

        val node = findFocusedTextNode()
        if (node == null) {
            report("no focused text node")
            return
        }

        val text = node.text?.toString().orEmpty()
        node.recycleCompat()

        if (text == lastText) return
        lastText = text

        report("analysing", "chars=${text.length}")
        engine?.analyze(text) { result -> panel?.render(result) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    /**
     * Selects an issue's text in the app underneath, so that app scrolls to it
     * and marks it with its own selection.
     *
     * This is how the panel points at a problem. Nothing here needs to know
     * where the text sits on screen, which is what made the previous
     * pixel-painting approach brittle: it depended on per-character positions
     * that not every app reports, and went stale as soon as you scrolled.
     *
     * The node is looked up fresh rather than reused from the event, since the
     * focus may well have moved between analysing and tapping.
     */
    private fun selectInHostApp(issue: Issue) {
        val node = findFocusedTextNode() ?: run {
            report("cannot select, nothing focused")
            return
        }

        val current = node.text?.toString().orEmpty()
        val range = locate(current, issue)
        if (range == null) {
            node.recycleCompat()
            report("phrase no longer present")
            return
        }

        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, range.first)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, range.last)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        node.recycleCompat()

        if (!ok) report("selection refused by app")
    }

    /**
     * Works out where an issue's phrase sits in the text *now*.
     *
     * Recorded offsets describe the text as it was when analysed, and analysis
     * is asynchronous, so anything typed in between shifts everything after the
     * caret. Selecting by the stored numbers landed slightly off after a few
     * keystrokes, and badly off after a line was added.
     *
     * The recorded position is still a good hint — it disambiguates a word that
     * appears several times — so it is checked first, then used to pick the
     * nearest occurrence if the text has moved underneath it.
     *
     * Returns first..last as an exclusive end, matching what ACTION_SET_SELECTION
     * expects, or null when the phrase has been edited away entirely.
     */
    private fun locate(current: String, issue: Issue): IntRange? {
        val phrase = issue.text
        if (phrase.isEmpty() || current.isEmpty()) return null

        // Unchanged since analysis: the recorded span still holds the phrase.
        if (issue.start >= 0 && issue.end <= current.length &&
            current.regionMatches(issue.start, phrase, 0, phrase.length)
        ) {
            return issue.start..issue.end
        }

        var best = -1
        var bestDistance = Int.MAX_VALUE
        var from = 0
        while (true) {
            val found = current.indexOf(phrase, from)
            if (found < 0) break
            val distance = kotlin.math.abs(found - issue.start)
            if (distance < bestDistance) {
                bestDistance = distance
                best = found
            }
            from = found + 1
        }

        return if (best < 0) null else best..(best + phrase.length)
    }

    /**
     * Finds the field being typed in.
     *
     * Deliberately more permissive than "is it editable". Apps rendering their
     * editor inside a WebView — Obsidian runs CodeMirror in one — expose the
     * focused element as a contenteditable that often does not set the editable
     * flag, and requiring it meant those apps were rejected outright.
     */
    private fun findFocusedTextNode(): AccessibilityNodeInfo? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return null

        if (focused.isEditable || !focused.text.isNullOrEmpty()) return focused

        focused.recycleCompat()
        return null
    }

    /**
     * Logs why a given app is or is not working, throttled to one line per
     * distinct outcome so typing does not flood the log:
     *
     *     adb logcat -s HemingwayProbe
     */
    private fun report(stage: String, extra: String = "") {
        val app = rootInActiveWindow?.packageName?.toString() ?: "unknown"
        val line = "$app: $stage $extra".trim()
        if (line == lastReport) return
        lastReport = line
        android.util.Log.i(PROBE_TAG, line)
    }

    private fun AccessibilityNodeInfo.recycleCompat() {
        // recycle() is deprecated and a no-op from API 33, but on older releases
        // skipping it exhausts the node pool.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            recycle()
        }
    }

    private companion object {
        const val PROBE_TAG = "HemingwayProbe"
    }
}
