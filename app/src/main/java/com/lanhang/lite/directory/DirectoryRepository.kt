package com.lanhang.lite.directory

import com.lanhang.lite.net.ApiClient
import com.lanhang.lite.net.RceApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「成员录」取数 + 本地缓存。
 *
 * 组织树一次 300KB+、1500 多个节点，每进一次都现拉会一直压在「加载中」甚至超时
 * （参考项目用户 2026-10-26 反馈）。所以树和每个部门的成员页都落到 `filesDir/directory/`：
 * - 有未过期的缓存 → 立刻返回，进页面基本秒开；
 * - 缓存过期或没有 → 请求网络，成功后再写盘（失败不影响已展示的旧数据）。
 *
 * 缓存只存服务端给的原始结构，不做业务加工；过期时间取保守值
 * （组织树 12 小时、部门成员 6 小时），避免展示过时名单。
 */
object DirectoryRepository {

    private const val TREE_TTL_MS = 12 * 60 * 60 * 1000L
    private const val BRANCH_TTL_MS = 6 * 60 * 60 * 1000L

    /** 部门成员页的分页大小（官方 branches 也是按页拉的） */
    const val PAGE_SIZE = 50

    private fun dir(): File? = runCatching {
        File(com.lanhang.lite.schedule.AppContext.instance.filesDir, "directory").apply { mkdirs() }
    }.getOrNull()

    private fun file(key: String): File? = dir()?.let { File(it, "$key.json") }

    private suspend fun readCache(key: String, ttlMs: Long, force: Boolean): String? =
        withContext(Dispatchers.IO) {
            if (force) return@withContext null
            val f = file(key) ?: return@withContext null
            if (!f.isFile) return@withContext null
            if (System.currentTimeMillis() - f.lastModified() > ttlMs) return@withContext null
            runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    private suspend fun writeCache(key: String, json: String) = withContext(Dispatchers.IO) {
        val f = file(key) ?: return@withContext
        runCatching { f.writeText(json) }
        Unit
    }

    /** 清缓存（设置页「清除缓存」之类入口用得到） */
    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { dir()?.listFiles()?.forEach { it.delete() } }
        Unit
    }

    /** 整棵组织树；[force] = 忽略缓存重新拉 */
    suspend fun tree(force: Boolean = false): List<Department> {
        val key = "tree_v1"
        readCache(key, TREE_TTL_MS, force)?.let { json ->
            runCatching {
                ApiClient.gson.fromJson(json, Array<Department>::class.java)?.toList()
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val fresh = RceApi.departmentTree()
        if (fresh.isNotEmpty()) writeCache(key, ApiClient.gson.toJson(fresh))
        return fresh
    }

    /** 某个部门的子部门 + 成员（按 offset/limit 分页，翻过的页同样落盘） */
    suspend fun branches(
        deptId: String?,
        offset: Int,
        limit: Int,
        force: Boolean = false,
    ): DepartmentBranches {
        val key = "dept_${deptId?.takeIf { it.isNotBlank() } ?: "root"}_${offset}_$limit"
        readCache(key, BRANCH_TTL_MS, force)?.let { json ->
            runCatching { ApiClient.gson.fromJson(json, DepartmentBranches::class.java) }
                .getOrNull()?.let { return it }
        }
        val fresh = RceApi.departmentBranches(deptId, offset, limit)
        writeCache(key, ApiClient.gson.toJson(fresh))
        return fresh
    }

    /**
     * 人员搜索（姓名 / 学号 / 手机号），统一走 [RceApi.searchMembers]。
     * 搜索结果**不落盘**：查询词任意、命中面窄，缓存收益低还容易存一堆手机号。
     */
    suspend fun search(keyword: String): List<StaffSearchHit> =
        withContext(Dispatchers.IO) { RceApi.searchMembers(keyword) }

    /**
     * 成员行的学号补齐：branches 只给姓名/身份/手机号，学号要用 `/staffs/batch` 批量补。
     * 返回 id → 学号（已去邮箱后缀）。失败返回空表，行里就不显示学号。
     */
    suspend fun studentNos(ids: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyMap()
            runCatching {
                RceApi.staffsBatch(ids.distinct())?.data
                    ?.mapNotNull { b -> val id = b.id ?: return@mapNotNull null; b.studentNo?.let { id to it } }
                    ?.toMap()
                    .orEmpty()
            }.getOrDefault(emptyMap())
        }
}
