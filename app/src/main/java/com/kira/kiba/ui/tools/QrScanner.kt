package com.kira.kiba.ui.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.Executors

/**
 * 二维码识别工具集：摄像头实时扫码 + 相册图片解码。
 * 识别结果（SGWCMAID 串）只经回调交回调用方，本文件不做任何持久化。
 */
object QrDecode {

    /** 从 Uri 解码二维码内容（自动降采样，失败返回 null）。**不缓存任何图像数据**。 */
    suspend fun decodeQrFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1536) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.getOrNull() ?: return@withContext null

        bitmap.useAndDecode()
    }

    /** Bitmap → ZXing 解码；解码后立即回收位图。 */
    private fun Bitmap.useAndDecode(): String? = try {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        decodeLuminance(source)
    } finally {
        recycle()
    }

    internal fun decodeLuminance(source: RGBLuminanceSource): String? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            null
        }
    }
}

/** 摄像头逐帧二维码分析器（YUV → Bitmap → ZXing）。 */
private class QrFrameAnalyzer(
    private val onResult: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    @Volatile
    private var fired = false

    override fun analyze(image: ImageProxy) {
        if (fired) {
            image.close()
            return
        }
        try {
            val bitmap = image.toBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val text = QrDecode.decodeLuminance(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
            bitmap.recycle()
            if (text != null && !fired) {
                fired = true
                onResult(text)
            }
        } catch (_: Exception) {
            // 非二维码帧，忽略
        } finally {
            image.close()
        }
    }
}

/**
 * 全屏摄像头扫码浮层：取景框 + ZXing 逐帧解码。
 * 识别成功后回调 [onResult]（通常调用方随即关闭浮层）。
 */
@Composable
fun QrCameraScanner(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            analyzerExecutor.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        val provider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@LaunchedEffect
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analyzerExecutor, QrFrameAnalyzer(onResult))
            }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // 取景框提示
        Text(
            text = "对准「舞萌 DX」公众号的登录二维码\n识别成功后会自动填入",
            style = MiuixTheme.textStyles.footnote1,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 220.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = onClose, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(text = "取消扫码", color = MiuixTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 「从图片识别」选择器：挂起等待图片 → 解码二维码 → 回调文本（失败回调 null）。
 * 用法：`val picker = rememberQrImagePicker { text -> ... }`，点击时
 * `picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))`。
 */
@Composable
fun rememberQrImagePicker(
    onDecoded: (String?) -> Unit,
): ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            onDecoded(null)
        } else {
            scope.launch { onDecoded(QrDecode.decodeQrFromUri(context, uri)) }
        }
    }
}

/** 摄像头权限检查工具。 */
internal fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
