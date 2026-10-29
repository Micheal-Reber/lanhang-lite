package com.lanhang.lite.moments

import android.content.Context
import android.content.SharedPreferences

/**
 * 搜索历史（最多 5 条，只存本机）。
 *
 * 存的是**用户输入的原文**而不是归一化结果：归一化会把全角标点改成半角，
 * 回填到输入框里会让人以为自己打错了。
 */
object MomentSearchStore {

    private const val FILE = "moments_search"
    private const val KEY_HISTORY = "history"
    private const val SEPARATOR = '\n'

    /** 再多也记不住，还会把首屏的历史区撑满 */
    const val MAX_HISTORY = 5

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun history(): List<String> {
        val raw = sp.getString(KEY_HISTORY, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    /** 最近使用置顶 + 去重（按归一化判重，避免 `ＶＸ` 和 `vx` 各占一条） */
    fun remember(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val norm = MomentTextMatcher.normalize(q)
        val list = ArrayList<String>(MAX_HISTORY)
        list.add(q)
        for (old in history()) {
            if (MomentTextMatcher.normalize(old) == norm) continue
            list.add(old)
            if (list.size >= MAX_HISTORY) break
        }
        save(list)
    }

    fun remove(query: String) {
        val norm = MomentTextMatcher.normalize(query)
        save(history().filterNot { MomentTextMatcher.normalize(it) == norm })
    }

    fun clear() = save(emptyList())

    private fun save(list: List<String>) {
        sp.edit().putString(KEY_HISTORY, list.joinToString(SEPARATOR.toString())).apply()
    }
}
