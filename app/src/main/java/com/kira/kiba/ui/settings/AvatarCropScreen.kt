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
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
    // 变换只存**一个** State：zoom 与 offset 必须原子更新，分成两个 State 会在
    // 同一回调里先写 zoom 后写 offset，重组时可能读到「新 zoom + 旧 offset」的中间态。
    val transform = remember(source) { mutableStateOf(CropTransform()) }
    // 取景框实测像素边长（onSizeChanged 写入）。绘制与 clamp 都读它，保证同源。
    var framePx by remember(source) { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "拖动调整位置，双指缩放 · 框内即头像",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        // 手势区 = 整块可用区域，比取景框大一圈：框只有屏宽的一部分，
        // 双指捏合时第二根手指很容易落在框外，挂在框上就只有单指 pan，放大形同无效。
        //
        // 手势节点用普通 Box，取景框尺寸交给内层 `BoxWithConstraints` 量：
        // 布局代码按 SubcomposeLayout 的子组合顺序执行，把手势和测量拆开最省心，
        // 也保证 `side` 是按这块区域自己的约束算的（取景框必须严格正方形）。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(source) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val frame = framePx
                        if (frame <= 0f) return@detectTransformGestures
                        val cur = transform.value
                        val nextZoom = (cur.zoom * gestureZoom)
                            .coerceIn(1f, AvatarCrop.MAX_ZOOM)
                        // 以双指中心为锚点缩放：缩放前后，手指底下的那块图不动。
                        val s0 = AvatarCrop.effectiveScale(
                            frame, source.width, source.height, cur.zoom,
                        )
                        val s1 = AvatarCrop.effectiveScale(
                            frame, source.width, source.height, nextZoom,
                        )
                        val ratio = if (s0 > 0f) s1 / s0 else 1f
                        // 手势区比取景框大，但框恒居中 —— 所以手势区中心就是框中心。
                        val area = this@pointerInput.size
                        val cx = centroid.x - area.width / 2f
                        val cy = centroid.y - area.height / 2f
                        val nx = cx - (cx - cur.offset.x) * ratio + pan.x
                        val ny = cy - (cy - cur.offset.y) * ratio + pan.y
                        val (clampedX, clampedY) = AvatarCrop.clampOffset(
                            offsetX = nx,
                            offsetY = ny,
                            frame = frame,
                            imageW = source.width,
                            imageH = source.height,
                            zoom = nextZoom,
                        )
                        transform.value = CropTransform(nextZoom, Offset(clampedX, clampedY))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth - 32.dp, maxHeight - 8.dp, 380.dp)
                    .coerceAtLeast(80.dp)
                CropFrame(
                    source = source,
                    side = side,
                    transform = transform,
                    onSizeChanged = { framePx = it },
                )
            }
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
                onClick = {
                    val t = transform.value
                    onConfirm(t.zoom, t.offset, framePx)
                },
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

/**
 * 裁切态的一次变换。
 *
 * - [zoom]：相对「铺满」的放大倍数（≥ 1，≤ [AvatarCrop.MAX_ZOOM]）；
 * - [offset]：图片中心相对取景框中心的位移，单位是取景框屏幕像素。
 *
 * 两个值必须一起写（见 [CropStage]），否则会出现「新 zoom 配旧 offset」的中间帧。
 */
private data class CropTransform(
    val zoom: Float = 1f,
    val offset: Offset = Offset.Zero,
)

/**
 * 取景框。
 *
 * ## 为什么缩放不重绘位图
 *
 * 画布只画**基准那一帧**（zoom=1 的铺满图），缩放与平移全部交给
 * [graphicsLayer] 的 `scaleX/scaleY/translationX/translationY`。
 *
 * 之前的写法是把缩放后的目标尺寸算进 `drawImage(dstSize = 原图 × s)`，
 * 每一帧都要对 1440px 的源图做一次高质量重采样 —— 掉帧之后手势事件成批到达，
 * 拖起来就是一顿一顿地「发抖」。走 layer 以后缩放只是 GPU 合成，手势期间
 * 既不重组（State 只在 layer block 里读），也不重采样，跟手程度完全不一样。
 *
 * ## 为什么用 onSizeChanged 而不是 side.toPx()
 *
 * `Modifier.size(side)` 在父约束不足时会被**压小**，`side.toPx()` 却仍是请求值。
 * 两者一旦不等，clamp 允许的拖动余量就比肉眼看到的小，手感是「拖不动」。
 * 这里把画布实测宽度交回去，绘制、clamp、裁切共用同一个 frame。
 *
 * 几何仍然与 [AvatarCrop.cropRect] 同源：图片按 `baseScale × zoom` 缩放、
 * 中心落在「框中心 + offset」，所以框里看到什么，存下去就是什么。
 */
@Composable
private fun CropFrame(
    source: Bitmap,
    side: Dp,
    transform: State<CropTransform>,
    onSizeChanged: (Float) -> Unit,
) {
    val image: ImageBitmap = remember(source) { source.asImageBitmap() }
    Canvas(
        modifier = Modifier
            .size(side)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .onSizeChanged { onSizeChanged(it.width.toFloat()) }
            .graphicsLayer {
                // 只在 layer block 里读 State：值变只失效图层，不触发重组/重绘。
                val t = transform.value
                scaleX = t.zoom
                scaleY = t.zoom
                translationX = t.offset.x
                translationY = t.offset.y
            },
    ) {
        val frame = size.width
        val base = AvatarCrop.baseScale(frame, source.width, source.height)
        val bw = source.width * base
        val bh = source.height * base
        drawImage(
            image = image,
            dstOffset = IntOffset(
                ((frame - bw) / 2f).roundToInt(),
                ((frame - bh) / 2f).roundToInt(),
            ),
            dstSize = IntSize(bw.roundToInt().coerceAtLeast(1), bh.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
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
