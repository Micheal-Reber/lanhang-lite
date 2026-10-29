package com.lanhang.lite.moments

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import androidx.core.graphics.ColorUtils

/**
 * 工作圈的关键词匹配器：**搜索**和**过滤**共用这一套。
 *
 * 两边共用是刻意的 —— 归一化规则不一致会出现「搜得到、列表里却没有」这种自相矛盾的现场。
 *
 * 规则（依据 120 条真实时间线实测，见 docs/moments-keyword-search-and-filter.md）：
 * - 子串包含，不做分词（中文分词要词库，且「耳机」在「白色倍思耳机」里子串命中就是对的）
 * - 归一化：全角转半角、转小写、连续空白折叠成一个 —— 中文输入法很容易打出全角 `ＱＱ`
 * - 不支持正则：普通用户不会写，且把输入当正则编译有 ReDoS 风险
 * - 多词组合由调用方决定：搜索取 AND（收窄），过滤取 OR（命中任一就藏）
 *
 * 归一化会改动字符数（空白折叠），所以内部同时维护「归一化下标 → 原文下标」的映射，
 * 高亮才能落在正确的字上，而不是整体左移。
 */
object MomentTextMatcher {

    /** 原文中一段命中的区间（半开区间，下标对应**原文**） */
    data class Hit(val start: Int, val end: Int)

    /**
     * 失物招领豁免词。
     *
     * 样本里 18% 是找东西/还东西的帖子，它们几乎都带手机号和「联系我」，
     * 与广告的字面特征完全重合。命中这些词就一律不过滤 —— 宁可漏掉一条广告，
     * 也不能把「捡到耳机」吞掉（那正是最需要被看见的内容）。
     */
    private val PROTECTED = arrayOf(
        "捡到", "捡了", "捡的", "丢了", "丢在", "丢的", "谁的", "失物", "失主",
        "忘拿", "拿错", "落在", "掉在", "领回", "招领", "寻物", "遗失"
    )

    /** 单个查询词的长度上限：防呆，也避免用户把整段文章粘进来 */
    const val MAX_QUERY_LENGTH = 30

    /** 一条自定义过滤规则里最多几个词（词越多越难命中，超过就没意义了） */
    const val MAX_RULE_WORDS = 4

    // ------------------------------------------------------------------ 归一化

    /**
     * 归一化文本：全角 → 半角、小写、连续空白折叠成单个半角空格。
     * 只用于比较，**不要**拿去显示 —— 会把用户的全角标点改成半角。
     */
    fun normalize(raw: String): String = normalizeWithMap(raw).first

    /** 归一化并返回下标映射：`map[归一化下标] = 原文下标` */
    private fun normalizeWithMap(raw: String): Pair<String, IntArray> {
        val sb = StringBuilder(raw.length)
        val map = IntArray(raw.length)
        var lastSpace = false
        for (i in raw.indices) {
            var c = raw[i]
            // 全角 ASCII（！-～）整体左移 0xFEE0；表意空格单独处理
            if (c in '\uFF01'..'\uFF5E') c = (c.code - 0xFEE0).toChar() else if (c == '\u3000') c = ' '
            c = Character.toLowerCase(c)
            val space = c == ' ' || c == '\n' || c == '\r' || c == '\t'
            if (space) {
                if (lastSpace) continue
                c = ' '
                lastSpace = true
            } else {
                lastSpace = false
            }
            map[sb.length] = i
            sb.append(c)
        }
        return sb.toString() to map.copyOf(sb.length)
    }

    /** 查询串拆词：按空白拆分、逐词归一化、去重、长词在前（长词优先命中，高亮覆盖更自然） */
    fun words(query: String): List<String> =
        query.split(' ', '\u3000', '\n', '\t', '\r')
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }

    // ------------------------------------------------------------------ 匹配

    /** 是否包含任一关键词（过滤档位用：命中一个就够） */
    fun matchesAny(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val (norm, _) = normalizeWithMap(text)
        return words.any { it.isNotEmpty() && norm.contains(it) }
    }

    /** 是否全部关键词都出现（搜索、多词自定义规则用：AND 收窄） */
    fun matchesAll(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val (norm, _) = normalizeWithMap(text)
        return words.all { it.isNotEmpty() && norm.contains(it) }
    }

    /** 单关键词是否命中（含归一化），给「作者名」这类短文本用 */
    fun contains(text: String, word: String): Boolean {
        val w = normalize(word)
        if (w.isEmpty()) return false
        return normalize(text).contains(w)
    }

    /** 是否命中失物招领豁免词 */
    fun isProtected(text: String): Boolean {
        if (text.isEmpty()) return false
        val (norm, _) = normalizeWithMap(text)
        return PROTECTED.any { norm.contains(it) }
    }

    /** 命中的所有区间（原文下标），已按起点排序并合并重叠段 */
    fun hits(text: String, words: List<String>): List<Hit> {
        if (text.isEmpty() || words.isEmpty()) return emptyList()
        val (norm, map) = normalizeWithMap(text)
        val out = ArrayList<Hit>()
        for (w in words) {
            if (w.isEmpty()) continue
            var from = 0
            while (true) {
                val i = norm.indexOf(w, from)
                if (i < 0) break
                out.add(Hit(map[i], map[i + w.length - 1] + 1))
                from = i + 1
            }
        }
        return merge(out)
    }

    private fun merge(input: List<Hit>): List<Hit> {
        if (input.size <= 1) return input
        val sorted = input.sortedBy { it.start }
        val out = ArrayList<Hit>(sorted.size)
        var cur = sorted.first()
        for (h in sorted.drop(1)) {
            if (h.start <= cur.end) {
                // 相邻或重叠：并成一段，避免同一个字被叠两层底色（透明度会叠加变深）
                cur = Hit(cur.start, maxOf(cur.end, h.end))
            } else {
                out.add(cur)
                cur = h
            }
        }
        out.add(cur)
        return out
    }

    /**
     * 给命中的片段加底色，用于搜索结果的正文。
     *
     * @param accent 主题主色；这里按 40% 透明度铺底，文字颜色不动，深色模式下也读得清
     */
    fun highlight(text: String, words: List<String>, accent: Int): CharSequence {
        val hits = hits(text, words)
        if (hits.isEmpty()) return text
        val span = SpannableString(text)
        val bg = ColorUtils.setAlphaComponent(accent, HIGHLIGHT_ALPHA)
        for (h in hits) {
            if (h.start < 0 || h.end > text.length || h.start >= h.end) continue
            span.setSpan(BackgroundColorSpan(bg), h.start, h.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }

    /** 0x66 ≈ 40%：再高就把字压住了，再低看不见 */
    private const val HIGHLIGHT_ALPHA = 0x66
}
