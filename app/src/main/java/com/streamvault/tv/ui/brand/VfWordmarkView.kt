package com.streamvault.tv.ui.brand

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Verflixed wordmark: gradient glass text (icy top → accent depth) with a soft
 * accent glow. Used in the top bar and as the settings header.
 */
class VfWordmarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    init {
        setShadowLayer(22f, 0f, 2f, 0x592F80FF)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!changed || width == 0 || height == 0) return
        paint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(0xFFD9E9FF.toInt(), 0xFF6FB0FF.toInt(), 0xFF2F80FF.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
}
