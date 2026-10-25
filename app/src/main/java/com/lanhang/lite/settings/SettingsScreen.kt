package com.lanhang.lite.settings

import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.lanhang.lite.ui.shell.ReportListScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lanhang.lite.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background

/** 设置页的 UI 状态（数据与动作都在 SettingsFragment 里，这里只负责显示）。 */
data class SettingsUiState(
    val page: Int = PAGE_NONE,
    val themeLabel: String = "",
    val languageLabel: String = "",
    val account: String = "--",
    val version: String = "--",
    val proto: String = "--",
) {
    companion object {
        const val PAGE_NONE = -1
        const val PAGE_APPEARANCE = 0
        const val PAGE_TOOLS = 1
        const val PAGE_ABOUT = 2
    }
}

/**
 * 设置页（Miuix 版）：分组 + 卡片的偏好列表，一行一个 [ArrowPreference] / [BasicComponent]，
 * 分割线两侧内缩 16dp —— 和 Miuix 示例里的设置页是同一套排版。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onOpenAppearance: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenAbout: () -> Unit,
    onTheme: () -> Unit,
    onLanguage: () -> Unit,
    onExport: () -> Unit,
    onDiagnostics: () -> Unit,
    onDebugSchedule: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    // 二级页（外观/课表工具/关于）与目录页之间的过渡 = 照搬 Miuix 的 `NavTransitions.MiuixDefault`
    // （2026-10-24 深夜八次改版，用户拿 demo 的 settings → about 录屏对比）：
    //  · 新页整幅从右侧推入（translationX: +width → 0）
    //  · 被覆盖页视差 25% 让位 + alpha 1 → 0.9
    //  · 新页运动期间带圆角裁剪（corner clip 24dp）
    //  · 进度用**临界阻尼弹簧**（dampingRatio = 1f / stiffness = 146f，等价 Miuix NavDriverSpec 的
    //    ω=√146），不是 tween —— tween(300) 走完就停，观感是「啪一下」，用户判定不到位
    val pushCurve = spring<IntOffset>(dampingRatio = 1f, stiffness = 146f)
    AnimatedContent(
        targetState = state.page,
        transitionSpec = {
            // 2026-10-25 用户再次反馈「还是一个向左一个向右」：
            // 之前新旧两页同时在横移（新页从右推入 + 旧页向左让位），同一时刻屏幕上
            // 有两块内容在动，观感就是「一张往左、一张往右」。现在改成**只让一张纸动**：
            //  · 进入：新页从右侧推入，旧页原地不动（只做极轻的渐隐）
            //  · 返回：旧页（子页）向右滑出，上级页原地不动（只做极轻的渐显）
            // 任何时刻屏幕上只有一处位移 = 不会再有「一左一右」。
            val opening = targetState != SettingsUiState.PAGE_NONE
            if (opening) {
                // 上级页面在推入过程中淡出（不是只降到 0.9）：
                // 顶栏换成紧凑高度会让容器上移 40dp，淡出后这段位移看不见。
                slideInHorizontally(pushCurve) { it } togetherWith
                    fadeOut(tween(PARENT_FADE_MS))
            } else {
                fadeIn(tween(PARENT_FADE_MS)) togetherWith
                    slideOutHorizontally(pushCurve) { it }
            }
        },
        label = "settingsPage",
    ) { page ->
        ReportListScroll(rememberLazyListState())
        LazyColumn(
            // 每页自带底色，推入/退出时才能盖住下面那一页；圆角裁剪在静止时不可见（底色同页面底）
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    shape = RoundedCornerShape(NAV_CORNER_DP)
                    clip = true
                }
                .background(MiuixTheme.colorScheme.background),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
        ) {
        when (page) {
            SettingsUiState.PAGE_APPEARANCE -> {
                item("appearance") {
                    SmallTitle(stringResource(R.string.settings_appearance))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_theme_title),
                            summary = state.themeLabel,
                            onClick = onTheme,
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ArrowPreference(
                            title = stringResource(R.string.settings_language),
                            summary = state.languageLabel,
                            onClick = onLanguage,
                        )
                    }
                }
            }

            SettingsUiState.PAGE_TOOLS -> {
                item("tools") {
                    SmallTitle(stringResource(R.string.settings_schedule_tools))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_export_schedule),
                            summary = stringResource(R.string.settings_export_schedule_subtitle),
                            onClick = onExport,
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ArrowPreference(
                            title = stringResource(R.string.menu_debug_schedule),
                            summary = stringResource(R.string.settings_debug_summary),
                            onClick = onDebugSchedule,
                        )
                    }
                }
            }

            SettingsUiState.PAGE_ABOUT -> {
                item("aboutAccount") {
                    SmallTitle(stringResource(R.string.settings_account_section))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ValueRow(stringResource(R.string.settings_account), state.account)
                    }
                }
                item("aboutGeneral") {
                    SmallTitle(stringResource(R.string.settings_general_section))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ValueRow(stringResource(R.string.settings_app_version), state.version)
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ValueRow(stringResource(R.string.settings_proto), state.proto)
                    }
                }
                item("aboutDiagnostics") {
                    SmallTitle(stringResource(R.string.settings_about_section))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_diagnostics),
                            summary = stringResource(R.string.settings_diagnostics_subtitle),
                            onClick = onDiagnostics,
                        )
                    }
                }
                item("footer") {
                    Text(
                        text = stringResource(R.string.settings_footer),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, start = 24.dp, end = 24.dp),
                    )
                }
            }

            else -> {
                item("directory") {
                    SmallTitle(stringResource(R.string.settings_general_section))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_appearance),
                            summary = state.themeLabel,
                            onClick = onOpenAppearance,
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ArrowPreference(
                            title = stringResource(R.string.settings_schedule_tools),
                            summary = stringResource(R.string.settings_tools_summary),
                            onClick = onOpenTools,
                        )
                    }
                }
                item("directoryAbout") {
                    SmallTitle(stringResource(R.string.settings_about_section))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_about_app),
                            summary = stringResource(R.string.settings_about_version, state.version),
                            onClick = onOpenAbout,
                        )
                    }
                }
                item("directoryAccount") {
                    SmallTitle(stringResource(R.string.settings_account_section))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ValueRow(stringResource(R.string.settings_account), state.account)
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ArrowPreference(
                            title = stringResource(R.string.action_logout),
                            summary = stringResource(R.string.settings_logout_summary),
                            onClick = onLogout,
                        )
                    }
                }
                item("directoryFooter") {
                    Text(
                        text = stringResource(R.string.settings_footer),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, start = 24.dp, end = 24.dp),
                    )
                }
            }
        }
        }
    }
}

/** 只读信息行：左标题右值（Miuix 的 BasicComponent 排版）。 */
@Composable
private fun ValueRow(title: String, value: String) {
    BasicComponent(
        title = title,
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
    )
}

/** 被覆盖页 alpha 衰减的时长（对齐弹簧收敛，别用短 tween） */
private const val PLAIN_FADE_MS = 520

/** 推入时新页的圆角裁剪半径（dp），对齐 Miuix 的 corner clip */
private const val NAV_CORNER_DP = 24f

/** 一级页面在二级页推入/退出时的淡入淡出时长（毫秒） */
private const val PARENT_FADE_MS = 140
