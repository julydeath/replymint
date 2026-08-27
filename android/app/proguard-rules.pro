# kotlinx.serialization — keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.replymint.net.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.replymint.net.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
