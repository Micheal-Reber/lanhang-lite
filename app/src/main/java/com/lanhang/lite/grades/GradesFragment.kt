package com.lanhang.lite.grades

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.lanhang.lite.ui.miuix.LanhangMiuixTheme
import com.lanhang.lite.ui.miuix.isNightMode

/**
 * 成绩页宿主。视觉层已迁到 Compose + Miuix（见 [GradesScreen]），
 * 这里只负责把 Compose 内容挂到 Fragment 生命周期上。
 */
class GradesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LanhangMiuixTheme(darkTheme = LocalContext.current.isNightMode()) {
                GradesScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
