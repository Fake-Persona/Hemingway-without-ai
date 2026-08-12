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

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = AnalysisEngine(this)
        panel = OverlayWidget(this, ::selectInHostApp).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> Unit
            else -> return
        }

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
        panel?.hide()
        engine?.destroy()
        panel = null
        engine = null
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

        val length = node.text?.length ?: 0
        val start = issue.start.coerceIn(0, length)
        val end = issue.end.coerceIn(start, length)

        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        node.recycleCompat()

        if (!ok) report("selection refused by app")
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
