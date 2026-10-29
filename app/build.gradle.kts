import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

// 水鱼查分器 OAuth 应用凭据：显式读 local.properties（不入库，AGP 不保证注入 project property），
// 环境变量兜底。公开仓库只放占位，真实值存 GitHub Secrets（KIBA_DF_CLIENT_ID / KIBA_DF_CLIENT_SECRET）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val dfClientId = (localProps.getProperty("kiba.dfClientId")
    ?: System.getenv("KIBA_DF_CLIENT_ID") ?: "").toString()
val dfClientSecret = (localProps.getProperty("kiba.dfClientSecret")
    ?: System.getenv("KIBA_DF_CLIENT_SECRET") ?: "").toString()

// 二维码取数网关接入点与协议密钥：同上不入库，真实值存 GitHub Secrets
// （KIBA_QR_GATEWAY_BASE / KIBA_QR_GATEWAY_SECRET），本地存 local.properties。
val qrGatewayBase = (localProps.getProperty("kiba.qrGatewayBase")
    ?: System.getenv("KIBA_QR_GATEWAY_BASE") ?: "").toString()
val qrGatewaySecret = (localProps.getProperty("kiba.qrGatewaySecret")
    ?: System.getenv("KIBA_QR_GATEWAY_SECRET") ?: "").toString()

// 网关各端点路径同样不入库：固定顺序「登录|注册|续期|二维码取数|拉取同步成绩」，
// CI 用单条 Secret KIBA_QR_GATEWAY_PATHS（| 分隔）注入。
val qrGatewayPaths = (localProps.getProperty("kiba.qrGatewayPaths")
    ?: System.getenv("KIBA_QR_GATEWAY_PATHS") ?: "").split('|')

android {
    namespace = "com.kira.kiba"
    // miuix 0.9.4 / kyant backdrop 2.0.1 硬要求 compileSdk >= 37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kira.kiba"
        // miuix 0.9.4 各模块（blur/ui/icons/preference）要求 minSdk >= 33
        minSdk = 33
        targetSdk = 37
        versionCode = 51
        versionName = "1.7.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // ABI：显式钉死包含 32 位 armeabi-v7a（默认虽会带，但依赖变化可能悄悄丢掉）。
        // 四个 ABI 与依赖实际提供的一致（实测各含 3 个 .so：
        // libandroidx.graphics.path.so / libimage_processing_util_jni.so / libsurface_util_jni.so），
        // 保留 x86 / x86_64 供模拟器（AVD）调试安装。
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        buildConfigField("String", "DIVINGFISH_CLIENT_ID", "\"$dfClientId\"")
        buildConfigField("String", "DIVINGFISH_CLIENT_SECRET", "\"$dfClientSecret\"")
        buildConfigField("String", "QR_GATEWAY_BASE", "\"$qrGatewayBase\"")
        buildConfigField("String", "QR_GATEWAY_SECRET", "\"$qrGatewaySecret\"")
        buildConfigField("String", "QR_PATH_LOGIN", "\"${qrGatewayPaths.getOrElse(0) { "" }}\"")
        buildConfigField("String", "QR_PATH_REGISTER", "\"${qrGatewayPaths.getOrElse(1) { "" }}\"")
        buildConfigField("String", "QR_PATH_REFRESH", "\"${qrGatewayPaths.getOrElse(2) { "" }}\"")
        buildConfigField("String", "QR_PATH_QR_BIND", "\"${qrGatewayPaths.getOrElse(3) { "" }}\"")
        buildConfigField("String", "QR_PATH_SCORE", "\"${qrGatewayPaths.getOrElse(4) { "" }}\"")
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    // 签名：私钥不入库。CI 由 Secrets 注入同名环境变量并把 base64 解码为 app/kiba-release.jks；
    // 本地可用 -PKIBA_KEYSTORE_PASSWORD=... 等传入，或 export 同名环境变量。口令留空则 release
    // 签名任务直接失败（刻意：宁可报错也不产出签名不对的包）。
    signingConfigs {
        create("release") {
            storeFile = file("kiba-release.jks")
            // 现代 JDK(9+) keytool 默认产出 PKCS12 格式；即便扩展名是 .jks 也要按真实格式读取。
            // storeType 由 CI 按魔数自动探测写入 env，本地可 -Pkiba.storeType=... 覆盖。
            storeType = (project.findProperty("kiba.storeType")
                ?: System.getenv("KIBA_KEYSTORE_TYPE") ?: "PKCS12").toString()
            storePassword = (project.findProperty("kiba.keystorePassword")
                ?: System.getenv("KIBA_KEYSTORE_PASSWORD") ?: "").toString()
            keyAlias = (project.findProperty("kiba.keyAlias")
                ?: System.getenv("KIBA_KEY_ALIAS") ?: "ki-terminal").toString()
            keyPassword = (project.findProperty("kiba.keyPassword")
                ?: System.getenv("KIBA_KEY_PASSWORD") ?: "").toString()
        }
    }

    buildTypes {
        release {
            // R8 代码收缩（含优化）。本仓库 release 不混淆（保留类名/方法名，降低击破反射与
            // 运行时取景层的风险），但仍做收缩以显著减小体积。
            isMinifyEnabled = true
            // 资源收缩暂关闭：本工程多处用 resources.getIdentifier(name, "drawable", …) 按拼接名
            // 动态取 drawable（Best50 瓦片、DX 评级板、表情包等），AAPT 资源收缩看不到这些引用，
            // 会误删资源导致运行时 Resources.NotFoundException。需要资源收缩时须补 res/raw/keep.xml
            // 用 tools:keep 的 glob 显式保留这些 drawable。
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/**"
        }
    }

    lint {
        disable += "ContentDescription"
    }
}

configurations.all {
    resolutionStrategy {
        // miuix 各模块（ui/blur/icons/preference）可能互相拉取不同 miuix-ui 版本，统一锁 0.9.4
        force("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
        // concurrent-futures 1.1.0 的 AbstractResolvableFuture 依赖 guava；
        // 本项目无完整 guava，锁 1.2.0（自包含实现）修 ProfileInstaller NoClassDefFound
        force("androidx.concurrent:concurrent-futures:1.2.0")
    }
}

dependencies {
    // ── miuix（Termux Ultra 视觉体系 / miuix-glass）──
    val miuixVersion = "0.9.4"
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:$miuixVersion")

    // miuix-glass 弹层控件需要 NavigationBackHandler / rememberNavigationEventState（1.1.2 起提供）
    implementation("androidx.navigationevent:navigationevent-compose-android:1.1.2")
    // 根 owner 装配（ViewTreeNavigationEventDispatcherOwner / OnBackInvokedDefaultInput）在
    // navigationevent-android 里，KMP 根模块变体选择下需显式声明才能上编译类路径
    implementation("androidx.navigationevent:navigationevent-android:1.1.2")

    // ── Compose 基础 ──
    implementation("androidx.compose.ui:ui:1.8.3")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.3")
    debugImplementation("androidx.compose.ui:ui-tooling:1.8.3")

    // 图片加载（歌曲封面）
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // ── 数据层（保留 MaimaiData 查分/歌曲核心逻辑）──
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.preference:preference-ktx:1.2.1")

    implementation("com.google.code.gson:gson:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-rx2:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 网络（查分器代理）
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.retrofit2:adapter-rxjava2:2.9.0")
    implementation("io.reactivex.rxjava2:rxjava:2.2.6")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
    implementation("com.squareup.okhttp3:logging-interceptor:3.12.13")

    // 机台成绩更新（VPN 方案）：本地 HTTP 跳转服务
    implementation("org.nanohttpd:nanohttpd:2.2.0")

    // 机台成绩更新（二维码导入）：扫码（CameraX 取景 + ZXing 解码）与图片识别
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.zxing:core:3.5.3")

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

