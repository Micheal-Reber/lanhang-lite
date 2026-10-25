package com.lanhang.lite.moments

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lanhang.lite.R
import com.lanhang.lite.ui.miuix.rememberBottomSafeInsetDp
import com.lanhang.lite.ui.miuix.refreshTexts
import com.lanhang.lite.ui.miuix.showMiuixConfirm
import com.lanhang.lite.ui.miuix.showMiuixInput
import com.lanhang.lite.ui.miuix.showMiuixInfo
import com.lanhang.lite.ui.shell.ReportListScroll
import com.lanhang.lite.session.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工作圈（Miuix 版）。
 *
 * 一条动态一张卡片：头像 + 姓名/部门 + 时间 → 正文 → 九宫格 → 点赞/评论 → 评论区。
 * 发布与评论用 Miuix 的 `OverlayDialog` + `TextField`（不再是 Material 的 EditText 弹窗）。
 */
@Composable
fun MomentsScreen(
    items: List<MomentItem>,
    loading: Boolean,
    refreshing: Boolean,
    ended: Boolean,
    emptyText: String?,
    revision: Int,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLike: (MomentItem) -> Unit,
    onPostComment: (MomentItem, String?, String) -> Unit,
    onDeleteComment: (MomentItem, CommentEntry) -> Unit,
    onDeletePost: (MomentItem) -> Unit,
    onImage: (List<String>, Int, android.graphics.Rect?) -> Unit,
    onOpenPublish: () -> Unit,
) {
    var commentTarget by remember { mutableStateOf<Pair<MomentItem, String?>?>(null) }
    var commentText by remember { mutableStateOf("") }
    var myCommentsTarget by remember { mutableStateOf<MomentItem?>(null) }
    var commentDeleteTarget by remember { mutableStateOf<Pair<MomentItem, CommentEntry>?>(null) }
    var deleteTarget by remember { mutableStateOf<MomentItem?>(null) }

    val listState = rememberLazyListState()
    // 往下滑 → 顶栏大标题收起为居中小标题（见 ShellScroll）
    ReportListScroll(listState)

    // 滚到底部自动翻页（对齐原来的 onScrolled 逻辑：距末尾 3 条就预取）
    LaunchedEffect(listState, items.size, ended, loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (!loading && !ended && last >= items.size - 3) onLoadMore()
            }
    }

    // 不透明根：切 Tab / 发表页推入时两页会重叠，透明根会透出下层
    Box(modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            refreshTexts = refreshTexts(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading && items.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    items.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = emptyText ?: stringResource(R.string.moments_empty),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 96.dp),
                    ) {
                        itemsIndexed(
                            items,
                            key = { _, item -> item.feed.feed_id ?: item.hashCode().toString() },
                        ) { index, item ->
                            MomentCard(
                                item = item,
                                revision = revision,
                                onDeletePost = { deleteTarget = item },
                                onLike = { onLike(item) },
                                onComment = { commentTarget = item to null },
                                onReply = { userId -> commentTarget = item to userId },
                                onCommentsLongPress = { myCommentsTarget = item },
                                onCommentLongPress = { comment -> commentDeleteTarget = item to comment },
                                onImage = onImage,
                            )
                            // 2026-10-25 用户要求：不同帖子之间加一条分隔线
                            if (index < items.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (ended) {
                            item("end") {
                                Text(
                                    text = stringResource(R.string.moments_no_more),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onOpenPublish,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp),
        ) {
            Icon(
                imageVector = MiuixIcons.Add,
                contentDescription = stringResource(R.string.moments_cd_publish),
                tint = Color.White,
                modifier = Modifier.padding(14.dp),
            )
        }

    }

    // ---- 发表评论 / 回复：统一输入对话框 ----
    // 2026-10-25 用户反馈：①遮罩只盖住顶栏下方（顶栏没被压暗）；②没有进出场动画。
    // 根因是原来做成页面内浮层。现在改成真正的 Dialog 窗口（showMiuixInput），
    // 遮罩覆盖整屏、动画与学年选择完全一致。
    val replyTarget = commentTarget
    val replyCtx = LocalContext.current
    LaunchedEffect(replyTarget) {
        val target = replyTarget ?: return@LaunchedEffect
        commentTarget = null
        val title = if (target.second.isNullOrEmpty()) {
            replyCtx.getString(R.string.moments_comment_title)
        } else {
            replyCtx.getString(R.string.moments_reply_title, UserInfoCache.nameOf(target.second))
        }
        showMiuixInput(
            context = replyCtx,
            title = title,
            placeholder = replyCtx.getString(R.string.moments_comment_hint),
            confirmText = replyCtx.getString(R.string.common_send),
            dismissText = replyCtx.getString(R.string.common_cancel),
            onConfirm = { body -> onPostComment(target.first, target.second, body) },
        )
    }

    // ---- 删除自己发布的动态（右下角「删除」按钮 / 长按卡片触发）----
    // 统一用 Miuix 对话框（ui/miuix/MiuixDialog.kt），和其它页面弹窗同款且贴底栏
    val pending = deleteTarget
    val ctx = LocalContext.current
    val delTitle = stringResource(R.string.moments_delete_post)
    val delMsg = stringResource(R.string.moments_delete_post_confirm)
    val delConfirm = stringResource(R.string.moments_delete)
    val delCancel = stringResource(R.string.common_cancel)
    LaunchedEffect(pending) {
        val item = pending ?: return@LaunchedEffect
        deleteTarget = null
        showMiuixConfirm(
            context = ctx,
            title = delTitle,
            message = delMsg,
            confirmText = delConfirm,
            dismissText = delCancel,
            onConfirm = { onDeletePost(item) },
        )
    }

    // ---- 删除某条评论（长按评论触发）----
    val pendingComment = commentDeleteTarget
    val delCommentTitle = stringResource(R.string.moments_delete_comment)
    val delCommentMsg = stringResource(R.string.moments_delete_comment_confirm)
    val delCommentOwnOnly = stringResource(R.string.moments_delete_comment_own_only)
    LaunchedEffect(pendingComment) {
        val target = pendingComment ?: return@LaunchedEffect
        commentDeleteTarget = null
        val (moment, comment) = target
        if (comment.user_id != Session.userId) {
            // 服务端只允许删自己的评论，先拦一道，避免无意义的失败请求
            showMiuixInfo(
                context = ctx,
                title = delCommentTitle,
                message = delCommentOwnOnly,
                confirm = delCancel,
            )
        } else {
            showMiuixConfirm(
                context = ctx,
                title = delCommentTitle,
                message = delCommentMsg,
                confirmText = delConfirm,
                dismissText = delCancel,
                onConfirm = { onDeleteComment(moment, comment) },
            )
        }
    }

}


/**
 * 点赞 / 评论的小胶囊：图标 + 数字。
 *
 * 用户反馈「赞与评论没有对应的图标装饰」——原来只有一行纯文字，看不出这是两个动作。
 * 现在左侧固定给一个 15dp 的矢量图标（心形 / 气泡），点赞后心形实心并转暖红。
 */
@Composable
private fun MomentActionChip(
    iconRes: Int,
    text: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (accent) Color(0xFFE94634) else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val fill = if (accent) Color(0x1AE94634) else MiuixTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = tint,
        )
        Spacer(Modifier.width(5.dp))
        Text(text = text, style = MiuixTheme.textStyles.footnote1, color = tint)
    }
}

@Composable
private fun MomentCard(
    item: MomentItem,
    revision: Int,
    onDeletePost: () -> Unit,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onReply: (String) -> Unit,
    onCommentsLongPress: () -> Unit,
    onOpenProfile: (String) -> Unit = {},
    onCommentLongPress: (CommentEntry) -> Unit,
    onImage: (List<String>, Int, android.graphics.Rect?) -> Unit,
) {
    // MomentItem.likes/comments 是普通可变列表（非 Compose 状态），
    // 靠 revision 变化时重新快照，才能让点赞/评论立刻反映到界面
    val likes = remember(item, revision) { item.likes.toList() }
    val comments = remember(item, revision) { item.comments.toList() }
    val likedByMe = remember(item, revision) { item.likedByMe }
    val isMine = remember(item) { item.feed.user_id == Session.userId }

    val user = UserInfoCache.get(item.feed.user_id)
    val name = user?.name ?: stringResource(R.string.moments_anonymous)
    val sub = listOfNotNull(user?.depart_name, user?.duty_name)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val time = item.feed.create_dt?.let { timeFormat.format(Date(it)) }.orEmpty()

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // 长按卡片 = 我的动态菜单（删除自己发布的内容）；普通点击不拦截，图片/评论照旧
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { if (isMine) onDeletePost() },
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = user?.portrait_url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.secondaryContainer),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.headline2,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sub.isNotEmpty()) {
                        Text(
                            text = sub,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = time,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            val text = item.content.text.orEmpty()
            if (text.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(text = text, style = MiuixTheme.textStyles.body1)
            }

            val media = item.content.media
                ?.filter { !it.displayUrl.isNullOrBlank() }
                .orEmpty()
            if (media.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                MomentImageGrid(media, onImage)
            }

            Spacer(Modifier.height(6.dp))
            // 点赞 / 评论：图钉 + 数字（用户反馈原来只有纯文字，缺装饰图标）。
            // 图标用项目里已有的 Material Symbols 矢量（ic_favorite / ic_mode_comment）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MomentActionChip(
                    iconRes = if (likedByMe) R.drawable.ic_favorite else R.drawable.ic_favorite_outline,
                    text = if (likedByMe) {
                        stringResource(R.string.moments_liked, likes.size)
                    } else {
                        stringResource(R.string.moments_like, likes.size)
                    },
                    accent = likedByMe,
                    onClick = onLike,
                )
                MomentActionChip(
                    iconRes = R.drawable.ic_mode_comment,
                    text = stringResource(R.string.moments_comment_count, comments.size),
                    accent = false,
                    onClick = onComment,
                )
                Spacer(Modifier.weight(1f))
                // 2026-10-25 用户要求：自己发布的动态在右下角给一个「删除」，
                // 纯蓝色文字、不加底色/阴影
                if (isMine) {
                    Text(
                        text = stringResource(R.string.moments_delete),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDeletePost() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // 点赞名单 + 评论区
            if (likes.isNotEmpty() || comments.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MiuixTheme.colorScheme.dividerLine,
                )
                if (likes.isNotEmpty()) {
                    // 2026-10-25 用户反馈：谁点了赞看不出来是「点赞」，补一个和点赞按钮
                    // 同一个爱心图标做前缀
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_favorite),
                            contentDescription = null,
                            tint = Color(0xFFE94634),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = likes.joinToString("，") { UserInfoCache.nameOf(it.user_id) },
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (comments.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    val replyInline = stringResource(R.string.moments_reply_inline)
                    val separator = stringResource(R.string.moments_comment_separator)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = onCommentsLongPress,
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        comments.forEach { comment ->
                            val uid = comment.user_id
                            val replyTo = comment.reply_to?.takeIf { it.isNotEmpty() && it != uid }
                            // 2026-10-25 用户要求：评论区也要显示头像和姓名，姓名用主色蓝并
                            // 预留点击区域（后续个人主页做好后，点姓名/头像直接进个人主页）。
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 轻点 = 回复；长按 = 删除这条评论
                                    .combinedClickable(
                                        onClick = { if (!uid.isNullOrEmpty()) onReply(uid) },
                                        onLongClick = { onCommentLongPress(comment) },
                                    ),
                                verticalAlignment = Alignment.Top,
                            ) {
                                AsyncImage(
                                    model = UserInfoCache.get(uid)?.portrait_url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .padding(top = 1.dp)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.secondaryContainer)
                                        .clickable { if (!uid.isNullOrEmpty()) onOpenProfile(uid) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = UserInfoCache.nameOf(uid),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable {
                                                if (!uid.isNullOrEmpty()) onOpenProfile(uid)
                                            },
                                        )
                                        if (replyTo != null) {
                                            // 2026-10-25 用户要求：「回复」两个字保持次要灰，
                                            // 只有**姓名**用蓝色（可点击进个人主页的那两个名字）
                                            Text(
                                                text = buildAnnotatedString {
                                                    withStyle(
                                                        SpanStyle(
                                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                        )
                                                    ) { append(" $replyInline ") }
                                                    withStyle(
                                                        SpanStyle(
                                                            color = MiuixTheme.colorScheme.primary,
                                                            fontWeight = FontWeight.Medium,
                                                        )
                                                    ) { append(UserInfoCache.nameOf(replyTo)) }
                                                },
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Text(
                                        text = comment.content.orEmpty(),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentImageGrid(media: List<MediaItem>, onImage: (List<String>, Int, android.graphics.Rect?) -> Unit) {
    val urls = media.map { it.url?.takeIf { u -> u.isNotBlank() } ?: it.displayUrl.orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        media.chunked(3).forEachIndexed { rowIndex, rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowItems.forEachIndexed { colIndex, mediaItem ->
                    val index = rowIndex * 3 + colIndex
                    // 记录缩略图在窗口里的位置：预览页用它做「从缩略图放大 / 缩回原位」
                    var bounds by remember(index) { mutableStateOf<android.graphics.Rect?>(null) }
                    AsyncImage(
                        model = mediaItem.displayUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .onGloballyPositioned { coords ->
                                val b = coords.boundsInWindow()
                                bounds = android.graphics.Rect(
                                    b.left.toInt(), b.top.toInt(),
                                    b.right.toInt(), b.bottom.toInt(),
                                )
                            }
                            .clickable { onImage(urls, index, bounds) },
                    )
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
