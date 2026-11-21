# Release 빌드 난독화 활성화 시 대비 ProGuard 규칙

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.unraid.app.data.model.**$$serializer { *; }
-keepclassmembers class com.unraid.app.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.unraid.app.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Gomobile / tsnet-bridge JNI 바인딩 유지
-keep class go.** { *; }
-keep class com.unraid.app.tsnetbridge.** { *; }

# libmpv JNI 바인딩 유지
-keep class dev.jdtech.mpv.** { *; }

# 외부 플러그인 직렬화 모델 및 @JavascriptInterface 유지
-keep,includedescriptorclasses class com.unraid.app.plugin.**$$serializer { *; }
-keepclassmembers class com.unraid.app.plugin.** {
    *** Companion;
}
-keepclasseswithmembers class com.unraid.app.plugin.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
