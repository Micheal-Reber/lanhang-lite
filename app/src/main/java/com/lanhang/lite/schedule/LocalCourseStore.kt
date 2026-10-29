package com.lanhang.lite.schedule

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 自建课程的本地仓库。
 *
 * **独立文件** `local_courses.json`：学校课表刷新（[ScheduleRepository.refresh]）只写
 * `schedule_cache.json` / `schedule_wN.json`，永远不会碰这个文件；退出登录也不清它
 * （自建课是用户自己录入的内容，不是服务端凭证）。
 *
 * 数据量是几十条，同步读写即可；调用点（网格构建、提醒排期）本来就是同步的。
 */
object LocalCourseStore {

    private const val TAG = "LocalCourseStore"
    private const val FILE = "local_courses.json"

    private val gson = Gson()
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE)
    }

    @Synchronized
    fun all(): List<LocalCourse> {
        if (!::file.isInitialized || !file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<LocalCourse>>() {}.type
            gson.fromJson<List<LocalCourse>>(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "read failed", e)
            emptyList()
        }
    }

    /** 当前学期的自建课；`termId` 为空的历史数据也带上，避免升级后突然消失 */
    fun forTerm(termId: String): List<LocalCourse> =
        all().filter { it.termId.isBlank() || termId.isBlank() || it.termId == termId }

    /** 某天、某一周要显示的自建课 */
    fun forDay(termId: String, dayIndex: Int, week: Int): List<LocalCourse> =
        forTerm(termId).filter { it.dayIndex == dayIndex && it.isActiveInWeek(week) }

    @Synchronized
    fun save(course: LocalCourse): Boolean {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == course.id }
        if (idx >= 0) list[idx] = course else list.add(course)
        return write(list)
    }

    @Synchronized
    fun delete(id: String): Boolean = write(all().filterNot { it.id == id })

    fun find(id: String): LocalCourse? = all().firstOrNull { it.id == id }

    private fun write(list: List<LocalCourse>): Boolean = try {
        file.writeText(gson.toJson(list))
        true
    } catch (e: Exception) {
        Log.e(TAG, "write failed", e)
        false
    }
}
