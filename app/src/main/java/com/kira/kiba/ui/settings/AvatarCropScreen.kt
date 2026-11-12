package com.kira.kiba.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.AvatarCrop
import com.kira.kiba.core.account.CustomAvatar
import com.kira.kiba.ui.common.pageContentPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Image
import top.yukonga.miuix.kmp.icon.glass.Photos
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 源图最长边上限：裁切输出 512，1440 足够，再大只是白吃内存。 */
private const val MAX_SOURCE_PX = 1440

/**
 * 自定义头像页（3.3.1）：选图 → 1:1 裁切 → 落盘。
 *
 * ## 两种状态
 *
 * - **预览态**：显示当前头像（自定义 or 默认），两个入口「从相册选择」「恢复默认头像」；
 * - **裁切态**：整块正方形取景框，拖动平移 + 双指缩放，底部「取消 / 使用这张」。
 *
 * 裁切态不做成独立路由：选完图立刻原地切换，取消就退回预览态，
 * 避免「选错图 → 返回 → 再进 → 再选」这种来回折腾。
 *
 * ## 取景框为什么是方的
 *
 * 账户页顶栏那个头像就是方形大图（无圆角、贴屏角、左/下羽化）。取景框跟着
 * 用 1:1 才能所见即所得 —— 圆形取景框在这里会骗人：框内四角其实也会显示出来。
 *
 * 几何计算全在 [AvatarCrop] 里（纯函数 + 单测），这里只负责手势与绘制：
 * 绘制和最终裁切读的是**同一组** `zoom / offset`，所以框里看到什么，存下去就是什么。
 */
@Composable
fun AvatarCropScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avatarState by CustomAvatar.state.collectAsState()

    // 非 null = 正在裁切；null = 预览态
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        notice = null
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { decodeForCrop(context, uri) }.getOrNull()
            }
            loading = false
            if (bitmap == null) {
                notice = "这张图片读不出来，换一张试试"
            } else {
                source = bitmap
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(pageContentPadding(withNavBar = false)),
    ) {
        val current = source
        if (current == null) {
            PreviewStage(
                hasImage = avatarState.hasImage,
                version = avatarState.version,
                loading = loading,
                notice = notice,
                onPick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onClear = {
                    CustomAvatar.clear()
                    notice = "已恢复默认头像"
                },
            )
        } else {
            CropStage(
                source = current,
                saving = saving,
                onCancel = { source = null },
                onConfirm = { zoom, offset, framePx ->
                    saving = true
                    scope.launch {
                        val rect = AvatarCrop.cropRect(
                            offsetX = offset.x,
                            offsetY = offset.y,
                            frame = framePx,
                            imageW = current.width,
                            imageH = current.height,
                            zoom = zoom,
                        )
                        val ok = withContext(Dispatchers.Default) {
                            val out = CustomAvatar.exportCrop(current, rect)
                            withContext(Dispatchers.IO) { CustomAvatar.save(out) }
                        }
                        saving = false
                        if (ok) {
                            source = null
                            onDone()
                        } else {
                            notice = "保存失败，请重试"
                        }
                    }
                },
            )
        }
    }
}

// ── 预览态 ───────────────────────────────────────────────────────────

@Composable
private fun PreviewStage(
    hasImage: Boolean,
    version: Long,
    loading: Boolean,
    notice: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AvatarPreview(version = version, size = 96.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when {
                        loading -> "正在读取图片…"
                        hasImage -> "账户页右上角显示这张图"
                        else -> "尚未设置，账户页显示默认头像"
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = "从相册选择",
                summary = "选图后按 1:1 裁切，可拖动 / 双指缩放",
                startAction = {
                    Icon(
                        imageVector = MiuixGlassIcons.Regular.Photos,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(26.dp),
                    )
                },
                onClick = onPick,
            )
            ArrowPreference(
                title = "恢复默认头像",
                summary = if (hasImage) "移除自定义头像，回到默认头像" else "当前已是默认头像",
                startAction = {
                    Icon(
                        imageVector = MiuixGlassIcons.Regular.Delete,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(26.dp),
                    )
                },
                onClick = onClear,
                enabled = hasImage,
            )
        }

        Text(
            text = notice ?: "头像只有这一份，舞萌与中二共用；没设置时右上角是昵称首字的默认头像。",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

/** 当前头像预览（方形，圆角只用在预览上 —— 顶栏那张是直角）。 */
@Composable
private fun AvatarPreview(version: Long, size: Dp) {
    val bitmap = remember(version) { CustomAvatar.loadBitmap() }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = remember(version, bitmap) { bitmap.asImageBitmap() },
                contentDescription = "当前头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = MiuixGlassIcons.Regular.Image,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(size * 0.34f),
            )
        }
    }
}

// ── 裁切态 ───────────────────────────────────────────────────────────

@Composable
private fun CropStage(
    source: Bitmap,
    saving: Boolean,
    onCancel: () -> Unit,
    onConfirm: (zoom: Float, offset: Offset, framePx: Float) -> Unit,
) {
    // 变换状态放在这里而不是 Canvas 里：底部「使用这张」要用同一组值算裁切矩形。
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val side = minOf(maxWidth - 16.dp, maxHeight * 0.62f, 380.dp)
        val framePx = with(LocalDensity.current) { side.toPx() }

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "拖动调整位置，双指缩放 · 框内即头像",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CropCanvas(
                    source = source,
                    side = side,
                    framePx = framePx,
                    zoom = zoom,
                    offset = offset,
                    onTransform = { nextZoom, nextOffset ->
                        zoom = nextZoom
                        offset = nextOffset
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                )
                Button(
                    onClick = { onConfirm(zoom, offset, framePx) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                ) {
                    Text(text = if (saving) "保存中…" else "使用这张")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 取景框画布。
 *
 * 绘制用的 `dstOffset / dstSize` 与 [AvatarCrop.cropRect] 完全同源：
 * 都是「按 s 缩放后居中 + offset」，所以框里看到什么，存下去就是什么。
 */
@Composable
private fun CropCanvas(
    source: Bitmap,
    side: Dp,
    framePx: Float,
    zoom: Float,
    offset: Offset,
    onTransform: (zoom: Float, offset: Offset) -> Unit,
) {
    val image: ImageBitmap = remember(source) { source.asImageBitmap() }
    Canvas(
        modifier = Modifier
            .size(side)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .pointerInput(source, framePx) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val nextZoom = (zoom * gestureZoom).coerceIn(1f, AvatarCrop.MAX_ZOOM)
                    // 以双指中心为锚点缩放：缩放前后，手指底下的那块图不动。
                    val s0 = AvatarCrop.effectiveScale(framePx, source.width, source.height, zoom)
                    val s1 = AvatarCrop.effectiveScale(framePx, source.width, source.height, nextZoom)
                    val ratio = if (s0 > 0f) s1 / s0 else 1f
                    val cx = centroid.x - framePx / 2f
                    val cy = centroid.y - framePx / 2f
                    val nx = cx - (cx - offset.x) * ratio + pan.x
                    val ny = cy - (cy - offset.y) * ratio + pan.y
                    val (clampedX, clampedY) = AvatarCrop.clampOffset(
                        offsetX = nx,
                        offsetY = ny,
                        frame = framePx,
                        imageW = source.width,
                        imageH = source.height,
                        zoom = nextZoom,
                    )
                    onTransform(nextZoom, Offset(clampedX, clampedY))
                }
            },
    ) {
        val s = AvatarCrop.effectiveScale(framePx, source.width, source.height, zoom)
        val dw = source.width * s
        val dh = source.height * s
        val dx = framePx / 2f + offset.x - dw / 2f
        val dy = framePx / 2f + offset.y - dh / 2f
        drawImage(
            image = image,
            dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()),
            dstSize = IntSize(
                dw.roundToInt().coerceAtLeast(1),
                dh.roundToInt().coerceAtLeast(1),
            ),
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * 解码待裁的图：按最长边 [MAX_SOURCE_PX] 缩放，软位图（要用 Canvas 画，硬件位图读不了）。
 *
 * 走 [ImageDecoder] 而不是 BitmapFactory：它会自己处理 EXIF 方向，
 * 竖拍照片不会在取景框里横过来。
 */
private fun decodeForCrop(context: Context, uri: Uri): Bitmap? {
    val src = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > MAX_SOURCE_PX) {
            val ratio = MAX_SOURCE_PX.toFloat() / longest
            decoder.setTargetSize(
                (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                (info.size.height * ratio).roundToInt().coerceAtLeast(1),
            )
        }
    }
}
