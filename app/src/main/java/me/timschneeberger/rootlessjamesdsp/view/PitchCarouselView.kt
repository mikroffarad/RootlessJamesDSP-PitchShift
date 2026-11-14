package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Horizontal, Flickity-style carousel for picking one integer value.
 *
 * The selected card sits in the middle, its neighbours peek out on both sides,
 * a fling carries momentum and the carousel always comes to rest on a card.
 * It does not wrap around: at the ends it just stops (with the system overscroll effect).
 * Values grow to the right, so swiping left raises the value.
 *
 * - [onValueSettled] fires once the carousel rests on a new value after user input
 *   (swipe, tap on a neighbour, [stepBy], long press). That is the moment to apply the value,
 *   so the sound doesn't wobble while scrolling.
 * - [onCenterChanged] fires live while scrolling, e.g. to update labels.
 * - Tapping a neighbour brings it to the middle. A plain tap on the middle card does nothing;
 *   a long press on it goes back to [resetValue].
 * - [setValue] moves the carousel programmatically without firing [onValueSettled].
 */
class PitchCarouselView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    var onValueSettled: ((Int) -> Unit)? = null
    var onCenterChanged: ((Int) -> Unit)? = null

    var resetValue = 0
    var minValue = 0
        private set
    var maxValue = 0
        private set

    /** Value of the card that is currently closest to the middle (updates live while scrolling). */
    var centerValue = 0
        private set

    /** Where the carousel is heading: the target of a running smooth scroll, else [centerValue]. */
    val targetValue get() = scrollTarget ?: centerValue

    private var settledValue = 0
    private var scrollTarget: Int? = null
    private var pendingValue: Int? = null
    private var userScrolling = false
    private var formatter: (Int) -> String = { it.toString() }

    private val itemWidth = dp(60)
    private val itemHeight = dp(44)
    private val itemSpacing = dp(8)
    private val decoratedWidth = itemWidth + itemSpacing

    private val lm = LinearLayoutManager(context, HORIZONTAL, false)
    private val snapHelper = LinearSnapHelper()

    private val cardColor = themeColor(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFD0E4FF.toInt())
    private val cardTextColor = themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF001D36.toInt())
    private val resetCardColor = themeColor(com.google.android.material.R.attr.colorTertiaryContainer, 0xFFFFD8E4.toInt())
    private val resetCardTextColor = themeColor(com.google.android.material.R.attr.colorOnTertiaryContainer, 0xFF31111D.toInt())

    init {
        layoutManager = lm
        clipToPadding = false
        itemAnimator = null
        setHasFixedSize(true)
        snapHelper.attachToRecyclerView(this)

        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    SCROLL_STATE_DRAGGING -> {
                        userScrolling = true
                        scrollTarget = null
                    }
                    SCROLL_STATE_IDLE -> {
                        userScrolling = false
                        scrollTarget = null
                        updateCenter(fromUser = false)
                        if (centerValue != settledValue) {
                            settledValue = centerValue
                            onValueSettled?.invoke(centerValue)
                        }
                        onCenterChanged?.invoke(centerValue)
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                applyDepth()
                updateCenter(fromUser = userScrolling)
            }
        })
    }

    /** Configures the range, the displayed text of each card and the initial value. */
    fun setup(min: Int, max: Int, value: Int, format: (Int) -> String) {
        minValue = min
        maxValue = max
        formatter = format
        val v = value.coerceIn(min, max)
        centerValue = v
        settledValue = v
        adapter = ValueAdapter()
        if (width > 0) jumpTo(v) else pendingValue = v
    }

    /** Moves to [value] without reporting it back through [onValueSettled]. Ignored while the user is scrolling. */
    fun setValue(value: Int, animate: Boolean) {
        val v = value.coerceIn(minValue, maxValue)
        if (scrollState == SCROLL_STATE_DRAGGING) return
        settledValue = v
        if (v == targetValue) return
        if (width == 0) {
            pendingValue = v
            centerValue = v
            return
        }
        if (animate) smoothScrollToValue(v) else jumpTo(v)
    }

    /** One step left or right (the ‹ › buttons). Applied once the carousel comes to rest. */
    fun stepBy(delta: Int) {
        val target = (targetValue + delta).coerceIn(minValue, maxValue)
        if (target == targetValue) return
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        smoothScrollToValue(target)
    }

    fun canStep(delta: Int) = (targetValue + delta) in minValue..maxValue

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Pad both sides so the first and last cards can also reach the middle
        val pad = ((w - decoratedWidth) / 2).coerceAtLeast(0)
        setPaddingRelative(pad, paddingTop, pad, paddingBottom)
        val target = pendingValue ?: centerValue
        pendingValue = null
        post { jumpTo(target) }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyDepth()
    }

    private fun jumpTo(value: Int) {
        if (adapter == null) return
        scrollTarget = null
        // With the padding above, offset 0 puts the card exactly in the middle
        lm.scrollToPositionWithOffset(value - minValue, 0)
    }

    private fun smoothScrollToValue(value: Int) {
        scrollTarget = value
        lm.startSmoothScroll(CenterScroller().apply { targetPosition = value - minValue })
        onCenterChanged?.invoke(centerValue)
    }

    private fun onCardTapped(position: Int) {
        if (position == NO_POSITION) return
        val value = minValue + position
        // A plain tap on the middle card does nothing, so it can't be reset by accident
        if (value != centerValue) smoothScrollToValue(value)
    }

    private fun onCardLongPressed(position: Int): Boolean {
        if (position == NO_POSITION || minValue + position != centerValue) return false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (centerValue != resetValue) smoothScrollToValue(resetValue)
        return true
    }

    private fun updateCenter(fromUser: Boolean) {
        val view = snapHelper.findSnapView(lm) ?: return
        val position = getChildAdapterPosition(view)
        if (position == NO_POSITION) return
        val value = minValue + position
        if (value == centerValue) return
        centerValue = value
        if (fromUser) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onCenterChanged?.invoke(value)
    }

    /** Cards shrink and fade the further they are from the middle. */
    private fun applyDepth() {
        val center = width / 2f
        if (center <= 0f) return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val childCenter = (child.left + child.right) / 2f
            val distance = min(1f, abs(childCenter - center) / (decoratedWidth * 1.6f))
            val scale = 1f - 0.18f * distance
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = 1f - 0.6f * distance
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun themeColor(attr: Int, fallback: Int) = MaterialColors.getColor(context, attr, fallback)

    /** Smooth scroller that stops with the target card centred instead of just visible. */
    private inner class CenterScroller : LinearSmoothScroller(context) {
        override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int) =
            (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
    }

    private inner class ValueAdapter : Adapter<ValueAdapter.Holder>() {
        inner class Holder(val text: TextView) : ViewHolder(text)

        override fun getItemCount() = maxValue - minValue + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val text = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(itemWidth, itemHeight).apply {
                    val vertical = ((parent.height - itemHeight) / 2).coerceAtLeast(0)
                    setMargins(itemSpacing / 2, vertical, itemSpacing / 2, vertical)
                }
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
                isFocusable = false
            }
            val holder = Holder(text)
            text.setOnClickListener { onCardTapped(holder.bindingAdapterPosition) }
            text.setOnLongClickListener { onCardLongPressed(holder.bindingAdapterPosition) }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val value = minValue + position
            val isReset = value == resetValue
            holder.text.text = formatter(value)
            holder.text.setTextColor(if (isReset) resetCardTextColor else cardTextColor)
            holder.text.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (isReset) resetCardColor else cardColor)
            }
        }
    }
}
