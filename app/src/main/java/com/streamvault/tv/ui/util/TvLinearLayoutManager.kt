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

    private companion object {
        const val GLIDE_MS = 220
    }

    /**
     * Where the leading edge of the focused child comes to rest. Vertical:
     * the row's top, so its heading always sits at the same height under the
     * nav (the first item, the hero, rests at the very top). Horizontal: the
     * tile's start, so focus stays in one place and the shelf glides under it.
     * 0 on a vertical list = default scrolling.
     */
    var snapTopPx = 0

    private val glide = android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    private var host: RecyclerView? = null

    /** When a glide ends short (a rebind interrupted it), finish the move. */
    private val settleWhenIdle = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) settle(recyclerView)
        }
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        host = view
        view.removeOnScrollListener(settleWhenIdle)
        view.addOnScrollListener(settleWhenIdle)
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        view.removeOnScrollListener(settleWhenIdle)
        host = null
    }

    private fun settle(rv: RecyclerView) {
        rv.post {
            val child = focusedChild ?: return@post
            if (rv.isComputingLayout || rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) return@post
            val delta = restDelta(child) ?: return@post
            val room = if (orientation == HORIZONTAL) rv.canScrollHorizontally(delta) else rv.canScrollVertically(delta)
            // A few pixels off is rounding, not a move worth animating.
            if (kotlin.math.abs(delta) > 2 && room) glideBy(rv, delta)
        }
    }

    /** How far [child] is from its resting place; null when this list does not snap. */
    private fun restDelta(child: View): Int? = when {
        orientation == HORIZONTAL -> getDecoratedLeft(child) - paddingLeft
        snapTopPx > 0 -> getDecoratedTop(child) - (if (getPosition(child) == 0) 0 else snapTopPx)
        else -> null
    }

    private fun glideBy(parent: RecyclerView, delta: Int) {
        if (orientation == HORIZONTAL) parent.smoothScrollBy(delta, 0, glide, GLIDE_MS)
        else parent.smoothScrollBy(0, delta, glide, GLIDE_MS)
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: android.graphics.Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean {
        val delta = restDelta(child)
            ?: return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
        if (delta == 0) return false
        when {
            // Focus can be restored in the middle of a layout pass, where
            // scrolling is not allowed; onLayoutCompleted settles that case.
            parent.isComputingLayout -> Unit
            immediate -> if (orientation == HORIZONTAL) parent.scrollBy(delta, 0) else parent.scrollBy(0, delta)
            // One short decelerating glide. A new focus move simply retargets it,
            // so holding the D-pad stays fluid instead of queueing jumps.
            else -> glideBy(parent, delta)
        }
        return true
    }

    /**
     * A layout pass (new cover art arriving, a row rebinding) can re-anchor the
     * list around the focused child and leave it half off the edge. Once the
     * list is at rest again, glide the focused child back to its place.
     */
    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        val rv = host ?: return
        if (focusedChild != null) settle(rv)
    }

    /**
     * RecyclerView throws when its child bookkeeping gets out of step (seen in
     * the field as "Called attach on a child which is not detached" under
     * fast D-pad input while rows rebind). That is recoverable: drop every
     * view and lay the list out again, instead of letting it end the app.
     */
    private fun recover(recycler: RecyclerView.Recycler, error: RuntimeException) {
        android.util.Log.w("TvLinearLayoutManager", "list rebuilt after layout fault", error)
        runCatching { removeAndRecycleAllViews(recycler) }
        runCatching { recycler.clear() }
        val rv = host ?: return
        rv.post {
            runCatching { rv.adapter?.notifyDataSetChanged() }
            rv.post { if (rv.focusedChild == null && rv.hasWindowFocus()) rv.requestFocus() }
        }
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: RuntimeException) {
            recover(recycler, e)
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int =
        try {
            super.scrollVerticallyBy(dy, recycler, state)
        } catch (e: RuntimeException) {
            recover(recycler, e)
            0
        }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int =
        try {
            super.scrollHorizontallyBy(dx, recycler, state)
        } catch (e: RuntimeException) {
            recover(recycler, e)
            0
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
            // No scrollToPosition here: it jumps. Focusing the neighbour makes
            // requestChildRectangleOnScreen glide it into place.
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
