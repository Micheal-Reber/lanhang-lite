package com.lanhang.lite.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.lanhang.lite.ui.navigation.liquid.DampedDragAnimation
import com.lanhang.lite.ui.navigation.liquid.DockTouchState
import com.lanhang.lite.ui.navigation.liquid.InteractiveHighlight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// 照抄自 PocketDiary LiquidGlassBottomBar（Compose → 同为 Compose，逻辑未改）。
// 仅做了宿主适配：NavDestination/Screen → selectedIndex/LanhangTab，
// Spacing.xs → 4.dp，backdrop 由宿主经 rememberCanvasBackdrop 提供（本项目内容为 View 层）。

private val BarHeight = 64.dp
private val IndicatorHeight = 56.dp
private val ContentMargin = 4.dp

/**
 * 每个 Tab 的宽度。
 *
 * 胶囊不铺满屏幕——总宽 = TabWidth × tab 数 + 两侧 ContentMargin，
 * 即「贴合内容」的紧凑胶囊（2026-11-09 用户要求），常规手机屏（~390dp）
 * 宽约 312dp，折叠屏 / 平板上保持不变。
 */
private val TabWidth = 76.dp

/**
 * 紧凑胶囊的完整宽度。
 *
 * `MainActivity.forwardTouchOutsideBar` 靠它算透传边界：胶囊居中于底栏视图里，
 * 左右两侧的空白要把触摸透传回内容区，边界按这个值从视图中心往两边量。
 */
internal fun compactPillWidthDp(tabCount: Int): Dp = TabWidth * tabCount + ContentMargin * 2

data class LanhangTab(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val title: String,
)

private val LocalTabScale = staticCompositionLocalOf { { 1f } }
private val LocalTabInteractive = staticCompositionLocalOf { true }

@Composable
fun LiquidGlassBottomBar(
    tabs: List<LanhangTab>,
    selectedIndex: Int,
    onNavigate: (LanhangTab) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /**
     * 采样源版本号（见 [ViewBackdrop]）：原生页面滚动/刷新时递增，
     * 玻璃在绘制期读它，让缓存了「上一次采样结果」的图层重新录制。
     * 不传 = 只采样一次（只有 Compose 内容自己会动时才够用）。
     */
    backdropRevision: () -> Int = { 0 },
) {
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() >= 0.5f
    val accentColor = MaterialTheme.colorScheme.primary
    // 与 PocketDiary / E:\app_start 完全一致：40% 底色。
    // 这层是唯一盖在模糊之上的漆，调高到 0.5 以上就会把 blur/折射压没（历史上 0.84+0.54 双层叠加
    // 合成 92.6% 不透明，实测就是「看起来像实心卡片」）。
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.40f)
    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        // 紧凑胶囊：整体宽度按 Tab 计，不铺满屏幕；居中由调用方的 align 决定。
        // 触摸透传边界见 MainActivity.forwardTouchOutsideBar，两处必须同口径。
        modifier
            .height(BarHeight)
            .width(compactPillWidthDp(tabs.size)),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val tabsCount = tabs.size
        // 与内容步长一致：胶囊左右各留 ContentMargin，指示器宽度必须用同一个值
        val tabWidth = with(density) { TabWidth.toPx() }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, constraints.maxWidth) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()

        val currentIndex = remember(selectedIndex) { selectedIndex.coerceAtLeast(0) }
        val selectedTabIndex = remember(currentIndex) { { currentIndex } }
        val latestSelected by rememberUpdatedState(selectedTabIndex)
        val latestNavigate by rememberUpdatedState(onNavigate)
        val latestTabs by rememberUpdatedState(tabs)

        // 切 tab 的触感：点按与拖动落点两个提交点共用。
        // 只在真的换了 tab 时震 —— host 的 switchTab 不做同 tab 判重，点当前 tab 也会走一遍提交，
        // 不在这里挡住就会出现「点已选中的 tab 也震」。
        // 手感取 TextHandleMove（最轻的一档，同 NexioSchedule 的做法）：VirtualKey 偏脆、Confirm 偏实，
        // 底栏每次切换都来一下会显得吵。
        // 用 rememberUpdatedState 而非普通 lambda：DampedDragAnimation 被 remember 记住，
        // 闭包捕获的是首次组合的实例，tabs 换新 List 后会读到旧值。
        val haptic = LocalHapticFeedback.current
        val commitTab = rememberUpdatedState<(Int) -> Unit> { index ->
            if (index != latestSelected()) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            latestNavigate(latestTabs[index])
        }

        // updateValue 是异步写，这里持有权威目标，避免读到上一帧的 targetValue
        val dragTarget = remember { mutableFloatStateOf(currentIndex.toFloat()) }

        val touchSlop = LocalViewConfiguration.current.touchSlop
        val contentMarginPx = with(density) { ContentMargin.toPx() }
        val touchState = remember(tabWidth, tabsCount, isLtr) {
            DockTouchState(tabsCount, tabWidth, reverse = !isLtr)
        }

        val drag = remember(animationScope, tabWidth, tabsCount, isLtr) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = { position ->
                    touchState.start(position.x - contentMarginPx)
                    dragTarget.floatValue = touchState.indicatorValue
                    updateValue(dragTarget.floatValue)
                },
                onDragStopped = {
                    // 提交命中格，而不是视觉中心或取整后的偏移
                    val target = touchState.index
                    dragTarget.floatValue = target.toFloat()
                    animateToValue(dragTarget.floatValue)
                    if (target != latestSelected()) commitTab.value(target)
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDragCancelled = {
                    dragTarget.floatValue = latestSelected().toFloat()
                    animateToValue(dragTarget.floatValue)
                    animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                },
                onDrag = { _, dragAmount ->
                    if (dragAmount.x != 0f) {
                        touchState.move(dragAmount.x)
                        dragTarget.floatValue = touchState.indicatorValue
                        updateValue(dragTarget.floatValue)
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                        }
                    }
                },
            )
        }

        // 外部选中变化（点击 tab / 通知深链）→ 指示器吸附；手势进行中不抢
        LaunchedEffect(drag) {
            snapshotFlow { latestSelected() }
                .collectLatest { index ->
                    if (!drag.gestureActive) {
                        dragTarget.floatValue = index.toFloat()
                        drag.animateToValue(dragTarget.floatValue)
                    }
                }
        }

        val interactiveHighlight = remember(animationScope, tabWidth, isLtr) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    val center = if (isLtr) {
                        (drag.value + 0.5f) * tabWidth + panelOffset
                    } else {
                        size.width - (drag.value + 0.5f) * tabWidth + panelOffset
                    }
                    Offset(center, size.height / 2f)
                },
            )
        }

        val tabContent: @Composable RowScope.() -> Unit = {
            tabs.forEachIndexed { index, tab ->
                LiquidGlassTab(
                    index = index,
                    tab = tab,
                    dragValue = { drag.value },
                    onClick = { commitTab.value(index) },
                )
            }
        }

        // 层一：胶囊外框
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(50.dp) },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = drag.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .then(interactiveHighlight.modifier)
                .height(BarHeight)
                .fillMaxWidth()
                .padding(ContentMargin)
                // 采样源（原生页面）变了就重录本层：否则这层 RenderNode 会一直复放上一次的模糊底图
                .drawBehind { backdropRevision() },
            verticalAlignment = Alignment.CenterVertically,
            content = tabContent,
        )

        // 层二：染色副本（录进 tabsBackdrop，再折射出来统一染主题色）
        CompositionLocalProvider(
            LocalTabInteractive provides false,
            LocalTabScale provides { lerp(1f, 1.2f, drag.pressProgress) },
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    // 读在淡出层内部、layerBackdrop 外部：这一层重录才会把染色副本重新录进 tabsBackdrop
                    .drawBehind { backdropRevision() }
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(50.dp) },
                        effects = {
                            val progress = drag.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
                        },
                        highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .then(interactiveHighlight.modifier)
                    .height(IndicatorHeight)
                    .fillMaxWidth()
                    .padding(horizontal = ContentMargin)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = tabContent,
            )
        }

        // 层三：选中指示器（折射整窗 + tab 内容）
        Box(
            Modifier
                .padding(horizontal = ContentMargin)
                .graphicsLayer {
                    translationX =
                        if (isLtr) drag.value * tabWidth + panelOffset
                        else size.width - (drag.value + 1f) * tabWidth + panelOffset
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { RoundedCornerShape(50.dp) },
                    effects = {
                        val progress = drag.pressProgress
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                    shadow = { Shadow(alpha = drag.pressProgress) },
                    innerShadow = { InnerShadow(radius = 8f.dp * drag.pressProgress, alpha = drag.pressProgress) },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val velocity = drag.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = drag.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                            alpha = 1f - progress,
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    },
                )
                .height(IndicatorHeight)
                // 显式宽度：分数填充会在 padding 之后测量，导致指示器右漂
                .width(with(density) { tabWidth.toDp() })
                // 指示器也采样整窗底图，页面滚动时要跟着重录
                .drawBehind { backdropRevision() },
        )

        // 固定输入层，压在所有玻璃之上统一接管手势
        Box(
            Modifier
                .matchParentSize()
                .clearAndSetSemantics {}
                .then(interactiveHighlight.gestureModifier)
                .then(drag.modifier),
        )
    }
}

@Composable
private fun RowScope.LiquidGlassTab(
    index: Int,
    tab: LanhangTab,
    dragValue: () -> Float,
    onClick: () -> Unit,
) {
    val scale = LocalTabScale.current
    val interactive = LocalTabInteractive.current
    val emphasis = (1f - abs(dragValue() - index)).coerceIn(0f, 1f)
    val base = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color.White.copy(alpha = 0.70f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
    }
    val color = androidx.compose.ui.graphics.lerp(base, MaterialTheme.colorScheme.primary, emphasis)
    Column(
        Modifier
            .clip(RoundedCornerShape(50.dp))
            .clickable(
                interactionSource = null,
                indication = null,
                enabled = interactive,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (emphasis >= 0.5f) tab.selectedIcon else tab.unselectedIcon,
            contentDescription = tab.title,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tab.title,
            color = color,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (emphasis >= 0.5f) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}
