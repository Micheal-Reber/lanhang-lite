package com.lanhang.lite.moments

import android.content.Context
import android.content.SharedPreferences
import com.lanhang.lite.R
import com.lanhang.lite.session.Session

/**
 * 工作圈「关键词过滤」的规则与持久化。
 *
 * 只影响**本机渲染**，不请求、不上报、不删服务端内容 —— 关掉开关列表就恢复全量。
 *
 * 三条硬规则（按判定顺序）：
 * 1. **自己发的不过滤**：否则用户会以为自己的动态没了；
 * 2. **失物招领豁免**：命中 [MomentTextMatcher.isProtected] 就放行。样本里 18% 是找东西的帖子，
 *    它们全都带手机号和「联系我」，不豁免必然误杀；
 * 3. 最后才判档位 / 自定义词。
 *
 * 档位默认只开「广告推广」：另外三档在实测里都有误伤
 * （「有偿代拿」是同学互助、「转让」可能是正常内容），交给用户自己勾。
 */
object MomentsFilterStore {

    /** 预设分档。词表来自 120 条真实样本的命中统计，见 docs/moments-keyword-search-and-filter.md */
    enum class Preset(
        val key: String,
        val titleRes: Int,
        val words: List<String>,
        val defaultOn: Boolean
    ) {
        /** 高置信广告词：实测命中 10 条里 9 条确为广告 */
        AD(
            "ad", R.string.moments_filter_preset_ad,
            listOf(
                // 卖货话术
                "原价", "福利价", "特惠", "学生价", "领卷", "优惠券", "秒杀", "包邮", "返红包",
                "代购", "开团", "接单", "下单",
                // 兼职刷单
                "兼职", "招募", "日结", "刷单", "代刷", "代考",
                // 引流
                "加vx", "加v", "加微信", "vx",
                // 培训招生
                "招生", "集训", "实训", "训练营"
            ),
            defaultOn = true
        ),

        /** 「有偿」会打到同学之间的跑腿互助，所以默认关 */
        PART_TIME(
            "part_time", R.string.moments_filter_preset_part_time,
            listOf("招人", "家教", "助教", "客服", "有偿", "招聘"),
            defaultOn = false
        ),

        SECOND_HAND(
            "second_hand", R.string.moments_filter_preset_second_hand,
            listOf("出二手", "收二手", "带价来", "出闲置", "转让", "面交"),
            defaultOn = false
        ),

        HOUSING(
            "housing", R.string.moments_filter_preset_housing,
            listOf("出租", "合租", "转租", "单间", "求租"),
            defaultOn = false
        )
    }

    /** 命中的规则：预设档位或自定义规则，二选一（界面用来告诉用户「为什么这条被藏了」） */
    data class RuleHit(val preset: Preset?, val customRule: String?)

    /** 添加自定义规则的结果（每一种都要给用户一句不同的提示，否则「点了没反应」） */
    enum class AddResult { OK, EMPTY, TOO_LONG, TOO_MANY_WORDS, TOO_MANY_RULES, DUPLICATE }

    private const val FILE = "moments_filter"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESETS = "presets"
    private const val KEY_CUSTOM = "custom_rules"
    private const val KEY_SEEDED = "seeded"

    private const val SEPARATOR = '\n'

    /** 自定义规则条数上限：再多就是自己给自己找麻烦，且每条都要对每条帖子跑一遍 */
    const val MAX_CUSTOM_RULES = 30

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!sp.getBoolean(KEY_SEEDED, false)) {
            // 首次运行只开默认档，并写盘 —— 这样用户关掉之后不会被「默认值」重新打开
            savePresets(Preset.entries.filter { it.defaultOn }.map { it.key }.toSet())
            sp.edit().putBoolean(KEY_SEEDED, true).apply()
        }
    }

    // ------------------------------------------------------------------ 开关

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    fun isPresetOn(preset: Preset): Boolean = preset.key in enabledPresetKeys()

    fun setPreset(preset: Preset, on: Boolean) {
        val keys = enabledPresetKeys().toMutableSet()
        if (on) keys.add(preset.key) else keys.remove(preset.key)
        savePresets(keys)
    }

    private fun enabledPresetKeys(): Set<String> {
        val raw = sp.getString(KEY_PRESETS, null) ?: return emptySet()
        if (raw.isEmpty()) return emptySet()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    private fun savePresets(keys: Set<String>) {
        sp.edit().putString(KEY_PRESETS, keys.joinToString(SEPARATOR.toString())).apply()
    }

    // ------------------------------------------------------------------ 自定义词

    /** 每条规则是「词1 词2」形式，词之间是 **AND**（必须同时出现） */
    fun customRules(): List<String> {
        val raw = sp.getString(KEY_CUSTOM, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    fun addCustomRule(input: String): AddResult {
        val rule = normalizeRule(input)
        if (rule.isEmpty()) return AddResult.EMPTY
        if (rule.length > MomentTextMatcher.MAX_QUERY_LENGTH) return AddResult.TOO_LONG
        if (MomentTextMatcher.words(rule).size > MomentTextMatcher.MAX_RULE_WORDS) {
            return AddResult.TOO_MANY_WORDS
        }
        val existing = customRules()
        if (existing.size >= MAX_CUSTOM_RULES) return AddResult.TOO_MANY_RULES
        // 判重要按归一化后的形态：`ＶＸ` 与 `vx` 是同一条
        val norm = MomentTextMatcher.normalize(rule)
        if (existing.any { MomentTextMatcher.normalize(it) == norm }) return AddResult.DUPLICATE
        saveCustom(existing + rule)
        return AddResult.OK
    }

    fun removeCustomRule(rule: String) {
        val existing = customRules()
        if (saveCustom(existing - rule)) return
        // 文本不完全相等时按归一化兜底（列表里显示的是原文，正常不会走到这里）
        val norm = MomentTextMatcher.normalize(rule)
        saveCustom(existing.filterNot { MomentTextMatcher.normalize(it) == norm })
    }

    private fun saveCustom(rules: List<String>): Boolean {
        sp.edit().putString(KEY_CUSTOM, rules.joinToString(SEPARATOR.toString())).apply()
        return true
    }

    private fun normalizeRule(input: String): String =
        input.trim().split(' ', '\u3000', '\n', '\t', '\r')
            .filter { it.isNotBlank() }
            .joinToString(" ")

    // ------------------------------------------------------------------ 判定

    /** 当前生效的词数（设置页摘要用） */
    fun activeKeywordCount(): Int =
        Preset.entries.filter { isPresetOn(it) }.sumOf { it.words.size } +
            customRules().size

    fun activePresets(): List<Preset> = Preset.entries.filter { isPresetOn(it) }

    /**
     * 这条帖子该不该被过滤；返回 null 表示放行。
     *
     * @param text 帖子正文（不含作者名 —— 按人名过滤会把整条时间线清空）
     * @param authorId 作者 userId，用来豁免自己发的
     */
    fun match(text: String, authorId: String?): RuleHit? {
        if (!enabled) return null
        if (text.isBlank()) return null
        if (authorId != null && authorId == Session.userId) return null
        if (MomentTextMatcher.isProtected(text)) return null

        for (preset in activePresets()) {
            if (MomentTextMatcher.matchesAny(text, preset.words)) {
                return RuleHit(preset = preset, customRule = null)
            }
        }
        for (rule in customRules()) {
            val words = MomentTextMatcher.words(rule)
            if (words.isNotEmpty() && MomentTextMatcher.matchesAll(text, words)) {
                return RuleHit(preset = null, customRule = rule)
            }
        }
        return null
    }

    /** 设置页摘要：`已开启 · 广告 28 个关键词` / `已关闭` */
    fun summary(context: Context): String {
        if (!enabled) return context.getString(R.string.moments_filter_summary_off)
        val presets = activePresets()
        if (presets.isEmpty()) {
            return context.getString(
                R.string.moments_filter_summary_custom_only,
                customRules().size
            )
        }
        return context.getString(
            R.string.moments_filter_summary_on,
            context.getString(presets.first().titleRes),
            activeKeywordCount()
        )
    }
}
