package com.lanhang.lite.net

import android.os.Build
import com.google.gson.reflect.TypeToken
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val code: Int, message: String) : IOException(message)

object RceApi {
    private val JSON = "application/json;charset=utf-8".toMediaType()

    private inline fun <reified T> get(path: String, withCookie: Boolean = true): T {
        val builder = Request.Builder().url(ApiClient.BASE_URL + ApiClient.API_PREFIX + path).get()
        if (withCookie) {
            ApiClient.cookieHeader()?.let { builder.header("Cookie", it) }
        }
        return execute(builder.build())
    }

    private inline fun <reified T> post(path: String, body: String, withCookie: Boolean = true): T {
        val builder = Request.Builder()
            .url(ApiClient.BASE_URL + ApiClient.API_PREFIX + path)
            .post(body.toRequestBody(JSON))
        if (withCookie) {
            ApiClient.cookieHeader()?.let { builder.header("Cookie", it) }
        }
        return execute(builder.build())
    }

    private inline fun <reified T> execute(request: Request): T {
        ApiClient.http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val type = object : TypeToken<ApiResponse<T>>() {}.type
            val parsed: ApiResponse<T> = try {
                ApiClient.gson.fromJson(text, type)
            } catch (e: Exception) {
                throw ApiException(-1, "数据异常: ${text.take(200)}")
            }
            if (!parsed.isSuccess) {
                throw ApiException(parsed.code ?: -1, parsed.msg ?: "请求失败")
            }
            @Suppress("UNCHECKED_CAST")
            return parsed.result as T
        }
    }

    fun login(username: String, password: String, deviceId: String): LoginInfo {
        val body = mapOf(
            "username" to username,
            "password" to Security.encryptPassword(password),
            "agent" to mapOf("platform" to "Android", "device_id" to deviceId),
            "status" to "online",
            "device_info" to mapOf(
                "app_version" to "0.1.0",
                "app_version_code" to 1,
                "app_language" to "zh-CN",
                "os_version" to Build.VERSION.RELEASE,
                "device_name" to Build.MANUFACTURER,
                "device_model" to Build.MODEL,
                "network" to "WIFI",
                "timezone" to java.util.TimeZone.getDefault().id,
                "operator" to "",
                "imei" to deviceId,
                "package_name" to "com.lanhang.lite"
            )
        )
        return post("/user/security_login", ApiClient.gson.toJson(body), withCookie = false)
    }

    fun configurationAll(): ConfigInfo =
        get("/configuration/all", withCookie = false)

    fun getImageUploadToken(): MediaUploadToken =
        get("/user/get_image_token")

    // 这两个接口返回的是 {"code":10000,"result":"字符串"}，不是嵌套对象
    fun getLdapToken(): String? {
        val builder = Request.Builder()
            .url(ApiClient.BASE_URL + ApiClient.API_PREFIX + "/apps/getLdapToken")
            .get()
        ApiClient.cookieHeader()?.let { builder.header("Cookie", it) }
        return executeTokenString(builder.build())
    }

    fun getAppToken(appId: String): String? {
        val builder = Request.Builder()
            .url(ApiClient.BASE_URL + ApiClient.API_PREFIX + "/apps/getAppToken/$appId")
            .get()
        ApiClient.cookieHeader()?.let { builder.header("Cookie", it) }
        return executeTokenString(builder.build())
    }

    private fun executeTokenString(request: Request): String? {
        ApiClient.http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            android.util.Log.d("RceApi", "token resp code=${resp.code} url=${request.url} body=${text.take(300)}")
            val parsed: RawTokenResponse = try {
                ApiClient.gson.fromJson(text, RawTokenResponse::class.java)
            } catch (e: Exception) {
                throw ApiException(-1, "数据异常: ${text.take(200)}")
            }
            if (parsed.code != 10000) {
                throw ApiException(parsed.code ?: -1, "请求失败")
            }
            return parsed.result
        }
    }

    fun allApps(): OAWebAppBean? {
        val body = """{"state":[0,1]}"""
        return post("/apps/all", body)
    }

    fun refreshUserByDel(userId: String): Any? =
        get<Any?>("/user/refreshUserByDel/$userId", withCookie = false)

    fun staffsBatch(ids: List<String>): StaffBatchResult? =
        post("/staffs/batch", ApiClient.gson.toJson(mapOf("ids" to ids)))
}
