package com.lanhang.lite.oa

import android.content.Context

/**
 * 「工作」Tab 顶部「常用功能」分组的本地存储。
 *
 * 就是一个 SharedPreferences 字符串（逗号分隔、**有序**——用户先加的排前面），
 * 规模最多几十条，不值得上 DataStore / Room。
 *
 * 首初始化的默认三项（2026-11-14 用户要求）：校园网、图书馆座位、赣青二课。
 * 用「key 不存在 = 未初始化」区分「从没设过」和「用户主动清空」——后者要尊重，
 * 不能下次打开又把默认三项塞回来。
 */
object OaCommonStore {

    private const val PREF = "lanhang_oa_common"
    private const val KEY_IDS = "ids"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 常用功能里的条目 id，按加入顺序。 */
    fun load(ctx: Context): List<String> {
        val sp = sp(ctx)
        val raw = sp.getString(KEY_IDS, null)
        if (raw == null) {
            val defaults = listOf(
                OaNativeApps.CAMPUS_NET_APP_ID,
                OaNativeApps.LIBRARY_SEAT_APP_ID,
                OaNativeApps.GQHW_APP_ID
            )
            sp.edit().putString(KEY_IDS, defaults.joinToString(",")).apply()
            return defaults
        }
        return raw.split(',').filter { it.isNotBlank() }
    }

    fun isCommon(ctx: Context, id: String): Boolean = id in load(ctx)

    fun add(ctx: Context, id: String) {
        val ids = load(ctx)
        if (id in ids) return
        save(ctx, ids + id)
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, load(ctx) - id)
    }

    private fun save(ctx: Context, ids: List<String>) {
        sp(ctx).edit().putString(KEY_IDS, ids.joinToString(",")).apply()
    }
}
