# Gson 反射序列化（Models 用 @SerializedName 中文键）
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.lanhang.lite.net.Models { *; }
-keep class com.lanhang.lite.schedule.ScheduleModels { *; }
-keep class com.lanhang.lite.grades.GradeModels { *; }
-keep class com.lanhang.lite.moments.MomentModels { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coil
-dontwarn coil.**
