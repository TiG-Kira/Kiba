package com.kira.kiba.core.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 外部浏览器跳转工具（工具箱「实用网站」入口用）。
 *
 * 统一走 [Intent.ACTION_VIEW]：系统会分发给用户默认浏览器或可处理 http(s)
 * 的应用（含浏览器内建的站内搜索/定位授权，如 FindMaiMai 的机厅地图）。
 * 无可处理应用时给用户可见反馈，不静默吞异常。
 */
object BrowserJump {

    /** 用外部浏览器打开网页；失败 Toast 提示。 */
    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "未找到可用的浏览器应用", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "打开链接失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
