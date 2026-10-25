package com.lanhang.lite.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import com.lanhang.lite.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Weeks

/**
 * 顶栏的动画目标状态（值相等 = 不播动画）。把「可见的东西」全塞进来，是因为
 * `AnimatedContent` 的**退场内容**会和入场内容同时在场：如果退场那一份还去读
 * `ShellState`，它会在半路突然变成新标题/新返回箭头。
 */
private data class TopBarSpec(
    val title: String,
    val subtitle: String,
    val large: Boolean,
    val hasBack: Boolean,
    val actionText: String?,
)

/**
 * Miuix 顶栏：`TopAppBar`（大标题，主 Tab 用）/ `SmallTopAppBar`（课表页与二级页）。
 *
 * 2026-10-24 应用户要求去掉右上角「更多（三个点）」——那个菜单原先只放
 * 调试/退出登录两项，用户点开没反应（下拉菜单未渲染）且与 Miuix 观感不符，
 * 现改为：退出登录进「设置 → 账号」，调试入口进「设置 → 课表工具」。
 *
 * ## 标题动效（2026-10-25 九次改版）
 * Miuix 的 `TopAppBar(title: String)` / `SmallTopAppBar(title: String)` **只接受字符串标题**，
 * 没有 composable 标题槽，所以没法只给标题做动画。这里把**整条顶栏**放进 `AnimatedContent`：
 * 新旧两条同款顶栏并排整幅横移（入场 `+width*dir → 0`，退场 `0 → -width*dir`），
 * 视觉上等价于参考项目 KernelSU-Style-UI-Kit 里「每页自带 Scaffold+TopAppBar、
 * 顶栏跟着 HorizontalPager 一起滑」的效果，且两条顶栏都不透明、首尾相接不会露缝。
 *
 * 之前的写法是「整条顶栏淡出到 0 再淡回 1」（`alpha 0 → 1` + 22dp 位移），
 * 用户连续两轮反馈「闪一下」，根因就是那个 alpha 归零——**不要再改回去**。
 */
@Composable
fun LanhangTopBar(state: ShellState, large: Boolean = false) {
    val dir = state.slideDir
    val fast = state.slideFast
    // 页面往下滑之后大标题收起（见 ShellScroll）；这里读它但不放进 TopBarSpec，
    // 否则横向切页动画会把「收起」也当成一次换标题去做横移。
    AnimatedContent(
        targetState = TopBarSpec(
            title = state.title,
            subtitle = state.subtitle,
            large = large,
            hasBack = state.backAction != null,
            actionText = state.actionText,
        ),
        transitionSpec = {
            // Tab 级用 220ms EaseInOut（参考项目是 tween(100×距离+100, EaseInOut)，相邻 Tab 200ms）；
            // 二级页用 Miuix 的临界阻尼弹簧（dampingRatio=1 / stiffness=146），和 SettingsScreen 的推入一致
            if (fast) {
                // 只做 1/4 幅的随动 + 淡入淡出：整幅横移时新旧两条顶栏会在半路重叠，
                // 看起来像「两个标题打架」（用户 2026-10-25 视频反馈）。
                val spec = tween<IntOffset>(TAB_BAR_MS, easing = FastOutSlowInEasing)
                (slideInHorizontally(spec) { width -> width / 4 * dir } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(spec) { width -> -width / 4 * dir } + fadeOut(tween(120)))
            } else {
                // 二级页/返回：顶栏**不做横移**，只做淡切，方向感完全交给下面的页面。
                // 顶栏自己横移时，哪怕幅度很小，也会被读成「顶栏和内容反着动」。
                fadeIn(tween(200)) togetherWith fadeOut(tween(140))
            }
        },
        label = "shellTopBar",
    ) { spec ->
        TopBarContent(
            spec = spec,
            progress = if (spec.large && !spec.hasBack) ShellScroll.progress else 0f,
            onBack = { state.backAction?.invoke() },
            onAction = { state.onAction?.invoke() },
        )
    }
}

@Composable
private fun TopBarContent(
    spec: TopBarSpec,
    progress: Float,
    onBack: () -> Unit,
    onAction: () -> Unit,
) {
    val navigation: @Composable () -> Unit = {
        if (spec.hasBack) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
        }
    }
    // 右上角文字动作（如「发表动态」页的「发表」）
    val actions: @Composable RowScope.() -> Unit = {
        val label = spec.actionText
        if (label != null) {
            TextButton(text = label, onClick = onAction)
        }
    }
    if (spec.large && !spec.hasBack) {
        CollapsingTitleBar(spec = spec, progress = progress, actions = actions)
    } else {
        // 2026-10-25 用户两次反馈的平衡点：
        //  · 二级页顶栏必须是紧凑高度（不能和一级大标题一样高，否则不协调）；
        //  · 但顶栏变矮会让下面的容器上跳 40dp，上一级页面会「异常上移」。
        // 解法：顶栏保持紧凑，容器位移由 SettingsScreen 里「上级页面快速淡出」遮掉
        // （位移发生在它几乎透明的阶段），观感上就是新页面推入、旧页面淡去。
        if (spec.large) {
            TopAppBar(
                title = spec.title,
                subtitle = spec.subtitle,
                navigationIcon = navigation,
                actions = actions,
            )
        } else {
            SmallTopAppBar(
                title = spec.title,
                subtitle = spec.subtitle,
                navigationIcon = navigation,
                actions = actions,
            )
        }
    }
}

/**
 * 大标题顶栏：**一个** `Text` 从 32sp 连续缩到 18sp、同时从左下移到正中。
 *
 * 2026-10-25 用户第二次反馈「顶栏收缩和文字不同步，文字是突然闪到顶部的」——
 * 上一版是「高度动画 + 两套顶栏交叉淡入」，切换是离散的，所以看起来像闪。
 * 现在字号、纵向位置、横向位置全部由滚动进度 [progress] 逐帧插值，和手指完全同步，
 * 副标题的高度也跟着收到 0，收起后标题正好落在小标题栏的垂直中心。
 */
@Composable
private fun CollapsingTitleBar(
    spec: TopBarSpec,
    progress: Float,
    actions: @Composable RowScope.() -> Unit,
) {
    val density = LocalDensity.current
    var blockSize by remember(spec.title, spec.subtitle) { mutableStateOf(IntSize.Zero) }
    val barHeight = lerp(BAR_LARGE_HEIGHT, BAR_SMALL_HEIGHT, progress)
    val titleSize = lerp(TITLE_LARGE_SP, TITLE_SMALL_SP, progress)
    // 上面留出状态栏（Miuix 的 TopAppBar 内部本来会自己加，自绘顶栏必须显式加，
    // 否则大标题会压到状态栏时钟上）
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(barHeight)) {
        val totalW = with(density) { maxWidth.toPx() }
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = TITLE_START_PADDING)
                .onSizeChanged { blockSize = it }
                .graphicsLayer {
                    if (blockSize.width > 0) {
                        val padStart = TITLE_START_PADDING.toPx()
                        // 大标题贴顶栏**下部**（Miuix 大标题就是这个位置），不是贴顶
                        val yLarge = (BAR_LARGE_HEIGHT.toPx() - blockSize.height - TITLE_TOP_PADDING.toPx())
                        val ySmall = (BAR_SMALL_HEIGHT.toPx() - blockSize.height) / 2f
                        translationX = lerp(0f, (totalW / 2f - padStart - blockSize.width / 2f), progress)
                        translationY = lerp(yLarge, ySmall, progress)
                    }
                },
        ) {
            Text(
                text = spec.title,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false,
            )
            if (spec.subtitle.isNotBlank()) {
                Spacer(Modifier.height(lerp(4.dp, 0.dp, progress)))
                Box(
                    modifier = Modifier
                        .height(lerp(SUBTITLE_HEIGHT, 0.dp, progress))
                        .alpha(1f - progress),
                ) {
                    Text(
                        text = spec.subtitle,
                        fontSize = SUBTITLE_SP,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .height(BAR_SMALL_HEIGHT)
                .padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** 大标题栏高度（含副标题与上下留白） */
private val BAR_LARGE_HEIGHT = 96.dp
/** 收起后的小标题栏高度，与 Miuix SmallTopAppBar 对齐 */
private val BAR_SMALL_HEIGHT = 56.dp
private val TITLE_LARGE_SP = 32.sp
private val TITLE_SMALL_SP = 18.sp
private val TITLE_START_PADDING = 20.dp
private val TITLE_TOP_PADDING = 12.dp
private val SUBTITLE_SP = 13.sp
private val SUBTITLE_HEIGHT = 18.dp

/** Tab 级顶栏横移时长（毫秒），与页面横移 `MainActivity.TAB_SWAP_MS` 保持一致。 */
private const val TAB_BAR_MS = 220

/**
 * Miuix 底栏：`NavigationBar` + 四个 `NavigationBarItem`。
 *
 * 图标取自 miuix-icons（Weeks / Notes / Community / Settings），
 * 选中态由 Miuix 自己处理（加粗 + 主题色 + 透明度动画）。
 */
@Composable
fun LanhangBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val items = bottomBarItems()
    NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                icon = item.icon,
                label = item.label,
            )
        }
    }
}

private data class BottomBarItem(val icon: ImageVector, val label: String)

@Composable
private fun bottomBarItems(): List<BottomBarItem> = listOf(
    BottomBarItem(MiuixIcons.Weeks, stringResource(R.string.tab_schedule)),
    BottomBarItem(MiuixIcons.Notes, stringResource(R.string.tab_grades)),
    BottomBarItem(MiuixIcons.Community, stringResource(R.string.tab_moments)),
    BottomBarItem(MiuixIcons.Settings, stringResource(R.string.tab_settings)),
)

/** 供 `RowScope` 内直接使用（预留：某些页面需要在导航栏里塞额外按钮）。 */
@Composable
fun RowScope.LanhangNavigationSpacer() = Unit
