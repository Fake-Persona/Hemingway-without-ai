package com.hemingway.withoutai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.WindowManager

/** A block of colour to paint, already in screen coordinates. */
data class HighlightRect(val rect: RectF, val type: String)

/**
 * Paints highlight blocks over words in whatever app is in front.
 *
 * A full-screen, untouchable window that never intercepts input — it only
 * draws. Coordinates arrive from the accessibility API already in screen space,
 * so the window is laid out across the whole screen including system bars and
 * no conversion is needed.
 */
class HighlightOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: HighlightView? = null

    fun show() {
        if (view != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // NOT_TOUCHABLE is what makes this safe to lay over another app:
            // every touch passes straight through to whatever is underneath.
            // LAYOUT_IN_SCREEN and NO_LIMITS keep the window's coordinate space
            // identical to the screen coordinates the accessibility API reports.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        view = HighlightView(context).also { windowManager.addView(it, params) }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    fun draw(rects: List<HighlightRect>) {
        view?.setRects(rects)
    }

    fun clear() = draw(emptyList())

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private class HighlightView(context: Context) : View(context) {

        private var rects: List<HighlightRect> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val location = IntArray(2)

        fun setRects(value: List<HighlightRect>) {
            rects = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // The accessibility API reports positions in screen coordinates,
            // but this view's own origin is wherever the window manager put it
            // — typically below the status bar, which pushed every highlight
            // down by that height, about two lines of text.
            //
            // Asking the view where it actually is, rather than assuming it
            // starts at the top of the screen, corrects for the status bar,
            // display cutouts and gesture insets without hardcoding any of them.
            getLocationOnScreen(location)
            canvas.save()
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())

            for (highlight in rects) {
                // Translucent so the text underneath stays readable — this sits
                // on top of the words rather than behind them, which is the
                // opposite of how the website layers it.
                paint.color = colorFor(highlight.type)
                canvas.drawRoundRect(highlight.rect, CORNER, CORNER, paint)
            }

            canvas.restore()
        }

        private fun colorFor(type: String): Int = when (type) {
            "adverb", "qualifier" -> Color.argb(ALPHA, 0xC4, 0xE3, 0xF3)
            "passive" -> Color.argb(ALPHA, 0xC4, 0xED, 0x9D)
            "complex" -> Color.argb(ALPHA, 0xE3, 0xB7, 0xE8)
            "hardSentence" -> Color.argb(SENTENCE_ALPHA, 0xF7, 0xEC, 0xB5)
            "veryHardSentence" -> Color.argb(SENTENCE_ALPHA, 0xE4, 0xB9, 0xB9)
            else -> Color.TRANSPARENT
        }

        private companion object {
            const val CORNER = 3f
            const val ALPHA = 120

            // Sentence highlights span whole lines and stack under the
            // word-level ones, so they are fainter to stay legible.
            const val SENTENCE_ALPHA = 70
        }
    }
}
