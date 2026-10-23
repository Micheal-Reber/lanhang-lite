package com.lanhang.lite.moments

import com.lanhang.lite.R
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.net.StaffBrief
import com.lanhang.lite.schedule.AppContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 工作圈用户信息缓存：userId → 姓名/头像，批量解析走主 API POST /staffs/batch（code 10000） */
object UserInfoCache {
    private val cache = ConcurrentHashMap<String, StaffBrief>()

    fun get(id: String?): StaffBrief? = id?.let { cache[it] }

    fun nameOf(id: String?): String = get(id)?.name?.takeIf { it.isNotBlank() }
        ?: AppContext.instance.getString(R.string.moments_anonymous)

    suspend fun resolve(ids: Collection<String>) {
        val missing = ids.filter { it.isNotEmpty() && !cache.containsKey(it) }.distinct()
        if (missing.isEmpty()) return
        try {
            withContext(Dispatchers.IO) {
                RceApi.staffsBatch(missing)?.data?.forEach { brief ->
                    brief.id?.let { cache[it] = brief }
                }
            }
        } catch (_: Exception) {
        }
    }
}
