package com.lanhang.lite.core

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

object SwipeRefreshStyle {
    fun apply(view: SwipeRefreshLayout) {
        view.setColorSchemeColors(
            MaterialColors.getColor(view, MaterialR.attr.colorPrimary)
        )
        view.setProgressBackgroundColorSchemeColor(
            MaterialColors.getColor(view, MaterialR.attr.colorSurfaceContainerHigh)
        )
    }
}
