package com.lanhang.lite.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lanhang.lite.core.attachDoubleTapToTop
import com.lanhang.lite.R
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityMomentDetailBinding
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.settings.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单条动态详情。
 *
 * 两个入口都落到这里：消息中心点某条互动、系统通知点开。
 * 为什么不直接跳工作圈时间线：那条动态可能早就不在第一页了，
 * 而 `GET /feed/{id}` 能精确拿到它（含最新点赞/评论），所以单独开一页。
 *
 * 渲染复用 [MomentAdapter]，交互口径与工作圈列表完全一致
 * （点赞、评论/回复、长按删自己的评论、点图看大图、点头像进主页）。
 */
class MomentDetailActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityMomentDetailBinding

    private lateinit var adapter: MomentAdapter

    /** 点赞 / 评论 / 删评论：与搜索页共用同一套实现 */
    private lateinit var actions: MomentActions

    private val feedId: String by lazy { intent.getStringExtra(EXTRA_FEED_ID).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMomentDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // insets 会多次回调，基准值必须先取
        val baseBottomPadding = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0
            )
            binding.recycler.setPadding(
                binding.recycler.paddingLeft,
                binding.recycler.paddingTop,
                binding.recycler.paddingRight,
                baseBottomPadding + bars.bottom
            )
            insets
        }

        adapter = MomentAdapter(
            onLike = { item -> actions.toggleLike(item) },
            onComment = { item -> actions.askComment(item) },
            onImage = { urls, index -> ImagePreviewActivity.start(this, urls, index) },
            onReply = { item, userId -> actions.askComment(item, userId) },
            onCommentsLongPress = { item -> actions.showMyComments(item) },
            onUser = { userId -> UserProfileActivity.start(this, userId) },
            // 详情页删掉自己发的动态后没有内容可留，直接关页
            onDelete = { item -> actions.askDeletePost(item) { finish() } }
        )
        actions = MomentActions(this, adapter)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        // 双击回到顶部
        binding.recycler.attachDoubleTapToTop()
        binding.swipe.setOnRefreshListener { load() }

        if (feedId.isBlank()) {
            binding.tvEmpty.isVisible = true
            return
        }
        binding.progress.isVisible = true
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val feed = withContext(Dispatchers.IO) {
                    val fresh = MomentsApi.getFeed(feedId) ?: return@withContext null
                    val userIds = listOfNotNull(fresh.user_id) +
                        (fresh.likes?.mapNotNull { it.user_id } ?: emptyList()) +
                        (fresh.comments?.mapNotNull { it.user_id } ?: emptyList())
                    UserInfoCache.resolve(userIds)
                    fresh
                }
                if (feed == null) {
                    showMissing()
                } else {
                    adapter.submit(listOf(MomentItem(feed)))
                    binding.tvEmpty.isVisible = false
                }
            } catch (e: ApiException) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@MomentDetailActivity, getString(R.string.moments_detail_title), e)
                // 20009 = feed_noauth：对方把动态设成不可见，和「已删除」同一句提示即可
                showMissing(e.userMessage(this@MomentDetailActivity, R.string.moments_load_failed))
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@MomentDetailActivity, getString(R.string.moments_detail_title), e)
                showMissing(getString(R.string.moments_network_error))
            } finally {
                binding.progress.isVisible = false
                binding.swipe.isRefreshing = false
            }
        }
    }

    /** 拿不到动态时只留一句提示，并把已有内容清掉（避免展示过期数据） */
    private fun showMissing(message: String? = null) {
        adapter.clear()
        binding.tvEmpty.text = message ?: getString(R.string.moments_detail_missing)
        binding.tvEmpty.isVisible = true
    }

    companion object {
        /** 入口 extra：要看的那条动态 */
        private const val EXTRA_FEED_ID = "feed_id"

        fun intent(context: Context, feedId: String): Intent =
            Intent(context, MomentDetailActivity::class.java).putExtra(EXTRA_FEED_ID, feedId)

        fun start(context: Context, feedId: String) {
            context.startActivity(intent(context, feedId))
        }
    }
}
