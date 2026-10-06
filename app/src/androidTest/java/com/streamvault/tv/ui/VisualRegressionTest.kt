package com.streamvault.tv.ui

import android.content.Context
import android.test.InstrumentationTestCase
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.streamvault.tv.R
import com.streamvault.tv.ui.util.FocusFx

/** Exercises the native TV layouts, including the largest supported display zoom. */
@Suppress("DEPRECATION")
class VisualRegressionTest : InstrumentationTestCase() {
    private fun onUi(block: (Context) -> Unit) {
        instrumentation.runOnMainSync {
            block(ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Verflixed))
        }
    }

    private fun measure(view: View, widthDp: Int, heightDp: Int) {
        val density = view.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    fun testLongHeroGrowsInsteadOfClippingControls() = onUi { context ->
        for (width in listOf(960, 752, 662)) {
            val hero = LayoutInflater.from(context).inflate(R.layout.item_home_hero, null)
            hero.findViewById<TextView>(R.id.heroTitle).text = "Eine sehr lange Serienüberschrift mit einem zweiten Teil"
            hero.findViewById<TextView>(R.id.heroMeta).text = "Staffel 12 · Episode 24"
            hero.findViewById<TextView>(R.id.heroOverview).text = "Eine ausführliche Beschreibung der Serie mit langen Sätzen und mehreren Zeilen. ".repeat(5)
            val density = hero.resources.displayMetrics.density
            hero.measure(
                View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            hero.layout(0, 0, hero.measuredWidth, hero.measuredHeight)
            val block = hero.findViewById<View>(R.id.heroTextBlock)
            assertTrue("Hero text starts inside stage at ${width}dp", block.top >= 0)
            assertTrue(block.bottom <= hero.height)
            assertTrue(hero.findViewById<View>(R.id.btnHeroPlay).height > 0)
        }
    }

    fun testFocusPreservesOpaqueSiblingsAndScreenClipping() = onUi { context ->
        val root = FrameLayout(context).apply { clipChildren = true; clipToPadding = true }
        val shelf = RecyclerView(context).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        }
        root.addView(shelf)
        val first = View(context)
        val second = View(context)
        shelf.addView(first)
        shelf.addView(second)
        FocusFx.bindScale(first)
        first.onFocusChangeListener.onFocusChange(first, true)
        assertEquals(1f, second.alpha)
        assertTrue("Focus must not disable screen clipping", root.clipChildren)
        assertTrue(root.clipToPadding)
    }

    fun testFormsAndDetailCanScrollAtLargestZoom() = onUi { context ->
        for (layout in listOf(R.layout.activity_setup, R.layout.activity_detail, R.layout.activity_profile_edit)) {
            val root = LayoutInflater.from(context).inflate(layout, null) as ViewGroup
            measure(root, 662, 372)
            assertTrue("Scrollable content remains reachable", root.getChildAt(0).height >= root.height - root.paddingTop - root.paddingBottom)
        }
    }
}
