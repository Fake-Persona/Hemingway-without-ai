package com.hemingway.withoutai

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the text field you are currently typing in, anywhere on the device,
 * and keeps the floating widget updated.
 *
 * This is the only route Android offers to as-you-type feedback inside other
 * apps. It reads text from the focused node; it does not log, store or transmit
 * anything, and the app declares no INTERNET permission, so it structurally
 * cannot.
 */
class HemingwayAccessibilityService : AccessibilityService() {

    private var widget: OverlayWidget? = null

    /** Avoids redundant re-analysis when an event repeats the text unchanged. */
    private var lastText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        widget = OverlayWidget(this).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> Unit
            else -> return
        }

        val node = findFocusedEditable() ?: return
        val text = node.text?.toString().orEmpty()

        if (text == lastText) {
            node.recycleCompat()
            return
        }
        lastText = text

        widget?.update(text)
        probeCharacterBounds(node, text)
        node.recycleCompat()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        widget?.hide()
        widget = null
        super.onDestroy()
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (focused.isEditable) focused else { focused.recycleCompat(); null }
    }

    /**
     * One-off diagnostic for a question that decides the next design step:
     * can we obtain per-character pixel rectangles for text inside another
     * app's field?
     *
     * If yes, highlights can be painted directly over your words. If no, the
     * widget can only list the issues it found. This is expected to work for
     * standard TextView-backed fields and to fail for custom-rendered text
     * (Flutter, some Compose). Rather than guess, it reports what actually
     * happens on real apps:
     *
     *     adb logcat -s HemingwayProbe
     *
     * Costs one extra call on the first event per field and is skipped
     * thereafter, so it does not affect typing latency.
     */
    private fun probeCharacterBounds(node: AccessibilityNodeInfo, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (text.isEmpty() || probedPackages.contains(currentPackage())) return
        probedPackages.add(currentPackage())

        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
                minOf(text.length, 20)
            )
        }

        val ok = node.refreshWithExtraData(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
            args
        )
        val rects = node.extras
            ?.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)

        Log.i(
            PROBE_TAG,
            "package=${currentPackage()} refreshOk=$ok rects=${rects?.size ?: -1} " +
                "-> inline highlighting ${if ((rects?.size ?: 0) > 0) "POSSIBLE" else "NOT available"}"
        )
    }

    private fun currentPackage(): String = rootInActiveWindow?.packageName?.toString() ?: "unknown"

    private fun AccessibilityNodeInfo.recycleCompat() {
        // recycle() is deprecated and a no-op from API 33; calling it on older
        // releases still matters to avoid exhausting the node pool.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            recycle()
        }
    }

    private companion object {
        const val PROBE_TAG = "HemingwayProbe"
        val probedPackages = mutableSetOf<String>()
    }
}
