package com.lanhang.lite.oa

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.lanhang.lite.R
import com.lanhang.lite.databinding.ItemOaAppBinding
import com.lanhang.lite.databinding.ItemOaSectionBinding

/**
 * 办事大厅宫格适配器。
 *
 * 行是「分组标题 + 应用」的扁平列表（[OaRow]），跨列由 Activity 里的 SpanSizeLookup 决定；
 * 标题跨满整行，应用一格。用 notifyDataSetChanged 而不是 DiffUtil：列表规模是几十项，
 * 换列表只在首次加载 / 搜索 / 刷新时发生，DiffUtil 的收益抵不过多一个差异类。
 *
 * 长按 = 移入 / 移出「常用功能」（2026-11-14 用户要求），由 Fragment 弹确认框处理。
 */
class OaHallAdapter(
    private val onApp: (OaApp) -> Unit,
    private val onAppLongClick: (OaApp) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<OaRow>()

    fun submit(list: List<OaRow>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is OaRow.Header) TYPE_HEADER else TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemOaSectionBinding.inflate(inflater, parent, false))
        } else {
            AppHolder(ItemOaAppBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is OaRow.Header -> (holder as HeaderHolder).bind(row.title)
            is OaRow.App -> (holder as AppHolder).bind(row.app, onApp, onAppLongClick)
        }
    }

    private class HeaderHolder(
        private val binding: ItemOaSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.tvSection.text = title
        }
    }

    private class AppHolder(
        private val binding: ItemOaAppBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: OaApp, onApp: (OaApp) -> Unit, onAppLongClick: (OaApp) -> Unit) {
            binding.tvName.text = app.name.orEmpty()
            // 本地入口（校园网 / 图书馆座位 / 赣青二课）没有服务端 Logo，
            // 用项目自带的矢量图并按主题色着色——单色图标不染成品牌色会像占位图。
            //
            // 2026-11-14 用户反馈「常用功能的几个图标显示有问题」：条纹矢量图直接摆在
            // 40dp 的图标位里，跟旁边**自带彩色底**的服务端 logo 完全不像，看着像没加载出来。
            // 现在给本地入口垫一块品牌色圆角底（bg_oa_local_icon）+ onPrimary 色的图标，
            // 三者才和周围格子是同一档视觉重量。内边距只在本地分支加（24dp 图标 + 各 8dp）。
            val localIcon = OaNativeApps.localIcon(app.id)
            if (localIcon != null) {
                // 本地入口走 ivLocal（自己的 ImageView + XML 里的 tint），
                // 与 Coil 加载的 ivLogo 互不干扰：取消请求的清空动作再也不会把图标抹掉。
                binding.iconBox.setBackgroundResource(R.drawable.bg_oa_local_icon)
                binding.ivLocal.isVisible = true
                binding.ivLocal.setImageResource(localIcon)
                binding.ivLogo.isVisible = false
                // 本格不再需要网络图，顺手把上一个格子的请求放掉（此时 ivLogo 不可见，清空无所谓）
                binding.ivLogo.load(null as Any?)
                android.util.Log.d("OaIconDbg", "local id=${app.id} icon=$localIcon")
            } else {
                // holder 会复用：上一个格子若是本地图标，底板要还原
                binding.iconBox.setBackground(null)
                binding.ivLocal.isVisible = false
                binding.ivLogo.isVisible = true
                // 恢复服务端默认 Logo；地址为空或加载失败时退回统一的本地占位图标。
                // 白底图先抠掉与边缘连通的白色，避免深色卡片上出现一块白方块。
                val logoUrl = app.logoUrl?.takeIf { it.isNotBlank() }
                binding.ivLogo.load(logoUrl, OaIconLoader.get(binding.root.context)) {
                    // 必须 true（AGENTS.md §5：图片加载禁止 crossfade(false)，否则换图硬闪）。
                    // 「别闪」这件事靠下面的 diskCacheKey + OaIconLoader 的缓存实现：
                    // 地址不变就一直命中本地图，压根不会重新播淡入。
                    crossfade(true)
                    // URL 即固定缓存键：不变就一直读本地，变更时才缓存新图。
                    diskCacheKey(logoUrl)
                    transformations(StripWhiteBackdropTransformation())
                    placeholder(R.drawable.ic_view_grid)
                    error(R.drawable.ic_view_grid)
                    fallback(R.drawable.ic_view_grid)
                }
                android.util.Log.d("OaIconDbg", "remote id=${app.id} logo=${app.logoUrl != null}")
            }
            binding.root.setOnClickListener { onApp(app) }
            binding.root.setOnLongClickListener {
                onAppLongClick(app)
                true
            }
            binding.root.contentDescription = app.name.orEmpty()
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_APP = 1
    }
}
