package com.streamvault.tv.ui.util

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * TV D-pad: keep focus on the next/previous item in this list instead of
 * jumping to a sibling above (hero, season tabs, sidebar) when the neighbor
 * is off-screen or recycled.
 */
@Suppress("WrongConstant")
class TvLinearLayoutManager(
    context: Context,
    orientation: Int = VERTICAL,
    reverseLayout: Boolean = false,
    private val onPerpendicular: ((focused: View, direction: Int) -> View?)? = null,
) : LinearLayoutManager(context, orientation, reverseLayout) {

    private val extraVerticalPx = (80 * context.resources.displayMetrics.density).toInt()

    private var pendingFocusPos = RecyclerView.NO_POSITION

    /**
     * Vertical lists only: where the top of the focused row comes to rest, so
     * its heading always sits at the same height under the nav. 0 = default
     * scrolling. The first item (the hero) always rests at the very top.
     */
    var snapTopPx = 0

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: android.graphics.Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean {
        if (orientation != VERTICAL || snapTopPx <= 0) {
            return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
        }
        val restAt = if (getPosition(child) == 0) 0 else snapTopPx
        val dy = getDecoratedTop(child) - restAt
        if (dy == 0) return false
        // Jump, do not animate: an animated scroll racing the row-to-row focus
        // hand-off lets the system focus search fall through to the nav.
        // Focus can also be restored in the middle of a layout pass, where
        // scrolling is not allowed; defer that case by one frame.
        if (parent.isComputingLayout) {
            parent.post {
                if (child.parent === parent) parent.scrollBy(0, getDecoratedTop(child) - restAt)
            }
        } else {
            parent.scrollBy(0, dy)
        }
        return true
    }

    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        val extra = if (orientation == HORIZONTAL) {
            width.coerceAtLeast(160)
        } else {
            // Keep a short lookahead, not a full extra viewport: a laid-out
            // hero with Ken Burns would otherwise keep painting over Favoriten.
            extraVerticalPx
        }
        extraLayoutSpace[0] = extra
        extraLayoutSpace[1] = extra
    }

    @Deprecated("Use calculateExtraLayoutSpace")
    override fun getExtraLayoutSpace(state: RecyclerView.State): Int =
        if (orientation == HORIZONTAL) width.coerceAtLeast(160) else height.coerceAtLeast(160)

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val rv = focused.parent as? RecyclerView ?: return null
        val pos = rv.getChildAdapterPosition(focused)
        if (pos == RecyclerView.NO_POSITION) return null
        val count = rv.adapter?.itemCount ?: 0

        val along = when {
            orientation == HORIZONTAL && direction == View.FOCUS_LEFT -> pos - 1
            orientation == HORIZONTAL && direction == View.FOCUS_RIGHT -> pos + 1
            orientation == VERTICAL && direction == View.FOCUS_UP -> pos - 1
            orientation == VERTICAL && direction == View.FOCUS_DOWN -> pos + 1
            else -> {
                return onPerpendicular?.invoke(focused, direction)
            }
        }

        if (along !in 0 until count) return null

        val existing = rv.findViewHolderForAdapterPosition(along)?.itemView
        if (existing != null) {
            rv.scrollToPosition(along)
            return existing
        }
        // Off-screen neighbour: scroll, then place focus on the recycled item.
        // A retry pass guards against slow layouts, so the focus can never
        // vanish between two RecyclerView positions (TV dead-end).
        pendingFocusPos = along
        rv.scrollToPosition(along)
        rv.post {
            val target = rv.findViewHolderForAdapterPosition(along)?.itemView
            if (target != null) {
                target.requestFocus()
                pendingFocusPos = RecyclerView.NO_POSITION
            } else {
                rv.post {
                    val late = rv.findViewHolderForAdapterPosition(along)?.itemView
                        ?: rv.getChildAt(0)
                    if (late != null && rv.getChildAdapterPosition(late) == along) {
                        late.requestFocus()
                    }
                    pendingFocusPos = RecyclerView.NO_POSITION
                }
            }
        }
        return focused
    }

    fun attachPendingFocus(rv: RecyclerView) {
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val want = pendingFocusPos
                if (want == RecyclerView.NO_POSITION) return
                if (rv.getChildAdapterPosition(view) == want) {
                    view.requestFocus()
                    pendingFocusPos = RecyclerView.NO_POSITION
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
    }
}
