package com.kira.kiba.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.kira.kiba.R
import com.kira.kiba.BuildConfig
import com.kira.kiba.core.update.ApkDownloader
import com.kira.kiba.core.update.UpdateChecker
import com.kira.kiba.core.update.UpdateResult
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.AboutBgEffect
import com.kira.kiba.ui.common.MarkdownContent
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import com.kira.kiba.ui.material.KibaGlassDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页（移植自 Termux Ultra 的关于页 + 软件更新逻辑）：
 * 头部（图标 / 名称 / 版本 + 发布状态标签）、设备信息、开发者、检查更新
 * （GitHub Releases，含下载与安装）、测试版更新开关、版本信息、数据来源等。
 */
@Composable
fun AboutScreen(scrollBehavior: ScrollBehavior) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val updatePrefs = remember { context.getSharedPreferences(PREF_UPDATE, android.content.Context.MODE_PRIVATE) }

    var checkingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadingApk by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var pendingInstallVersion by remember { mutableStateOf<String?>(null) }
    var releaseStatus by remember { mutableStateOf<UpdateChecker.ReleaseStatus?>(null) }
    var betaUpdateEnabled by remember { mutableStateOf(updatePrefs.getBoolean(KEY_ENABLE_BETA, false)) }
    var showBetaWarningDialog by remember { mutableStateOf(false) }

    val currentVersion = remember { BuildConfig.VERSION_NAME }

    // 当前版本的发布状态：正式 / Beta / 未发布（内部构建）
    LaunchedEffect(currentVersion) {
        releaseStatus = UpdateChecker.getReleaseStatus(currentVersion)
    }

    // ── 高级渐变背景（移植 Termux Ultra：HyperCeiler 风格 RuntimeShader 动画，
    //    minSdk 33 恒可用，不设 Brush fallback）──
    val darkTheme = isSystemInDarkTheme()
    // 头部前景：Logo 染色与文字同色（Termux Ultra 同款），渐变从白色像素处透出
    val headerFg = if (darkTheme) Color.White else Color(0xFF333333)

    // 滚动跟踪：滚过头部时背景渐隐、头部内容淡出
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val headerHeightPx = with(density) { 100.dp.toPx() }
    var scrollFraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.offset ?: 0 }
            .collect { offset ->
                val firstIndex = listState.firstVisibleItemIndex
                scrollFraction = if (firstIndex == 0) {
                    (-offset.toFloat() / headerHeightPx).coerceIn(0f, 1f)
                } else {
                    1f
                }
            }
    }

    var bgController by remember { mutableStateOf<AboutBgEffect.ShaderController?>(null) }
    var bgDarkTheme by remember { mutableStateOf(darkTheme) }

    Box(modifier = Modifier.fillMaxSize()) {
        // RuntimeShader 动画渐变层（滚过头部后渐隐；暗色下减半）
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - scrollFraction) * (if (darkTheme) 0.5f else 1f)
                }
                .blur(120.dp)
                .zIndex(-1f),
            factory = { ctx ->
                android.view.View(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    val ctl = AboutBgEffect.createFor(this, darkTheme)
                    bgController = ctl
                    bgDarkTheme = darkTheme
                    ctl?.start()
                }
            },
            update = { _ ->
                if (bgDarkTheme != darkTheme) {
                    bgController?.updateParams(AboutBgEffect.getParams(darkTheme))
                    bgDarkTheme = darkTheme
                }
            },
        )

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(withNavBar = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 头部：Logo（染色透出渐变）+ 名称 + 版本 + 状态标签 ──
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = 1f - scrollFraction }
                        .padding(top = 48.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // App Logo：用 launcher 前景图（ic_launcher_foreground），
                    // 按主题整体重着色：亮色→黑、暗色→白（保留原 alpha 形状）
                    val appIcon = remember(darkTheme) {
                        val tintColor =
                            if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        runCatching {
                            ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
                                ?.toBitmap()
                        }.getOrNull()?.let { bm ->
                            val copy = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                            val pixels = IntArray(copy.width * copy.height)
                            copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
                            for (i in pixels.indices) {
                                val a = (pixels[i] shr 24) and 0xFF
                                if (a > 128) {
                                    // 非透明像素 → 统一染成目标色，alpha 保持原值（软边）
                                    pixels[i] = (a shl 24) or (tintColor and 0x00FFFFFF)
                                }
                            }
                            copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
                            BitmapPainter(copy.asImageBitmap())
                        }
                    }
                    if (appIcon != null) {
                        Image(
                            painter = appIcon,
                            contentDescription = "Logo",
                            modifier = Modifier.size(160.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Kiba",
                        style = TextStyle(
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Black,
                            color = headerFg,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "v$currentVersion",
                            style = TextStyle(
                                fontSize = 16.sp,
                                color = headerFg,
                            ),
                        )
                        when (releaseStatus) {
                            UpdateChecker.ReleaseStatus.PRERELEASE -> StatusTag("Beta", MiuixTheme.colorScheme.error)
                            UpdateChecker.ReleaseStatus.NOT_FOUND -> StatusTag("Internal", Color(0xFFFF9800))
                            else -> {}
                        }
                    }
                }
            }

            // ── 设备信息 ──
            item(key = "device_title") { SectionTitle("设备") }
            item(key = "device_card") {
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InfoRow("设备型号", android.os.Build.MODEL)
                        InfoRow("系统版本", "Android ${android.os.Build.VERSION.RELEASE}")
                        InfoRow("内核版本", android.os.Build.DISPLAY)
                    }
                }
            }

            // ── 开发者 ──
            item(key = "dev_title") { SectionTitle("开发者") }
            item(key = "dev_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    AvatarArrowRow(
                        title = "极犽",
                        summary = "@TiG-Kira",
                        avatarUrl = "https://github.com/TiG-Kira.png",
                        onClick = { openUrl(context, "https://github.com/TiG-Kira") },
                    )
                }
            }

            // ── 数据来源（保留原有文案）──
            item(key = "data_title") { SectionTitle("数据来源") }
            item(key = "data_card") {
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "· 曲库 / 歌曲数据：水鱼（diving-fish）公共曲库；\n" +
                                "· 成绩 / Best 50 / Rating：水鱼查分器（用户本人授权只读）；\n" +
                                "· 账号密码通道的 jwt 仅存本机私有目录，不保存任何账号口令。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            // ── 检查更新 ──
            item(key = "update_title") { SectionTitle("软件更新") }
            item(key = "update_card") {
                val updateSummary = when {
                    checkingUpdate -> "正在检查更新…"
                    updateResult is UpdateResult.UpdateAvailable -> {
                        if ((updateResult as UpdateResult.UpdateAvailable).isBeta) "发现测试版更新"
                        else "发现新版本"
                    }
                    updateResult is UpdateResult.UpToDate -> "已是最新版本"
                    else -> "检查 GitHub 上的最新版本"
                }
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        title = "检查更新",
                        summary = updateSummary,
                        onClick = {
                            if (!checkingUpdate) {
                                checkingUpdate = true
                                scope.launch {
                                    updateResult = UpdateChecker.checkForUpdates(currentVersion, betaUpdateEnabled)
                                    showUpdateDialog = true
                                    checkingUpdate = false
                                }
                            }
                        },
                    )
                    SwitchPreference(
                        title = "接收测试版更新",
                        summary = "检查更新时包含标记为 Pre-release 的测试版本",
                        checked = betaUpdateEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                betaUpdateEnabled = true
                                showBetaWarningDialog = true
                            } else {
                                betaUpdateEnabled = false
                                updatePrefs.edit().putBoolean(KEY_ENABLE_BETA, false).apply()
                            }
                        },
                    )
                }
            }

            // ── 版本信息 ──
            item(key = "version_card") {
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Kiba 版本",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            ),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "v$currentVersion",
                                style = TextStyle(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                ),
                            )
                            if (releaseStatus == UpdateChecker.ReleaseStatus.NOT_FOUND) {
                                Spacer(Modifier.width(8.dp))
                                StatusTag("Internal", Color(0xFFFF9800))
                            }
                        }
                        Text(
                            text = "versionCode ${BuildConfig.VERSION_CODE}\n" +
                                "更新包经 GitHub Release 分发，安装前会校验签名与版本。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            // ── 致谢（保留原有文案）──
            item(key = "thanks_title") { SectionTitle("致谢") }
            item(key = "thanks_card") {
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "· MaimaiData（PaperPig）—— 数据链路与 UI 交互参考；\n" +
                                "· miuix（YuKongA）—— 组件库；\n" +
                                "· Termux-Ultra —— 玻璃拟态设计参考。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            // ── 开源（保留原有文案）──
            item(key = "oss_title") { SectionTitle("开源") }
            item(key = "oss_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        title = "github.com/TiG-Kira/Kiba",
                        summary = "问题反馈与功能建议欢迎提 issue",
                        onClick = { openUrl(context, "https://github.com/TiG-Kira/Kiba") },
                    )
                }
            }
        }
        }
    }

    // ── 更新对话框 ──
    if (showUpdateDialog && updateResult != null) {
        // 「安装权限授权往返」后重新校验并安装已下载的更新包
        if (pendingInstallVersion != null && ApkDownloader.hasInstallPermission(context)) {
            val apkFile = ApkDownloader.getDownloadedApkFile(context, pendingInstallVersion!!)
            LaunchedEffect(pendingInstallVersion) {
                // 授权往返期间产物可能被替换，安装前在后台重新校验一次
                val verified = withContext(Dispatchers.IO) {
                    ApkDownloader.verifyApkFile(context, apkFile).isSuccess
                }
                if (verified) {
                    runCatching { ApkDownloader.installApk(context, apkFile) }
                } else {
                    withContext(Dispatchers.IO) { apkFile.delete() }
                }
                pendingInstallVersion = null
                showUpdateDialog = false
            }
        }

        val result = updateResult!!
        KibaGlassDialog(
            show = showUpdateDialog,
            // 豁免：软件更新窗口无论材质开关如何，一律使用默认弹层
            forceDefault = true,
            title = when (result) {
                is UpdateResult.UpdateAvailable ->
                    if (result.isBeta) "发现测试版更新" else "发现新版本"
                is UpdateResult.UpToDate -> "已是最新版本"
                is UpdateResult.CheckFailed -> "检查更新失败"
            },
            summary = when (result) {
                is UpdateResult.UpdateAvailable -> {
                    val preReleaseTag = if (result.isBeta) "（Beta）" else ""
                    "当前版本：v${result.currentVersionName}\n最新版本：${result.latestVersionName}$preReleaseTag"
                }
                is UpdateResult.UpToDate -> "当前已是最新版本 v${result.currentVersionName}"
                is UpdateResult.CheckFailed -> "无法连接 GitHub，请检查网络后重试"
            },
            onDismissRequest = { showUpdateDialog = false },
            content = {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    // 有新版本时展示新版本的 Release Notes；已是最新版时展示当前版本自身的日志
                    val notesToShow = when (result) {
                        is UpdateResult.UpdateAvailable -> result.releaseNotes
                        is UpdateResult.UpToDate -> result.releaseNotes
                        is UpdateResult.CheckFailed -> ""
                    }
                    if (notesToShow.isNotBlank()) {
                        Text(
                            text = "更新日志",
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        MarkdownContent(
                            text = notesToShow,
                            modifier = Modifier
                                .padding(bottom = 16.dp)
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    if (downloadingApk) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "正在下载更新…",
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    ),
                                )
                                Text(
                                    text = "$downloadProgress%",
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.primary,
                                    ),
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MiuixTheme.colorScheme.surfaceVariant),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(downloadProgress / 100f)
                                        .height(6.dp)
                                        .background(MiuixTheme.colorScheme.primary),
                                )
                            }
                            if (totalBytes > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${formatUpdateFileSize(downloadedBytes)} / ${formatUpdateFileSize(totalBytes)}",
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    ),
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (result is UpdateResult.UpdateAvailable) {
                            Arrangement.spacedBy(8.dp)
                        } else {
                            Arrangement.Center
                        },
                    ) {
                        if (result is UpdateResult.UpdateAvailable) {
                            TextButton(
                                text = "稍后",
                                onClick = { showUpdateDialog = false },
                                modifier = Modifier.weight(1f),
                                enabled = !downloadingApk,
                            )
                            TextButton(
                                text = "手动",
                                onClick = {
                                    openUrl(context, result.releaseUrl)
                                    showUpdateDialog = false
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !downloadingApk,
                            )
                            Button(
                                onClick = {
                                    downloadingApk = true
                                    downloadProgress = 0
                                    downloadedBytes = 0L
                                    totalBytes = 0L
                                    val downloadUrl = UpdateChecker.constructDownloadUrl(result.latestVersionName)
                                    scope.launch(Dispatchers.IO) {
                                        val downloadResult = ApkDownloader.downloadAndInstall(
                                            context,
                                            downloadUrl,
                                            result.latestVersionName,
                                        ) { progress, downloaded, total ->
                                            downloadProgress = progress
                                            downloadedBytes = downloaded
                                            totalBytes = total
                                        }
                                        downloadingApk = false
                                        if (downloadResult.isFailure) {
                                            // 下载失败回退到浏览器手动下载
                                            openUrl(context, result.releaseUrl)
                                            showUpdateDialog = false
                                        } else if (!ApkDownloader.hasInstallPermission(context)) {
                                            pendingInstallVersion = result.latestVersionName
                                        } else {
                                            showUpdateDialog = false
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = MiuixTheme.colorScheme.primary,
                                ),
                                enabled = !downloadingApk,
                            ) {
                                Text(text = "下载", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        } else {
                            Button(
                                onClick = { showUpdateDialog = false },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    color = MiuixTheme.colorScheme.primary,
                                ),
                            ) {
                                Text(text = "好的", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            },
        )
    }

    // ── 测试版开启警告 ──
    KibaGlassDialog(
        show = showBetaWarningDialog,
        // 豁免：与软件更新窗口保持一致，使用默认弹层
        forceDefault = true,
        title = "开启测试版更新？",
        summary = "测试版本（Pre-release）可能不稳定，存在崩溃或数据异常的风险。" +
            "仅建议愿意帮忙尝鲜并反馈问题的用户开启。",
        onDismissRequest = {
            betaUpdateEnabled = false
            showBetaWarningDialog = false
        },
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = {
                        betaUpdateEnabled = false
                        showBetaWarningDialog = false
                    },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        updatePrefs.edit().putBoolean(KEY_ENABLE_BETA, true).apply()
                        showBetaWarningDialog = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary,
                    ),
                ) {
                    Text(text = "开启", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        },
    )
}

/** 版本状态小标签（Beta / Internal）。 */
@Composable
private fun StatusTag(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
            ),
        )
    }
}

/** 设备信息行（值 + 标题）。 */
@Composable
private fun InfoRow(title: String, value: String) {
    Column {
        Text(
            text = value,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
            ),
        )
        Text(
            text = title,
            style = TextStyle(
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            ),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 带圆形头像的箭头行（开发者 / 贡献者）。 */
@Composable
private fun AvatarArrowRow(
    title: String,
    summary: String,
    avatarUrl: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var avatar by remember(avatarUrl) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(avatarUrl) {
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(avatarUrl)
                        .allowHardware(false)
                        .build()
                ).drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
            }.getOrNull()
        }
        if (bmp != null) avatar = bmp
    }
    ArrowPreference(
        title = title,
        summary = summary,
        onClick = onClick,
        startAction = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = avatar
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        },
    )
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        )
    }
}

private fun formatUpdateFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 1024.0).toInt()} KB"
        bytes < 1024L * 1024 * 1024 -> "${(bytes / (1024.0 * 1024.0)).let { String.format("%.1f", it) }} MB"
        else -> "${(bytes / (1024.0 * 1024.0 * 1024.0)).let { String.format("%.2f", it) }} GB"
    }
}

private const val PREF_UPDATE = "update_preferences"
private const val KEY_ENABLE_BETA = "enable_beta_update"
