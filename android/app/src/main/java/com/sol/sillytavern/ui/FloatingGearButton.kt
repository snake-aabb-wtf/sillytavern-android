package com.sol.sillytavern.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import kotlin.math.abs

/**
 * Settings gear that behaves like a floating control instead of a fixed corner button.
 *
 * SillyTavern draws its own controls along the top edge, so a pinned 48dp button covered
 * them. This view can be dragged anywhere inside its parent, snaps to the nearest side on
 * release and remembers that position. A tap (movement below the touch slop) still opens
 * settings, so the click listener is unchanged.
 *
 * The position is persisted as a fraction of the free travel inside the parent rather than
 * in pixels, which keeps the gear on screen after rotation, inset changes and density
 * changes.
 */
class FloatingGearButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.imageButtonStyle,
) : ImageButton(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var positioned = false

    private val parentLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val parent = parent as? View ?: return@OnLayoutChangeListener
        if (width <= 0 || parent.width <= 0) return@OnLayoutChangeListener
        if (!positioned) {
            positioned = true
            applySavedPosition()
        } else {
            snapIntoBounds()
        }
    }

    init {
        // A little elevation keeps the gear readable over both the dark chat list and the
        // light character pages.
        elevation = resources.displayMetrics.density * 6f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.addOnLayoutChangeListener(parentLayoutListener)
        // The saved position needs a laid-out parent; until then stay invisible so the
        // button never flashes at the default corner.
        post {
            if (!positioned && parentWidth() > 0) {
                positioned = true
                applySavedPosition()
            }
            visibility = View.VISIBLE
        }
    }

    override fun onDetachedFromWindow() {
        (parent as? View)?.removeOnLayoutChangeListener(parentLayoutListener)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount != 1) return false
                downRawX = event.rawX
                downRawY = event.rawY
                startX = x
                startY = y
                dragging = false
                animate().cancel()
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    dragging = abs(event.rawX - downRawX) > touchSlop ||
                        abs(event.rawY - downRawY) > touchSlop
                }
                if (dragging) {
                    x = (startX + event.rawX - downRawX).coerceIn(minX(), maxX().coerceAtLeast(minX()))
                    y = (startY + event.rawY - downRawY).coerceIn(minY(), maxY().coerceAtLeast(minY()))
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                if (dragging) settle() else performClick()
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                dragging = false
                snapIntoBounds()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Snaps to the nearer side, which keeps the middle of the page clear. */
    private fun settle() {
        val lo = minX()
        val hi = maxX().coerceAtLeast(lo)
        val target = if (x - lo < (hi - lo) / 2f) lo else hi
        persist(target)
        animate().x(target).setDuration(160).start()
    }

    private fun snapIntoBounds() {
        x = x.coerceIn(minX(), maxX().coerceAtLeast(minX()))
        y = y.coerceIn(minY(), maxY().coerceAtLeast(minY()))
        persist(x)
    }

    private fun applySavedPosition() {
        if (maxX() < minX() || maxY() < minY()) return
        val fx = prefs.getFloat(KEY_X, DEFAULT_X_FRAC)
        val fy = prefs.getFloat(KEY_Y, DEFAULT_Y_FRAC)
        x = minX() + (maxX() - minX()) * fx
        y = minY() + (maxY() - minY()) * fy
    }

    private fun persist(xOverride: Float) {
        val lo = minX()
        val hi = maxX()
        val top = minY()
        val bottom = maxY()
        if (hi <= lo || bottom <= top) return
        prefs.edit()
            .putFloat(KEY_X, ((xOverride - lo) / (hi - lo)).coerceIn(0f, 1f))
            .putFloat(KEY_Y, ((y - top) / (bottom - top)).coerceIn(0f, 1f))
            .apply()
    }

    private fun minX(): Float = (parent as? View)?.paddingLeft?.toFloat() ?: 0f

    private fun minY(): Float = (parent as? View)?.paddingTop?.toFloat() ?: 0f

    private fun maxX(): Float {
        val parent = parent as? View ?: return minX()
        return (parent.width - parent.paddingRight - width).toFloat()
    }

    private fun maxY(): Float {
        val parent = parent as? View ?: return minY()
        return (parent.height - parent.paddingBottom - height).toFloat()
    }

    private fun parentWidth(): Int = (parent as? View)?.width ?: 0

    private companion object {
        const val PREFS = "floating_gear"
        const val KEY_X = "x_fraction"
        const val KEY_Y = "y_fraction"

        /** Right edge, about a third down: clear of SillyTavern's own top-right buttons. */
        const val DEFAULT_X_FRAC = 1f
        const val DEFAULT_Y_FRAC = 0.35f
    }
}
