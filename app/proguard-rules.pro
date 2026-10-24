# Kiba ProGuard 规则（release 默认仅收缩不混淆：-dontobfuscate）
-dontobfuscate

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class com.kira.kiba.core.db.entity.** { *; }
-keep class com.kira.kiba.core.model.** { *; }

# Gson / 查分器 JSON
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.kira.kiba.core.network.** { *; }

# Retrofit / OkHttp
-keepattributes RuntimeVisibleAnnotations
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# miuix / kyant（玻璃导航栏 native blur）
-keep class top.yukonga.miuix.** { *; }
-keep class com.kyant.** { *; }
