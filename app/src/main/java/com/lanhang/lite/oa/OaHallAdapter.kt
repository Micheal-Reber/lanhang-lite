package com.lanhang.lite.oa

import android.view.LayoutInflater
import android.view.ViewGroup
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
 */
class OaHallAdapter(
    private val onApp: (OaApp) -> Unit,
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
            is OaRow.App -> (holder as AppHolder).bind(row.app, onApp)
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

        fun bind(app: OaApp, onApp: (OaApp) -> Unit) {
            binding.tvName.text = app.name.orEmpty()
            // logo 是远程图：失败/为空都退回统一占位图标，避免格子空一块。
            // 各家上传的 logo 一半是白底图，深色卡片上会变成一块白方块，先抠掉白底再显示。
            binding.ivLogo.load(app.logoUrl?.takeIf { it.isNotBlank() }) {
                crossfade(true)
                transformations(StripWhiteBackdropTransformation())
                placeholder(R.drawable.ic_view_grid)
                error(R.drawable.ic_view_grid)
                fallback(R.drawable.ic_view_grid)
            }
            binding.root.setOnClickListener { onApp(app) }
            binding.root.contentDescription = app.name.orEmpty()
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_APP = 1
    }
}
