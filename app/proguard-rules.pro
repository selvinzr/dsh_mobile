# kotlinx.serialization：保留 @Serializable 生成的 serializer
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dsh.mobile.**$$serializer { *; }
-keepclassmembers class com.dsh.mobile.** {
    *** Companion;
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ZXing（扫码）
-dontwarn com.google.zxing.**

# 协程
-dontwarn kotlinx.coroutines.**