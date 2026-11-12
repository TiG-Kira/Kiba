package com.kira.kiba.core.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 账户页顶栏头像的**用户自定义**那份（3.3.1）。
 *
 * ## 取图优先级
 *
 * ```
 * 自定义（本类）  >  自动抓取（AccountAvatar：机台抓包 / 落雪 icon）  >  默认头像（昵称首字）
 * ```
 *
 * 自定义是全局一份，不按游戏分：用户自己裁的图是他本人的「头像」，
 * 切游戏时不需要换脸。自动抓取那张仍然是按「游戏 × 数据源」四格的，
 * 只有在没设自定义时才参与兜底。
 *
 * ## 存储
 *
 * 图落盘在 `filesDir/avatar/custom_avatar.png`（512×512 PNG），
 * SharedPreferences 里只留一个版本号（见 [SpUtil.getCustomAvatarVersion]）：
 *
 * - **为什么不存路径**：路径一旦写进 SP，改目录就变成迁移问题；文件名是契约更稳。
 * - **为什么用版本号而不是「文件是否存在」**：同尺寸重设一张图，文件存在性不变，
 *   UI 侧 `remember(key)` 就不会重算，顶栏会继续挂着旧图。版本号每次变更 +1，
 *   拿来当 key 一定失效。
 * - **为什么 PNG 不 JPEG**：用户上传的图可能带透明，JPEG 会把透明压成黑块。
 *   512² 的照片级 PNG 约 400~700KB，单张常驻可以接受。
 *
 * ## 立即生效
 *
 * [state] 是进程内的 StateFlow：设置页保存完，账户页顶栏那一帧就换图，
 * 不需要重启 Activity，也不需要切换查分器（这正是 3.3.0 里「得切源才刷出来」的病根 ——
 * 当时头像跟着 `AccountAvatar` 的自动抓取走，只有切源才会让 URL 变化）。
 */
object CustomAvatar {

    private const val TAG = "CustomAvatar"
    private const val DIR = "avatar"
    private const val NAME = "custom_avatar.png"

    /** 落盘边长。顶栏槽位 168dp，3x 屏 504px，512 刚好够且不算大。 */
    const val SAVE_PX = 512

    /** [version] 每次变更 +1；[hasImage] 表示当前是否有一张可用的自定义头像。 */
    data class State(val version: Long = 0L, val hasImage: Boolean = false)

    private var appContext: Context? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 进程内的解码缓存。
     *
     * 顶栏每次进账户页都会重新组合，512² PNG 现解一次约 5~10ms；缓存住以后
     * 切页 / 折叠 / 重进都是直接取引用。体积 1MB 常驻，比反复解码划算。
     */
    @Volatile
    private var cached: Bitmap? = null

    /** 必须在 `Application.onCreate` 里调（早于任何 UI 读取 [state]）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        val version = SpUtil.getCustomAvatarVersion()
        _state.value = State(version = version, hasImage = file()?.isFile == true)
        Log.i(TAG, "init version=$version hasImage=${_state.value.hasImage}")
    }

    fun current(): State = _state.value

    private fun file(): File? {
        val context = appContext ?: return null
        return File(File(context.filesDir, DIR), NAME)
    }

    /** 读文件并解码（512² 直接解，不需要采样）。没有图 / 解失败返回 null。 */
    fun loadBitmap(): Bitmap? {
        cached?.let { if (!it.isRecycled) return it }
        val target = file()?.takeIf { it.isFile } ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(target.absolutePath) }
            .onFailure { Log.w(TAG, "decode failed", it) }
            .getOrNull()
        if (bitmap != null) cached = bitmap
        return bitmap
    }

    /**
     * 保存一张已裁好的正方形图。
     *
     * 先写 `.tmp` 再改名：中途被杀进程不会留下半张 PNG（下次启动解出来是一堆花屏），
     * 失败时旧图原样保留 —— 「换头像失败」比「头像没了」好处理得多。
     */
    fun save(bitmap: Bitmap): Boolean {
        val target = file() ?: return false
        return runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$NAME.tmp")
            tmp.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    error("compress failed")
                }
            }
            if (target.exists() && !target.delete()) error("delete old failed")
            if (!tmp.renameTo(target)) error("rename failed")
            cached = bitmap
            bump(hasImage = true)
            Log.i(TAG, "saved ${target.length() / 1024} KB")
            true
        }.getOrElse {
            Log.w(TAG, "save failed", it)
            false
        }
    }

    /** 恢复默认（同时回落到自动抓取那张，如果抓得到的话）。 */
    fun clear(): Boolean = runCatching {
        file()?.delete()
        cached = null
        bump(hasImage = false)
        Log.i(TAG, "cleared")
        true
    }.getOrElse {
        Log.w(TAG, "clear failed", it)
        false
    }

    private fun bump(hasImage: Boolean) {
        val version = SpUtil.getCustomAvatarVersion() + 1L
        SpUtil.setCustomAvatarVersion(version)
        _state.value = State(version = version, hasImage = hasImage)
    }

    // ── 裁切输出 ───────────────────────────────────────────────────────

    /**
     * 把源图按 [rect] 裁成正方形，再高质量缩到 [SAVE_PX]。
     *
     * 一律走 [Canvas.drawBitmap(Rect, Rect, Paint)] 而不是 `Bitmap.createBitmap(src, x, y, w, h)`
     * 再 `createScaledBitmap`：后者两步都是整数像素、且缩放用的是最近邻的实现细节，
     * 头像这种要长期挂在顶栏上的图很容易看出锯齿。这里一步带 `FILTER_BITMAP_FLAG` 画完。
     */
    fun exportCrop(source: Bitmap, rect: CropRect): Bitmap {
        val out = Bitmap.createBitmap(SAVE_PX, SAVE_PX, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }
        Canvas(out).drawBitmap(
            source,
            Rect(rect.left, rect.top, rect.left + rect.size, rect.top + rect.size),
            Rect(0, 0, SAVE_PX, SAVE_PX),
            paint,
        )
        return out
    }
}
