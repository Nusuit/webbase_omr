# Add project specific ProGuard rules here.
# By default, the flags in this file are applied to all build types.

# OpenCV
-keep class org.opencv.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Gson TypeToken — giữ generic signature để TypeToken.getParameterized() hoạt động đúng
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# GradeSnap data classes — giữ toàn bộ member + generic signature để Gson serialize/deserialize đúng.
# Không dùng chỉ keep class name vì R8 có thể xoá/đổi tên member và erase generic signature.
-keep class com.gradesnap.omr.ExamProject { *; }
-keep class com.gradesnap.omr.StudentResult { *; }
-keep class com.gradesnap.omr.QuestionResult { *; }
-keep class com.gradesnap.omr.AnswerKey { *; }
-keep class com.gradesnap.omr.StudentInfo { *; }
-keep class com.gradesnap.omr.RawScanResult { *; }
-keep class com.gradesnap.omr.BubbleResult { *; }
-keep class com.gradesnap.omr.ProcessingLog { *; }
-keep class com.gradesnap.omr.OmrConfig { *; }
-keep class com.gradesnap.omr.RegionConfig { *; }
-keep class com.gradesnap.omr.QuestionBlock { *; }
-keep class com.gradesnap.omr.QuestionConfig { *; }
-keep class com.gradesnap.omr.AnswerItem { *; }
# Giữ các field có @SerializedName (Gson cần đọc annotation trong runtime)
-keepclassmembers class com.gradesnap.omr.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
