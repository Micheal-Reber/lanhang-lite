package com.lanhang.lite.net

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(
    val code: Int? = null,
    val msg: String? = null,
    val result: T? = null
) {
    val isSuccess: Boolean get() = code == 10000
}

// 官方 LoginInfo：staff 字段带 @SerializedName("staff")
data class LoginInfo(
    val token: String? = null,
    @SerializedName("staff") val staffInfo: StaffInfo? = null,
    val user_type: Int? = null
)

// 官方 StaffInfo.getUserId() 返回的是 id 字段
data class StaffInfo(
    val id: String? = null,
    val name: String? = null,
    val code: String? = null,
    val mobile: String? = null,
    val company_id: String? = null,
    val depart_id: String? = null,
    val depart_name: String? = null
) {
    val userId: String? get() = id
}

data class RawTokenResponse(val code: Int? = null, val result: String? = null)

data class OAWebAppBean(
    val type1: Type1? = null,
    val type2: Type2? = null
) {
    data class Type1(val apps: List<App>? = null)
    data class Type2(val apps: List<App>? = null, val types: List<Types>? = null)
    data class Types(val apps: List<App>? = null, val type_name: String? = null)
    data class App(
        val id: String? = null,
        val name: String? = null,
        val description: String? = null,
        val home_page_url: String? = null,
        val pc_home_page_url: String? = null,
        val logo_url: String? = null,
        val state: Int? = null,
        val type: Int? = null
    ) {
        val homeUrl: String? get() = home_page_url ?: pc_home_page_url
    }
}

// POST /staffs/batch 批量解析；官方 StaffInfo.getUserId() 返回的是 id 字段
data class StaffBrief(
    val id: String? = null,
    val name: String? = null,
    val portrait_url: String? = null,
    val depart_name: String? = null,
    val duty_name: String? = null,
    val company_id: String? = null
)

data class StaffBatchResult(val data: List<StaffBrief>? = null, val timestamp: Long? = null)

data class ConfigInfo(val features: Features? = null) {
    data class Features(
        val security: Security? = null,
        val work: Work? = null,
        @SerializedName("media") val mediaServer: MediaServerInfo? = null
    )
    data class Security(val password_public_key_rsa: String? = null)
    data class Work(val enable: Boolean? = null, val url: String? = null)
}

data class MediaServerInfo(
    val type: Int? = null,
    @SerializedName("max_file_size") val maxFileSize: Long? = null,
    @SerializedName("upload_urls") val uploadUrls: List<String>? = null,
    @SerializedName("download_urls") val downloadUrls: List<String>? = null
)

data class MediaUploadToken(
    val token: String? = null,
    val key: String? = null,
    val server: String? = null,
    val domain: String? = null,
    val target: String? = null
)
