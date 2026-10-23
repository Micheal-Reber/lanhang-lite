package com.lanhang.lite.grades

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
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
import com.lanhang.lite.R
import com.lanhang.lite.databinding.FragmentGradesBinding
import com.lanhang.lite.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GradesFragment : Fragment() {

    private var _binding: FragmentGradesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: GradeAdapter
    private var semesters: List<GradeSemester> = emptyList()
    private var scores: List<GradeItem> = emptyList()
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
            binding.progress.isVisible = false
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
            if (semesters.isEmpty()) {
                binding.tvEmpty.isVisible = true
                binding.tvEmpty.text = getString(R.string.grades_load_failed, e.message.orEmpty())
                toast(getString(R.string.grades_load_failed, e.message.orEmpty()))
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

    private fun renderTerms() {
        val row = binding.termRow
        row.removeAllViews()
        val ctx = requireContext()
        val padH = (14 * resources.displayMetrics.density).toInt()
        val padV = (7 * resources.displayMetrics.density).toInt()
        for (term in semesters) {
            val id = term.id ?: continue
            val tv = TextView(ctx).apply {
                text = term.name
                gravity = Gravity.CENTER
                setPadding(padH, padV, padH, padV)
                textSize = 13f
            }
            val bg = ContextCompat.getDrawable(ctx, R.drawable.bg_week_pill)!!.mutate() as GradientDrawable
            val selected = id == selectedTerm
            if (selected) {
                bg.setColor(ContextCompat.getColor(ctx, R.color.week_pill_bg))
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.week_pill_text))
                tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                bg.setColor(Color.TRANSPARENT)
                bg.setStroke(
                    (resources.displayMetrics.density).toInt(),
                    ContextCompat.getColor(ctx, R.color.gray)
                )
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.icon_muted))
                tv.typeface = android.graphics.Typeface.DEFAULT
            }
            tv.background = bg
            tv.setOnClickListener {
                if (selectedTerm != id) {
                    selectedTerm = id
                    Session.gradesTerm = id
                    renderTerms()
                    loadScores(id, network = true)
                }
            }
            row.addView(tv)
        }
    }

    private fun loadScores(term: String, network: Boolean) {
        if (loading || term.isEmpty()) return
        loading = true
        if (network) binding.swipe.isRefreshing = true else binding.progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var data = withContext(Dispatchers.IO) { GradesRepository.loadCachedScores(term) }
                if (network || data == null) {
                    data = withContext(Dispatchers.IO) { GradesRepository.refreshScores(term) }
                }
                scores = data.orEmpty()
                adapter.submit(scores)
                renderStats()
                binding.tvEmpty.isVisible = scores.isEmpty()
                binding.tvEmpty.text = getString(R.string.grades_empty)
            } catch (e: Exception) {
                if (scores.isEmpty()) {
                    binding.tvEmpty.isVisible = true
                    binding.tvEmpty.text = getString(R.string.grades_load_failed, e.message.orEmpty())
                }
                toast(getString(R.string.grades_load_failed, e.message.orEmpty()))
            }
            loading = false
            binding.progress.isVisible = false
            binding.swipe.isRefreshing = false
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
