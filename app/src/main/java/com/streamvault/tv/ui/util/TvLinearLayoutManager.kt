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
class TvLinearLayoutManager(
    context: Context,
    orientation: Int = VERTICAL,
    reverseLayout: Boolean = false,
    private val onPerpendicular: ((focused: View, direction: Int) -> View?)? = null,
) : LinearLayoutManager(context, orientation, reverseLayout) {

    private var pendingFocusPos = RecyclerView.NO_POSITION

    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        val extra = if (orientation == HORIZONTAL) {
            width.coerceAtLeast(160)
        } else {
            height.coerceAtLeast(160)
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
        pendingFocusPos = along
        rv.scrollToPosition(along)
        rv.post {
            val target = rv.findViewHolderForAdapterPosition(along)?.itemView
            if (target != null) target.requestFocus()
            pendingFocusPos = RecyclerView.NO_POSITION
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
