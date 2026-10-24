plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.kira.kiba"
    // miuix 0.9.4 / kyant backdrop 2.0.1 硬要求 compileSdk >= 37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kira.kiba"
        // miuix 0.9.4 各模块（blur/ui/icons/preference）要求 minSdk >= 33
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
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
            isMinifyEnabled = false
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
        // guava 自带 ListenableFuture 与 com.google.guava:listenablefuture 冲突时，用官方空占位包
        eachDependency {
            if (requested.group == "com.google.guava" && requested.name == "listenablefuture") {
                useTarget("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
            }
        }
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

    // 玻璃悬浮导航依赖（miuix-glass 的 LiquidBottomTabs 同源实现）
    implementation("io.github.kyant0:backdrop-android:2.0.1") {
        exclude(group = "org.jetbrains.compose")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
    }
    implementation("io.github.kyant0:shapes-android:1.2.1") {
        exclude(group = "org.jetbrains.compose")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
    }
    // miuix-glass 弹层需要 NavigationBackHandler
    implementation("androidx.navigationevent:navigationevent-compose-android:1.1.2")

    // ── Compose 基础 ──
    implementation("androidx.compose.ui:ui:1.8.3")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
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

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
