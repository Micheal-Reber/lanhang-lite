# Gson 反射序列化
#
# 【不要用文件名当类名写 keep 规则】
# Models.kt / ScheduleModels.kt / GradeModels.kt / MomentModels.kt 里装的都是一堆【顶层类】
# （ApiResponse、LoginInfo、Course…），并不存在 com.lanhang.lite.net.Models 这个类。
# 之前的四条形如 `-keep class com.lanhang.lite.net.Models { *; }` 是空规则，
# R8 照常把模型字段改名、把类合并掉 → Gson 按 JSON 键取不到值，
# 线上表现为「登录失败」「数据异常」（release 包才复现，debug 不混淆所以看正常）。
# 这里改成按【包】保留，以后往这些文件里新增模型类会自动生效。
#
# 注意：net / moments / oa / directory / session 这几个包的**类文件在闭源 engine module 里**
# （见 settings.gradle.kts）。包名刻意保持不变，所以下面这些按包名的规则对 aar 的类同样生效，
# 无需为 module 单独再写一份。
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.lanhang.lite.net.** { *; }
-keep class com.lanhang.lite.schedule.** { *; }
-keep class com.lanhang.lite.grades.** { *; }
-keep class com.lanhang.lite.moments.** { *; }
# oa 包（OaApp / OaAppsAll / OaType2Bucket / OaTypeGroup…）同样只靠 Gson 反射，
# 字段名本身就是 JSON 键（type1/type2/types/apps 这些没有 @SerializedName）。
# 2026-10-26 漏了这条：release 包里 OaApp 被改名成 t7.b、字段变 a/b/c/d，
# OaAppsAll 直接被 R8 合并掉 → 办事大厅「应用列表加载失败」，debug 包不复现。
-keep class com.lanhang.lite.oa.** { *; }
# directory 包（成员录：Department / OrgEntry / StaffSearchHit…）同样只靠 Gson 反射，
# 字段经 @SerializedName 映射服务端下划线键名。往该包新增模型类自动生效。
-keep class com.lanhang.lite.directory.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# 兜底网：凡是有 @SerializedName 字段的类，成员一律保留。
# 新增「用 Gson 反射解析」的包时，仍然要在上面登记一行 -keep class <包>.** ，
# 因为像 OaAppsAll 这种整个类都没有注解的模型，这条兜不住。
-keepclasseswithmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coil
-dontwarn coil.**
