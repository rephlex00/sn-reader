package dev.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import dev.reader.R

/**
 * A printed sidehead: a tracked-caps label naming what follows.
 *
 * It comes in two forms, and which one is correct depends on the list beneath it — this is not a
 * style preference:
 *
 * * [Form.RULED] — the label sits *on* a 2dp INK rule, in a gap of paper, the way a printed
 *   sidehead does. Used wherever rows have no leaders: Type, Settings, Marks, Notes, the library
 *   list.
 * * [Form.PLAIN] — centred caps carrying the section on space alone, with no rule at all. Used in
 *   Contents, where every row already runs a dotted leader across it: a rule there would put two
 *   horizontal devices of similar weight on the same surface doing different jobs, and the leaders
 *   would lose.
 *
 * Drawn rather than composed from a nested layout: the ruled form needs the rule to pass *behind*
 * the label and stop, which as views is a FrameLayout, a rule, and a TextView with an opaque
 * background — three views and a measure pass for something that is two drawing calls.
 */
class SideheadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Which device this head uses. See the class note — it follows the list, not taste. */
    enum class Form { RULED, PLAIN }

    var label: String = ""
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    var form: Form = Form.RULED
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    // getDimension returns px directly, so none of this can be caught by the `Paint().apply`
    // density trap (inside that block `density` resolves to Paint's own 1.0).
    private val ruleHeightPx = resources.getDimension(R.dimen.rule_section)
    private val gapPx = resources.getDimension(R.dimen.space)

    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.reader_rule)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.reader_text_label)
        textSize = resources.getDimension(R.dimen.text_mark)
        letterSpacing = TRACKING_CAPS
        typeface = ResourcesCompat.getFont(context, R.font.literata) ?: Typeface.SERIF
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val metrics = textPaint.fontMetrics
        val height = (metrics.descent - metrics.ascent).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val text = label.uppercase()
        val metrics = textPaint.fontMetrics
        val baseline = (height - metrics.ascent - metrics.descent) / 2f

        when (form) {
            Form.PLAIN -> {
                val textWidth = textPaint.measureText(text)
                canvas.drawText(text, (width - textWidth) / 2f, baseline, textPaint)
            }
            Form.RULED -> {
                // The rule runs the full width behind the label, then the label is painted over
                // it on a paper ground — so the gap around the words is a hole in the rule rather
                // than two separately positioned rule segments.
                val ruleY = height / 2f - ruleHeightPx / 2f
                canvas.drawRect(0f, ruleY, width.toFloat(), ruleY + ruleHeightPx, rulePaint)

                val textWidth = textPaint.measureText(text)
                rulePaint.color = ContextCompat.getColor(context, R.color.paper)
                canvas.drawRect(0f, 0f, textWidth + gapPx, height.toFloat(), rulePaint)
                rulePaint.color = ContextCompat.getColor(context, R.color.reader_rule)

                canvas.drawText(text, 0f, baseline, textPaint)
            }
        }
    }

    private companion object {
        /** Matches `@dimen/tracking_caps`; letterSpacing is in ems, not a dimension. */
        const val TRACKING_CAPS = 0.20f
    }
}
