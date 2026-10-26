# Kiba ProGuard / R8 规则
# 不混淆（保留类名/方法名，降低 R8 击破反射与运行时取景层的风险；仍做代码收缩）
-dontobfuscate

# 保留行号，便于 release 崩溃定位
-keepattributes SourceFile,LineNumberTable

# ── Room ──
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class com.kira.kiba.core.db.entity.** { *; }
-keep class com.kira.kiba.core.model.** { *; }

# ── JSON：Gson（查分器）/ kotlinx.serialization ──
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.kira.kiba.core.network.** { *; }
-keep class com.kira.kiba.core.model.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Gson 反射受 R8 破坏的两个实证（1.4.0 release，debug 正常）：
# ① TypeToken 匿名子类的泛型签名被优化删除 → 登录成功瞬间
#    "Missing type parameter" 崩溃（SpUtil.getAccountHistory）；
# ② 仅经反射访问的 DTO（DivingFishAccount$RecordsEnvelope、KibaLlm$ChatResponse 等）
#    被 R8 整类删除/合并 → 成绩获取报 "Unable to create instance of class
#    kotlin.text.CharsKt"（类字面量被替换后的产物）。
# 保留 TypeToken 体系与其全部子类，以及承载反射 DTO 的包。
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.kira.kiba.core.account.** { *; }
-keep class com.kira.kiba.core.agent.** { *; }
-keep class kotlin.Pair { *; }

# ── Retrofit / OkHttp（接口经动态代理调用，注解须保留）──
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn org.codehaus.mojo.**

# ── 枚举：保留 values()/valueOf()，避免被 R8 误删 ──
-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── 库自带的 @Keep 标记一律保留（miuix / AndroidX 内部约定）──
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * { @androidx.annotation.Keep *; }

# ── miuix / miuix-glass / miuix-blur（玻璃导航栏、弹层、顶栏取景层、AGSL 着色器字符串）──
-keep class top.yukonga.miuix.** { *; }
-keep class com.kyant.** { *; }

# ── Coil / Glide 自带 consumer rules 已覆盖；此处仅免告警 ──
-dontwarn coil.**
-dontwarn com.bumptech.glide.**

# ── 原生库（.so）不参与 R8，保留 ──
-keep class **.BuildConfig { *; }
