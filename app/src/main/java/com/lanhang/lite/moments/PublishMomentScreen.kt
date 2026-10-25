package com.lanhang.lite.moments

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lanhang.lite.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 朋友圈式发表页最多 9 张图（与后端 `MomentsApi.publishFeed` 的 media 列表一致）。 */
const val MAX_PUBLISH_IMAGES = 9

/**
 * 发表动态页（整页，不再是弹窗）。
 *
 * 参考微信朋友圈的「这一刻的想法 + 九宫格选图」，但外观全部换成 Miuix：
 * 两张 `Card`（正文 / 图片），九宫格用等宽方格 + 12dp 圆角，
 * 末尾固定一个「＋」格子，选满 9 张自动消失。
 *
 * 发表动作不在这里——它在顶栏右上角的 `TextButton`（见 `ShellBars.LanhangTopBar`），
 * 与 Miuix 的二级页顶栏一致；按钮的可用状态由宿主决定。
 */
@Composable
fun PublishMomentScreen(
    text: String,
    onTextChange: (String) -> Unit,
    images: List<Uri>,
    onPickImages: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onPreviewImage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {

        // ---- 正文 ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(16.dp),
        ) {
            Column {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = stringResource(R.string.moments_publish_hint),
                    minLines = 5,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = "${text.length}/2000",
                        style = MiuixTheme.textStyles.footnote2,
                        color = scheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ---- 图片九宫格 ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(16.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.moments_publish_images),
                        style = MiuixTheme.textStyles.body1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${images.size}/$MAX_PUBLISH_IMAGES",
                        style = MiuixTheme.textStyles.footnote2,
                        color = scheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(12.dp))

                val tiles = images.size + if (images.size < MAX_PUBLISH_IMAGES) 1 else 0
                val rows = (tiles + 2) / 3
                for (row in 0 until rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (col in 0..2) {
                            val index = row * 3 + col
                            Box(modifier = Modifier.weight(1f)) {
                                when {
                                    index < images.size -> ImageTile(
                                        uri = images[index],
                                        onRemove = { onRemoveImage(index) },
                                        onPreview = { onPreviewImage(index) },
                                    )

                                    index == images.size && images.size < MAX_PUBLISH_IMAGES ->
                                        AddTile(onClick = onPickImages)

                                    else -> Spacer(Modifier.aspectRatio(1f))
                                }
                            }
                        }
                    }
                    if (row != rows - 1) Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.moments_publish_tip),
            style = MiuixTheme.textStyles.footnote2,
            color = scheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** 一张已选图片：等比方格 + 右上角删除按钮。 */
@Composable
private fun ImageTile(uri: Uri, onRemove: () -> Unit, onPreview: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(uri) {
                    detectTapGestures(onTap = { onPreview() }, onLongPress = { onPreview() })
                },
        )
        Icon(
            imageVector = MiuixIcons.Close,
            contentDescription = stringResource(R.string.moments_publish_remove_image),
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .clickable(onClick = onRemove)
                .padding(3.dp),
        )
    }
}

/** 「＋」格子：点开系统相册（Photo Picker，多选最多 9 张）。 */
@Composable
private fun AddTile(onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Add,
            contentDescription = stringResource(R.string.moments_publish_add_image),
            tint = scheme.onSurfaceVariantSummary,
            modifier = Modifier.size(26.dp),
        )
    }
}
