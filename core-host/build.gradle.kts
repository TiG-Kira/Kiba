plugins {
    id("com.android.library")
    // ⚠️ 必须启用 Compose 编译器插件：本模块公开 API 里有 @Composable 函数类型
    // （Provider.extPageContent）。不开的话 Kotlin 把它编成 Function1，而启用了
    // compose 的调用方（app / 插件）按 compose 规则生成 Function3 调用，
    // 运行时 NoSuchMethodError（v3.6.3 真机打开插件页闪退的第二个根因）。
    id("org.jetbrains.kotlin.plugin.compose")
}

// ─────────────────────────────────────────────────────────────────────────────
// 宿主公共 API 模块：全局模型枚举 + 通用扩展挂接点（纯接口，无任何业务实现）。
// app 依赖本模块；可选本地模块（见 settings.gradle.kts 的 kiba.extraModules）
// 也依赖本模块获得挂接点契约。不含任何实现语义，属公开中性的宿主契约层。
// ─────────────────────────────────────────────────────────────────────────────

android {
    namespace = "com.kira.kiba.core.host"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    val miuixVersion = "0.9.4"
    implementation("androidx.compose.runtime:runtime:1.8.3")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:$miuixVersion")
}
