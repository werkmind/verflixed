package com.streamvault.tv.ui.brand

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.streamvault.tv.R

/** Verflixed wordmark in the flat brand colour. Top bar and settings header. */
class VfWordmarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    init {
        setTextColor(ContextCompat.getColor(context, R.color.sv_accent))
    }
}
