package dev.reader.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import dev.reader.R

/**
 * The one control shape in the app: a row of bordered cells, of which exactly one is chosen and
 * drawn **reversed** — INK ground, PAPER label.
 *
 * A font picker, a margin setting, a page-turn mode and a plain boolean are all this control. That
 * is the point of it: the reader learns one shape and then only ever reads which cell is filled.
 * It replaces both the old `AaOption` outline (an outline reads as a *button* everywhere else in
 * Android, so it was ambiguous about current-value versus about-to-press) and `ToggleSwitchView`
 * (a pill switch is another platform's language, and nine of them in a column was the Display
 * sheet's actual defect).
 *
 * **Nothing animates.** There is no state list, no ripple, no pressed appearance and no transition
 * between chosen and unchosen: [choose] swaps two static drawables and the single e-ink redraw that
 * follows the tap is itself the feedback. This is the same constraint that makes [PageView] and
 * [ChapterScrubberView] hand-drawn.
 *
 * **Disabled removes the border entirely** rather than greying it. A bordered-but-grey control
 * looks broken; a borderless MIST label simply stops looking tappable, and the caller states the
 * reason underneath (see [SectionedSurface.row]). Enabling it draws the border in and darkens the
 * labels in one redraw — nothing moves, so nothing ghosts.
 */
class CellRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Called with the index of a cell the reader taps. Never fired for the already-chosen cell. */
    var onChoice: ((Int) -> Unit)? = null

    private var chosenIndex: Int = -1
    private val cells = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        // The group's own border, and the same weight between cells, so the control reads as one
        // ruled object rather than a run of separate boxes.
        background = ContextCompat.getDrawable(context, R.drawable.cell_group)
        dividerDrawable = ContextCompat.getDrawable(context, R.drawable.cell_divider)
        showDividers = SHOW_DIVIDER_MIDDLE
        minimumHeight = resources.getDimensionPixelSize(R.dimen.cell_height)
    }

    /**
     * Fills the row with [labels] and marks [chosen].
     *
     * [style] decides the label's voice, not its behaviour: [CellStyle.MARK] is the tracked-caps
     * form for word labels (ON / OFF, COVERS / LIST), [CellStyle.SPECIMEN] sets the label in the
     * reading face at [specimenSizesSp] so a size or a typeface is chosen by *looking* at it — which
     * is what removes "36px" from the interface.
     *
     * Cells share the row equally when [equalWidths]; a settings row instead sizes each to its
     * label so the group sits hard against the right margin.
     */
    fun setCells(
        labels: List<String>,
        chosen: Int,
        style: CellStyle = CellStyle.MARK,
        equalWidths: Boolean = true,
        specimenSizesSp: List<Float> = emptyList(),
    ) {
        removeAllViews()
        cells.clear()
        chosenIndex = chosen

        labels.forEachIndexed { index, label ->
            val cell = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                includeFontPadding = false
                // A cell label is one line by construction. If it will not fit, the group is too
                // narrow and that is a layout to fix, not a word to break.
                maxLines = 1
                isClickable = true
                minWidth = resources.getDimensionPixelSize(R.dimen.cell_min_width)
                minHeight = resources.getDimensionPixelSize(R.dimen.cell_height)
                val padH = resources.getDimensionPixelSize(R.dimen.cell_padding_h)
                setPadding(padH, 0, padH, 0)
                typeface = ResourcesCompat.getFont(context, R.font.literata)

                when (style) {
                    CellStyle.MARK -> {
                        isAllCaps = true
                        letterSpacing = TRACKING_CAPS
                        setTextSize(
                            TypedValue.COMPLEX_UNIT_PX,
                            resources.getDimension(R.dimen.text_mark),
                        )
                    }
                    CellStyle.SPECIMEN -> {
                        val sp = specimenSizesSp.getOrNull(index)
                        if (sp != null) {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                        } else {
                            setTextSize(
                                TypedValue.COMPLEX_UNIT_PX,
                                resources.getDimension(R.dimen.text_row),
                            )
                        }
                    }
                }

                setOnClickListener {
                    if (index != chosenIndex) onChoice?.invoke(index)
                }
            }

            // WRAP_CONTENT *with* a weight, never 0dp with a weight. A weighted child of width 0
            // inside a wrap_content group is measured against a remaining space of zero, so every
            // cell collapses to its minWidth and a label wider than that wraps: NORMAL became
            // "NORM AL", FASTER became "FASTE R". Measuring the label first and then distributing
            // the slack equally gives cells that are both equal and wide enough for their text.
            val params = if (equalWidths) {
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, 1f)
            } else {
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            }
            addView(cell, params)
            cells += cell
        }

        applyAppearance()
    }

    /**
     * Moves the fill to [index]. Cheap enough to call on every pref write: it repaints two cells,
     * and the caller's own redraw covers it.
     */
    fun choose(index: Int) {
        if (index == chosenIndex) return
        chosenIndex = index
        applyAppearance()
    }

    /** The currently filled cell, or -1 before [setCells]. */
    fun chosen(): Int = chosenIndex

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // The border is the thing that says "tappable", so a disabled group loses it outright.
        background = if (enabled) {
            ContextCompat.getDrawable(context, R.drawable.cell_group)
        } else {
            null
        }
        showDividers = if (enabled) SHOW_DIVIDER_MIDDLE else SHOW_DIVIDER_NONE
        cells.forEach { it.isClickable = enabled }
        applyAppearance()
    }

    private fun applyAppearance() {
        cells.forEachIndexed { index, cell ->
            val isChosen = index == chosenIndex
            when {
                // MIST is legal here: a disabled label carries nothing you need, and at 13sp caps
                // or 20sp it is above the value's floor.
                !isEnabled -> {
                    cell.background = null
                    cell.setTextColor(ContextCompat.getColor(context, R.color.mist))
                }
                isChosen -> {
                    cell.background = ContextCompat.getDrawable(context, R.drawable.cell_chosen)
                    cell.setTextColor(ContextCompat.getColor(context, R.color.paper))
                }
                else -> {
                    cell.background = null
                    // IRON rather than INK: an unchosen label is a label, and letting it sit at
                    // full black would make every cell read as chosen at a glance.
                    cell.setTextColor(ContextCompat.getColor(context, R.color.iron))
                }
            }
        }
    }

    /** The label's voice. Both behave identically; only the type differs. */
    enum class CellStyle {
        /** Tracked caps, for word labels: ON / OFF, CLEAN / FASTER, COVERS / LIST. */
        MARK,

        /** The reading face at the size or in the typeface the cell selects. */
        SPECIMEN,
    }

    private companion object {
        /** Matches `@dimen/tracking_caps`; Android takes letterSpacing in ems, not a dimension. */
        const val TRACKING_CAPS = 0.20f
    }
}
