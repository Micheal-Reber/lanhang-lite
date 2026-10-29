package com.lanhang.lite.schedule

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.lanhang.lite.R
import com.lanhang.lite.net.ApiClient
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.TextRef
import com.lanhang.lite.net.MiniappApi
import com.lanhang.lite.session.Session
import com.lanhang.lite.widget.ScheduleWidgetUpdater
import java.io.File

/**
 * 课表数据仓库：
 * - 首次加载：优先读本地缓存，无缓存则网络拉取
 * - 刷新：网络拉取并覆盖缓存
 */
object ScheduleRepository {
    private const val TAG = "ScheduleRepo"
    private val gson = Gson()
    private lateinit var cacheFile: File
    private lateinit var appDir: File

    fun init(context: Context) {
        appDir = context.filesDir
        cacheFile = File(appDir, "schedule_cache.json")
    }

    /** 读本地缓存；null 表示无缓存或解析失败 */
    fun loadCached(): ScheduleResponse? {
        if (!cacheFile.exists()) return null
        return try {
            val json = cacheFile.readText()
            gson.fromJson(json, ScheduleResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "loadCached failed: $e")
            null
        }
    }

    /**
     * 读某周缓存；null 表示无缓存、解析失败，或者**这一周的缓存比整学期缓存还旧**。
     *
     * 为什么必须比时间戳：下拉刷新走的是 `refresh(null)`，只覆盖整学期的
     * `schedule_cache.json`，不会碰切周时拉下来的 `schedule_wN.json`。教务把某节课移除后，
     * 刷新看是没了，但冷启动读回旧周缓存又把它"复活"（2026-10-26 用户反馈）。
     * 所以整学期缓存一旦比周缓存新，周缓存就整个作废：整学期数据本身就是最新全量。
     */
    fun loadCachedWeek(week: Int): ScheduleResponse? {
        val f = File(appDir, "schedule_w$week.json")
        if (!f.exists()) return null
        if (cacheFile.exists() && f.lastModified() < cacheFile.lastModified()) {
            f.delete()
            Log.d(TAG, "drop stale week cache $week")
            return null
        }
        return try {
            gson.fromJson(f.readText(), ScheduleResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "loadCachedWeek $week failed: $e")
            null
        }
    }

    /**
     * 整学期数据刷新后，切周时拉的周缓存全部过期，直接删掉（下次切到该周会按需补拉）。
     *
     * 只删 `schedule_w<数字>.json`：`schedule_wcur.json` 是 [MiniappApi] 留的原始响应转储，
     * 其它 `xnxq_list.json`/`score_*.json` 也不是周缓存，不能连带删掉。
     */
    private fun clearWeekCaches() {
        val pattern = Regex("""schedule_w\d+\.json""")
        runCatching {
            appDir.listFiles { f -> pattern.matches(f.name) }?.forEach { it.delete() }
        }
    }

    fun cacheLastModified(): Long {
        if (!::cacheFile.isInitialized) return 0L
        return if (cacheFile.exists()) cacheFile.lastModified() else 0L
    }

    /**
     * 网络拉取。week=null 表示不带周次参数（拿当前周次元信息），
     * 带周次则对齐官方 App 的 GetSchedule&week=N 请求。
     */
    suspend fun refresh(week: Int? = null, updateWidget: Boolean = true): ScheduleResponse {
        // 确保 miniapp 会话有效
        if (!hasMiniappSession()) {
            val ok = MiniappApi.handshake(Session.deviceId(AppContext.instance))
            if (!ok) {
                throw ApiException(-1, TextRef.Res(R.string.error_schedule_login))
            }
        }
        val raw = MiniappApi.getScheduleRaw(week)
        val resp = gson.fromJson(raw, ScheduleResponse::class.java)
        if (resp?.code != 0 || resp.data == null) {
            throw ApiException(
                resp?.code ?: -1,
                resp?.msg?.let { TextRef.Raw(it) } ?: TextRef.Res(R.string.error_schedule_request)
            )
        }
        if (week == null) {
            cacheFile.writeText(raw)
            // 整学期数据已经是"最新全量"：切周时留下的周缓存一律作废，
            // 否则教务移除/调整过的课会从旧周缓存里复活（2026-10-26 反馈）
            clearWeekCaches()
            val currentWeek = resp.data.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: 0
            if (currentWeek > 0) {
                Session.scheduleWeekAnchorAt = System.currentTimeMillis()
                Session.scheduleWeekAnchorWeek = currentWeek
            }
        } else {
            File(appDir, "schedule_w$week.json").writeText(raw)
        }
        Log.d(TAG, "refreshed week=$week ${raw.length} bytes")
        if (updateWidget) ScheduleWidgetUpdater.updateAll(AppContext.instance)
        return resp
    }

    /** 简易判断是否已有 miniapp 会话 Cookie */
    /** 会话判断依赖域名，实现在 engine（不在公开代码里重复一遍） */
    private fun hasMiniappSession(): Boolean = ApiClient.hasMiniappSession()
}

/** 供 Repository 获取全局 Context（App 初始化时注入） */
object AppContext {
    lateinit var instance: Context
        private set

    fun init(context: Context) {
        instance = context.applicationContext
    }
}
