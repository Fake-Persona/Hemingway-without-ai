package com.hemingway.withoutai

import android.accessibilityservice.AccessibilityService
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the text field you are typing in, anywhere on the device, and keeps
 * both overlays in step with it.
 *
 * This is the only route Android offers to feedback inside other apps that does
 * not depend on being handed text through a menu, which is why it works in
 * editors that draw their own selection popup.
 *
 * It reads text from the focused node and nothing else. Nothing is logged,
 * stored or transmitted, and the app declares no INTERNET permission, so it
 * structurally cannot be.
 */
class HemingwayAccessibilityService : AccessibilityService() {

    private var engine: AnalysisEngine? = null
    private var panel: OverlayWidget? = null
    private var highlights: HighlightOverlay? = null

    /** Skips redundant work when an event repeats text that has not changed. */
    private var lastText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = AnalysisEngine(this)
        panel = OverlayWidget(this).also { it.show() }
        highlights = HighlightOverlay(this).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> Unit
            else -> return
        }

        val node = findFocusedEditable()
        if (node == null) {
            // Nothing focused: stale colour over unrelated content would be
            // worse than none.
            highlights?.clear()
            lastText = null
            return
        }

        val text = node.text?.toString().orEmpty()
        if (text == lastText) {
            node.recycleCompat()
            return
        }
        lastText = text

        engine?.stats(text) { stats -> panel?.render(stats) }
        paintHighlights(node, text)
        node.recycleCompat()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        panel?.hide()
        highlights?.hide()
        engine?.destroy()
        panel = null
        highlights = null
        engine = null
        super.onDestroy()
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (focused.isEditable) focused else {
            focused.recycleCompat()
            null
        }
    }

    /**
     * Asks the system where each character sits on screen, then paints the
     * analysis over those positions.
     *
     * The per-character positions come from `refreshWithExtraData`, which is
     * only available from API 26 and is not honoured by every app — text drawn
     * by a custom renderer may report nothing. When that happens the colour is
     * simply cleared and the summary panel carries on, rather than the feature
     * appearing broken.
     */
    private fun paintHighlights(node: AccessibilityNodeInfo, text: String) {
        val overlay = highlights ?: return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || text.isEmpty()) {
            overlay.clear()
            return
        }

        // Requesting positions is proportional to length, and only what is on
        // screen can be painted anyway, so long documents are capped.
        val length = minOf(text.length, MAX_CHARACTERS)
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
        }

        val refreshed = node.refreshWithExtraData(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
            args
        )
        if (!refreshed) {
            overlay.clear()
            return
        }

        val parcelables = node.extras
            ?.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
        if (parcelables.isNullOrEmpty()) {
            overlay.clear()
            return
        }

        val charRects = Array(parcelables.size) { parcelables[it] as? RectF }

        engine?.highlights(text) { ranges ->
            overlay.draw(HighlightMapper.toRects(ranges, charRects))
        }
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
        const val MAX_CHARACTERS = 2000
    }
}
