package com.kira.kiba.core.best50

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 图片保存到系统相册（MediaStore）。
 *
 * Android 10+ 走 MediaStore.Images insert；Android 9 及以下写
 * `Pictures/Kiba/` 公开目录并触发媒体扫描。
 */
object ImageSaver {

    private const val TAG = "ImageSaver"

    /**
     * 保存 PNG 字节到相册。
     * @return 保存后的 Uri；失败返回 null
     */
    fun savePng(context: Context, bytes: ByteArray, displayName: String): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Kiba")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Kiba",
            )
            if (!dir.exists() && !dir.mkdirs()) return@runCatching null
            val file = File(dir, displayName)
            FileOutputStream(file).use { it.write(bytes) }
            // 触发媒体扫描，让相册可见
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)),
            )
            Uri.fromFile(file)
        }
    }.onFailure { Log.w(TAG, "save failed", it) }.getOrNull()
}
