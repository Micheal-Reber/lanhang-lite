package com.lanhang.lite.directory

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lanhang.lite.databinding.ItemDirectoryDeptBinding
import com.lanhang.lite.databinding.ItemDirectoryMemberBinding
import com.lanhang.lite.databinding.ItemDirectorySectionBinding

/**
 * 成员录列表行。
 *
 * 四种行类型：小节标题/面包屑、部门、成员、加载更多。用 notifyDataSetChanged 而不是
 * DiffUtil（与 OaHallAdapter 同一理由：列表规模几十项，换列表只在加载/搜索/翻页时发生）。
 */
sealed interface DirectoryRow {
    /** 小节标题（「下属部门」「成员」「成员」搜索分节）或下钻态的面包屑 */
    data class Section(val text: String) : DirectoryRow

    /** 一个部门（根节点或子部门） */
    data class Dept(
        val id: String,
        val name: String,
        val badge: String?,
        /** 搜索态里显示的上级路径；浏览态为空 */
        val pathLabel: String? = null,
    ) : DirectoryRow

    /** 一个成员 */
    data class Member(
        val id: String,
        val name: String,
        /** 学号 · 身份（取不到的项自动省掉）；不要塞手机号 */
        val subtitle: String?,
        val portraitUrl: String?,
    ) : DirectoryRow

    /** 触底加载更多的占位行 */
    data object LoadingMore : DirectoryRow
}

class DirectoryAdapter(
    private val onDeptClick: (DirectoryRow.Dept) -> Unit,
    private val onMemberClick: (DirectoryRow.Member) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<DirectoryRow>()

    fun submit(rows: List<DirectoryRow>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DirectoryRow.Section -> TYPE_SECTION
        is DirectoryRow.Dept -> TYPE_DEPT
        is DirectoryRow.Member -> TYPE_MEMBER
        DirectoryRow.LoadingMore -> TYPE_LOADING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION -> SectionVH(ItemDirectorySectionBinding.inflate(inflater, parent, false))
            TYPE_DEPT -> DeptVH(ItemDirectoryDeptBinding.inflate(inflater, parent, false))
            TYPE_MEMBER -> MemberVH(ItemDirectoryMemberBinding.inflate(inflater, parent, false))
            else -> LoadingVH(inflater.inflate(com.lanhang.lite.R.layout.item_directory_section, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is DirectoryRow.Section -> (holder as SectionVH).bind(row)
            is DirectoryRow.Dept -> (holder as DeptVH).bind(row)
            is DirectoryRow.Member -> (holder as MemberVH).bind(row)
            DirectoryRow.LoadingMore -> (holder as LoadingVH).bind()
        }
    }

    private inner class SectionVH(
        private val binding: ItemDirectorySectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: DirectoryRow.Section) {
            binding.tvSection.text = row.text
        }
    }

    private inner class DeptVH(
        private val binding: ItemDirectoryDeptBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: DirectoryRow.Dept) {
            binding.tvName.text = row.name
            // 右侧灰字：浏览态显示成员数，搜索态显示上级路径（两者不同时出现）
            val tail = row.pathLabel ?: row.badge
            binding.tvBadge.text = tail
            binding.tvBadge.visibility = if (tail.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.root.setOnClickListener { onDeptClick(row) }
        }
    }

    private inner class MemberVH(
        private val binding: ItemDirectoryMemberBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: DirectoryRow.Member) {
            binding.ivAvatar.bind(name = row.name, url = row.portraitUrl)
            binding.tvName.text = row.name
            binding.tvSubtitle.text = row.subtitle
            binding.tvSubtitle.visibility =
                if (row.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.root.setOnClickListener { onMemberClick(row) }
        }
    }

    /** 复用 section 行当「加载更多」提示（小灰字居中不够，直接显示文案即可） */
    private inner class LoadingVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            (itemView as? android.widget.TextView)?.apply {
                text = context.getString(com.lanhang.lite.R.string.directory_loading_more)
                gravity = android.view.Gravity.CENTER
            }
        }
    }

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_DEPT = 1
        const val TYPE_MEMBER = 2
        const val TYPE_LOADING = 3
    }
}
