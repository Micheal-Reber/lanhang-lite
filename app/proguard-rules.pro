# Gson 反射序列化
#
# 【不要用文件名当类名写 keep 规则】
# Models.kt / ScheduleModels.kt / GradeModels.kt / MomentModels.kt 里装的都是一堆【顶层类】
# （ApiResponse、LoginInfo、Course…），并不存在 com.lanhang.lite.net.Models 这个类。
# 之前的四条形如 `-keep class com.lanhang.lite.net.Models { *; }` 是空规则，
# R8 照常把模型字段改名、把类合并掉 → Gson 按 JSON 键取不到值，
# 线上表现为「登录失败」「数据异常」（release 包才复现，debug 不混淆所以看正常）。
# 这里改成按【包】保留，以后往这些文件里新增模型类会自动生效。
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.lanhang.lite.net.** { *; }
-keep class com.lanhang.lite.schedule.** { *; }
-keep class com.lanhang.lite.grades.** { *; }
-keep class com.lanhang.lite.moments.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coil
-dontwarn coil.**
