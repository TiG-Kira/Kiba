# Kiba ProGuard / R8 规则
#
# 1.8.8 起**开启混淆**（此前是 -dontobfuscate）。
# 目的：把落雪凭据保险箱（core/security/LxnsVault + 构建期生成的 LxnsSealData）连同 UI 层
# 一起改名，让「解包 → 找常量 → 抄密钥」的路径断掉；只靠加密不改名，加密逻辑本身一眼可见。
#
# 为什么敢开了：历史上被 R8 打穿的都是**仅反射可达**的东西（Gson TypeToken / 反射 DTO /
# Room 实体），这些现在全部有显式 -keep（见下）。UI / Compose / Coil / miuix 都是静态引用，
# 改名无影响；Activity / Service / Receiver 由 Manifest 引用，R8 自动保留。
#
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
# 2.6.0 新增：Agent 歌单存档（AgentPlaylistStore.Item）经 Gson 反射建实例、无 @SerializedName，
# 字段名即 JSON key —— 被 R8 剥掉后升级读回全空。core.listen 里 Saved/Item 都是这类 DTO。
-keep class com.kira.kiba.core.listen.** { *; }
-keep class kotlin.Pair { *; }

# ── Retrofit / OkHttp（接口经动态代理调用，注解须保留）──
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn org.codehaus.mojo.**

# Retrofit 方法泛型签名（1.8.8 实证坑）：
# R8 会剥掉「被 -keep 的接口」上方法的泛型签名（Observable<JsonElement> → 裸 Observable），
# RxJava2CallAdapterFactory 运行时按泛型实参解析返回类型，直接抛
# "Observable return type must be parameterized as Observable<Foo>"。
# 结果是 chart_stats 同步每次启动都静默失败，热门乐曲排行永远空态（debug 未混淆则一切正常）。
# 保留 RxJava2 全家 + 接口方法的泛型签名属性。
-keep class io.reactivex.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod

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

# ── 混淆相关的补充保留（1.8.8）──
# 导航用 page.javaClass.simpleName 生成页面状态 key（滚动位置/顶栏记忆），
# 类名被改成 a/b/c 后不同页可能撞名 → 只保名不保实现，收缩照常。
-keepnames class com.kira.kiba.ui.navigation.KibaSubPage$*
-keepnames class com.kira.kiba.ui.navigation.KibaTab

# kotlinx.serialization：二维码网关的 DTO 走编译器生成的 $$serializer。
# 只被 Companion / 内联 serializer<T>() 静态引用，R8 开混淆后仍可能被合并掉，显式保留。
-keep,includedescriptorclasses class com.kira.kiba.core.maimai.**$$serializer { *; }
-keepclassmembers class com.kira.kiba.core.maimai.** {
    *** Companion;
}
-keepclasseswithmembers class com.kira.kiba.core.maimai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── 插件动态加载：接口契约对第三方插件 dex 保持稳定（反射 cast 与注入路径） ──
-keep class com.kira.kiba.core.host.AppExtensions { *; }
-keep class com.kira.kiba.core.host.AppExtensions$* { *; }
-keep class com.kira.kiba.core.host.PluginLoader { *; }

# ── 插件动态加载：宿主侧被插件 dex 引用的类必须保持原名与成员 ──
# 生成依据：kiba-extra/tools/gen_host_keep.py（插件 dex 外部引用 × mapping 比对）
# 插件独立编译（compileOnly 宿主依赖），运行时按原名解析——任何被插件引用的
# 宿主类/成员被 R8 改名或收缩，都会导致插件加载失败（NoClassDefFoundError）。

# Kotlin 标准库整体保留——插件 dex 独立编译，凡它引用的宿主侧类都必须
# 原名且带成员（lazy 工厂 LazyKt、协程 internals、反射元数据等无法逐一枚举）
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# 网络（OkHttp 及其 okio 依赖）
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Compose / miuix / activity（插件 UI 契约面）
# ⚠️ v3.6.3→3.6.4 真机回归：R8 把插件 dex 引用的 31 个类改名、11 个类整体收缩
# （Modifier$Companion / ComponentActivity / ToggleableState / zxing 等），
# 插件页一进就 NoClassDefFoundError 闪退。之前 AVD E2E 走 debug 包（无 R8），
# 此路径从未被 release 覆盖。
# 修复不能走 -dontobfuscate（1.8.8 起混淆是 LxnsVault 防逆向的既定安全措施，
# 实测 dontobfuscate 会让 dex 显著膨胀且破坏防逆向目标），
# 改为外科手术式：gen_host_keep.py 校验出的缺口类带成员整体保留（防改名+防收缩），
# 实测体积回归 ≈0（22.4→22.5MB）。插件外部引用变化时跑
# kiba-extra/tools/gen_host_keep.py 复核并补规则（CI 可接退出码）。

-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.Modifier { *; }
-keep class androidx.compose.foundation.layout.** { *; }
-keep class androidx.compose.foundation.lazy.** { *; }
-keep class top.yukonga.miuix.kmp.basic.** { *; }
-keep class androidx.activity.compose.ManagedActivityResultLauncher { *; }

# gen_host_keep.py 校准（2026-11-24，v3.6.3 mapping × 插件 dex 比对；dontobfuscate
# 下改名类不会再现，这里主要是防收缩兜底）
-keep class androidx.activity.ComponentActivity { *; }
-keep class androidx.activity.compose.ActivityResultRegistryKt { *; }
-keep class androidx.activity.result.contract.ActivityResultContract { *; }
-keep class androidx.activity.result.contract.ActivityResultContracts$GetContent { *; }
-keep class androidx.compose.foundation.ClickableKt { *; }
-keep class androidx.compose.foundation.Indication { *; }
-keep class androidx.compose.foundation.OverscrollEffect { *; }
-keep class androidx.compose.foundation.gestures.FlingBehavior { *; }
-keep class androidx.compose.foundation.interaction.InteractionSourceKt { *; }
-keep class androidx.compose.foundation.interaction.MutableInteractionSource { *; }
-keep class androidx.compose.foundation.text.KeyboardActions { *; }
-keep class androidx.compose.foundation.text.KeyboardOptions { *; }
-keep class androidx.compose.foundation.text.TextAutoSize { *; }
-keep class androidx.compose.ui.Alignment { *; }
-keep class androidx.compose.ui.Alignment$Companion { *; }
-keep class androidx.compose.ui.Alignment$Horizontal { *; }
-keep class androidx.compose.ui.Alignment$Vertical { *; }
-keep class androidx.compose.ui.ComposedModifierKt { *; }
-keep class androidx.compose.ui.Modifier$Companion { *; }
-keep class androidx.compose.ui.graphics.Brush { *; }
-keep class androidx.compose.ui.layout.MeasurePolicy { *; }
-keep class androidx.compose.ui.node.ComposeUiNode { *; }
-keep class androidx.compose.ui.node.ComposeUiNode$Companion { *; }
-keep class androidx.compose.ui.semantics.Role { *; }
-keep class androidx.compose.ui.state.ToggleableState { *; }
-keep class androidx.compose.ui.state.ToggleableStateKt { *; }
-keep class androidx.compose.ui.text.TextStyle { *; }
-keep class androidx.compose.ui.text.font.FontFamily { *; }
-keep class androidx.compose.ui.text.font.FontStyle { *; }
-keep class androidx.compose.ui.text.font.FontWeight { *; }
-keep class androidx.compose.ui.text.input.VisualTransformation { *; }
-keep class androidx.compose.ui.text.style.TextAlign { *; }
-keep class androidx.compose.ui.text.style.TextDecoration { *; }
-keep class androidx.compose.ui.unit.Dp { *; }
-keep class androidx.compose.ui.unit.TextUnitKt { *; }
-keep class com.google.zxing.Binarizer { *; }
-keep class com.google.zxing.BinaryBitmap { *; }
-keep class com.google.zxing.LuminanceSource { *; }
-keep class com.google.zxing.MultiFormatReader { *; }
-keep class com.google.zxing.RGBLuminanceSource { *; }
-keep class com.google.zxing.Result { *; }
-keep class com.google.zxing.common.HybridBinarizer { *; }

# gen_host_keep.py 成员级校准（2026-11-25 真机回归第二/三轮）：R8 会把宿主已
# devirtualize 的 compose 接口/平台成员整体内联删掉（getLocalContext、Composer.changed(Object)），
# 插件 dex 按原名引用即 NoSuchMethodError。以下为成员级校验出的缺口类。
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt { *; }
-keep class androidx.compose.runtime.State { *; }
-keep class androidx.compose.runtime.MutableState { *; }
-keep class androidx.compose.runtime.ScopeUpdateScope { *; }
-keep class androidx.compose.foundation.layout.ColumnScopeInstance { *; }
-keep class androidx.compose.foundation.layout.RowScopeInstance { *; }
# Composer 的 changed(Object)：R8 在无显式规则时会内联删除接口抽象方法
-keepclassmembers class androidx.compose.runtime.Composer {
    boolean changed(java.lang.Object);
    boolean changedInstance(java.lang.Object);
    java.lang.Object rememberedValue();
    void updateRememberedValue(java.lang.Object);
    boolean shouldExecute(boolean, int);
    void startReplaceGroup(int);
    void endReplaceGroup();
    void startRestartGroup(int);
    androidx.compose.runtime.Composer startRestartGroup(int);
    androidx.compose.runtime.ScopeUpdateScope endRestartGroup();
}
