package com.lanhang.lite.schedule

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.LocalDate

/**
 * 自建考试的本地仓库（2026-11-26）。
 *
 * 与 [LocalCourseStore] 同一套思路：独立文件 `local_exams.json`，学校课表刷新不会碰它，
 * 退出登录也不清（用户自己录的内容）。数据量极小，同步读写即可 —— 调用点（网格构建、
 * 提醒排期）本来就是同步的。
 */
object LocalExamStore {

    private const val TAG = "LocalExamStore"
    private const val FILE = "local_exams.json"

    private val gson = Gson()
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE)
    }

    @Synchronized
    fun all(): List<LocalExam> {
        if (!::file.isInitialized || !file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<LocalExam>>() {}.type
            gson.fromJson<List<LocalExam>>(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "read failed", e)
            emptyList()
        }
    }

    /** 某一天的所有考试（按开始时间升序） */
    fun forDate(date: LocalDate): List<LocalExam> =
        all().filter { it.localDate() == date }.sortedBy { it.startMinute }

    /**
     * 从 [from] 起的考试（含当天），按日期 + 开始时间升序。
     * 列表页用这个：过期的考试不必天天顶在最上面，但也不删（用户可能想回看）。
     */
    fun upcoming(from: LocalDate = LocalDate.now()): List<LocalExam> = all()
        .filter { exam -> exam.localDate()?.let { !it.isBefore(from) } == true }
        .sortedWith(compareBy({ it.date }, { it.startMinute }))

    /** 全部考试，按日期倒序（「全部」视图用） */
    fun recentFirst(): List<LocalExam> =
        all().sortedWith(compareByDescending<LocalExam> { it.date }.thenBy { it.startMinute })

    @Synchronized
    fun save(exam: LocalExam): Boolean {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == exam.id }
        if (idx >= 0) list[idx] = exam else list.add(exam)
        return write(list)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val list = all().toMutableList()
        if (!list.removeIf { it.id == id }) return false
        return write(list)
    }

    /** 退出登录时清掉（考试挂在课表上，换账号后没有意义，同 [LocalCourseStore.clear]） */
    @Synchronized
    fun clear() {
        if (!::file.isInitialized) return
        runCatching { file.delete() }
    }

    private fun write(list: List<LocalExam>): Boolean = try {
        file.writeText(gson.toJson(list))
        true
    } catch (e: Exception) {
        Log.e(TAG, "write failed", e)
        false
    }
}
