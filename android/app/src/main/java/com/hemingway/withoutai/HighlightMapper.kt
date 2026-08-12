package com.hemingway.withoutai

import android.graphics.RectF
import org.json.JSONArray

/** A character range to colour, as reported by the analysis engine. */
data class HighlightRange(val start: Int, val end: Int, val type: String)

/**
 * Turns character ranges plus per-character pixel boxes into blocks to paint.
 *
 * Kept free of Android view code so it can be reasoned about on its own — the
 * fiddly part is not drawing, it is that a range can wrap across lines and can
 * contain characters the system reports no box for.
 */
object HighlightMapper {

    /**
     * Characters whose vertical position differs by more than this are treated
     * as being on different lines. A fraction of line height rather than a
     * fixed pixel count, so it holds at any text size.
     */
    private const val SAME_LINE_TOLERANCE = 0.5f

    fun parseRanges(json: String?): List<HighlightRange> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                HighlightRange(o.getInt("start"), o.getInt("end"), o.getString("type"))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Maps ranges onto [charRects], one entry per character of the analysed
     * text, as returned by `EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY`.
     *
     * Entries are null for characters the system will not report a position for
     * — anything scrolled out of view — so those are skipped rather than
     * treated as zero, which would drag a highlight to the corner of the screen.
     * Each run of characters sharing a line becomes one merged rectangle.
     */
    fun toRects(ranges: List<HighlightRange>, charRects: Array<RectF?>): List<HighlightRect> {
        val out = mutableListOf<HighlightRect>()

        for (range in ranges) {
            val from = range.start.coerceAtLeast(0)
            val to = range.end.coerceAtMost(charRects.size)
            if (to <= from) continue

            var run: RectF? = null

            for (i in from until to) {
                val box = charRects[i]
                if (box == null || box.isEmpty) {
                    // A gap in reported positions ends the current run; the next
                    // visible character starts a fresh one.
                    run?.let { out.add(HighlightRect(it, range.type)) }
                    run = null
                    continue
                }

                val current = run
                if (current == null) {
                    run = RectF(box)
                } else if (isSameLine(current, box)) {
                    current.union(box)
                } else {
                    out.add(HighlightRect(current, range.type))
                    run = RectF(box)
                }
            }

            run?.let { out.add(HighlightRect(it, range.type)) }
        }

        return out
    }

    private fun isSameLine(run: RectF, box: RectF): Boolean {
        val tolerance = maxOf(run.height(), box.height()) * SAME_LINE_TOLERANCE
        return kotlin.math.abs(run.top - box.top) <= tolerance
    }
}
