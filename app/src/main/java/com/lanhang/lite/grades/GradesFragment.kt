package com.lanhang.lite.grades

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.lanhang.lite.R
import com.lanhang.lite.core.LoadState
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.FragmentGradesBinding
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.showEmpty
import com.lanhang.lite.ui.showFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GradesFragment : Fragment() {

    private var _binding: FragmentGradesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: GradeAdapter
    private var semesters: List<GradeSemester> = emptyList()
    private var scoresState: LoadState<List<GradeItem>> = LoadState.Loading
    private val scores: List<GradeItem>
        get() = (scoresState as? LoadState.Ready)?.value.orEmpty()
    private var selectedTerm: String = ""
    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGradesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = GradeAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.swipe.setOnRefreshListener {
            if (selectedTerm.isNotEmpty()) loadScores(selectedTerm, network = true)
        }

        binding.progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            loadSemesters()
            // 协程取消后不能再碰 binding：view 可能已销毁，_binding 已置空
            _binding?.progress?.isVisible = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private suspend fun loadSemesters() {
        val cached = withContext(Dispatchers.IO) { GradesRepository.loadCachedSemesters() }
        if (!cached.isNullOrEmpty()) {
            semesters = cached
            pickDefaultTerm()
            renderTerms()
        }
        try {
            val fresh = withContext(Dispatchers.IO) { GradesRepository.refreshSemesters() }
            semesters = fresh
            pickDefaultTerm()
            renderTerms()
        } catch (e: Exception) {
            // 必须原样上抛：安静 return 只是结束本函数，协程仍会继续跑后面的非挂起收尾代码，
            // 而这时 view 已经销毁、_binding 为 null → NPE（切 Tab 时闪退就是这么来的）
            if (e.isCancellation) throw e
            if (semesters.isEmpty()) {
                val screen = _binding ?: return
                screen.tvEmpty.showFailure(e, R.string.grades_load_failed)
                toast(screen.tvEmpty.text.toString())
            }
        }
        if (selectedTerm.isEmpty() && semesters.isNotEmpty()) {
            selectedTerm = semesters.first().id.orEmpty()
        }
        if (selectedTerm.isNotEmpty()) loadScores(selectedTerm, network = false)
    }

    /** 优先用上次选择；否则当前学期；否则第一项 */
    private fun pickDefaultTerm() {
        val saved = Session.gradesTerm
        val current = semesters.firstOrNull { it.isCurrent }?.id
        selectedTerm = when {
            saved.isNotEmpty() && semesters.any { it.id == saved } -> saved
            !current.isNullOrEmpty() -> current
            else -> semesters.firstOrNull()?.id.orEmpty()
        }
    }

    /**
     * 学期筛选行：M3 filter chip（样式见 Widget.Lanhang.Chip.Filter）。
     * 选中态的容器色、描边、涟漪、48dp 触摸区全由官方样式负责，这里只绑定文案与选中状态。
     */
    private fun renderTerms() {
        val screen = _binding ?: return
        val row = screen.termRow
        row.removeAllViews()
        val inflater = LayoutInflater.from(row.context)
        for (term in semesters) {
            val id = term.id ?: continue
            val chip = inflater.inflate(R.layout.item_term_chip, row, false) as Chip
            chip.text = term.name
            chip.isChecked = id == selectedTerm
            chip.setOnClickListener {
                if (selectedTerm != id) {
                    selectedTerm = id
                    Session.gradesTerm = id
                    renderTerms()
                    loadScores(id, network = true)
                }
            }
            row.addView(chip)
        }
    }

    private fun loadScores(term: String, network: Boolean) {
        if (loading || term.isEmpty()) return
        val screen = _binding ?: return
        loading = true
        if (network) screen.swipe.isRefreshing = true else screen.progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var data = withContext(Dispatchers.IO) { GradesRepository.loadCachedScores(term) }
                if (network || data == null) {
                    data = withContext(Dispatchers.IO) { GradesRepository.refreshScores(term) }
                }
                scoresState = LoadState.Ready(data.orEmpty())
                renderScores()
            } catch (e: Exception) {
                if (e.isCancellation) throw e
                scoresState = LoadState.Failed(e)
                // view 已销毁时只更新状态，不回写控件
                val view = _binding ?: return@launch
                renderScores()
                toast(e.userMessage(view.root.context, R.string.grades_load_failed))
            } finally {
                // 必须放 finally：取消途中也要复位，否则 loading 卡在 true，
                // 重新进页面会静默不加载
                loading = false
                _binding?.progress?.isVisible = false
                _binding?.swipe?.isRefreshing = false
            }
        }
    }

    /** 成绩列表的三种状态只在这里落到控件上（加载中不渲染默认值） */
    private fun renderScores() {
        // view 可能已随切 Tab 销毁，此时保留状态、不碰控件
        if (_binding == null) return
        when (val state = scoresState) {
            is LoadState.Ready -> {
                adapter.submit(state.value)
                renderStats()
                if (state.value.isEmpty()) {
                    binding.tvEmpty.showEmpty(R.string.grades_empty)
                } else {
                    binding.tvEmpty.isVisible = false
                }
            }
            is LoadState.Failed -> binding.tvEmpty.showFailure(state.error, R.string.grades_load_failed)
            LoadState.Loading -> binding.tvEmpty.isVisible = false
        }
    }

    private fun renderStats() {
        val credits = scores.sumOf { it.creditValue ?: 0.0 }
        binding.tvCredits.text = formatNum(credits)
        binding.tvCount.text = scores.size.toString()

        val graded = scores.filter { it.scoreValue != null }
        val weighted = graded.sumOf { (it.scoreValue ?: 0) * (it.creditValue ?: 1.0) }
        val weightSum = graded.sumOf { it.creditValue ?: 1.0 }
        binding.tvAvg.text = if (weightSum > 0) String.format(java.util.Locale.CHINA, "%.1f", weighted / weightSum) else "--"
    }

    private fun formatNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.CHINA, "%.1f", v)

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun scoreColor(score: Int, context: android.content.Context): Int = when {
            score >= 90 -> Color.parseColor("#2E7D32")
            score >= 80 -> ContextCompat.getColor(context, R.color.nav_checked)
            score >= 60 -> Color.parseColor("#B26A00")
            else -> Color.parseColor("#C62828")
        }
    }
}

private class GradeAdapter : RecyclerView.Adapter<GradeAdapter.VH>() {

    val items = mutableListOf<GradeItem>()

    fun submit(list: List<GradeItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_grade, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvSub: TextView = itemView.findViewById(R.id.tvSub)
        private val tvScore: TextView = itemView.findViewById(R.id.tvScore)

        fun bind(item: GradeItem) {
            tvName.text = item.courseName.orEmpty()
            val parts = listOfNotNull(
                item.creditValue?.let {
                    itemView.context.getString(R.string.grades_credit, item.credit)
                },
                item.nature?.takeIf { it.isNotBlank() } ?: item.attribute,
                item.examMode?.takeIf { it.isNotBlank() }
            )
            tvSub.text = parts.joinToString(" · ")
            val score = item.scoreValue
            tvScore.text = item.score ?: "--"
            tvScore.setTextColor(
                if (score != null) GradesFragment.scoreColor(score, itemView.context)
                else androidx.core.content.ContextCompat.getColor(itemView.context, R.color.icon_muted)
            )
        }
    }
}
