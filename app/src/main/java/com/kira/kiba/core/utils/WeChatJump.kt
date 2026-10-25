package com.kira.kiba.core.utils

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * 微信跳转工具。
 *
 * 目前用于「我 → 登录机台」：国行舞萌 DX 的玩家二维码由微信公众号
 * **「舞萌 | 中二」**（华立官方账号）发放，机台扫码即登录。
 *
 * 跳转姿势（v1.1.4 重写，对齐「机台成绩更新」页实测可用的方案）：
 * - **Android 官方推荐**的打开其他应用方式是
 *   [PackageManager.getLaunchIntentForPackage]：由系统解析目标包的
 *   launcher 入口（不硬编码组件名），配合 NEW_TASK 启动；
 * - 不再用 `mp.weixin.qq.com` 深链「探测后拉起」：部分微信版本能解析该
 *   域名（startActivity 成功），但 profile_ext 页面在微信内渲染不出，
 *   表现为「点了没反应 / 打开了却是空白页」，是 v1.1.3 实测踩到的坑；
 * - 显式 `ComponentName(com.tencent.mm, com.tencent.mm.ui.LauncherUI)`
 *   作为兜底（版本无关的 launcher 入口）；
 * - 每级失败都给出用户可见反馈，不再静默吞异常。
 *
 * 兜底链：官方启动意图 → 显式 LauncherUI → 复制公众号名 + 提示手动搜索。
 */
object WeChatJump {

    private const val TAG = "WeChatJump"

    /** 公众号展示名（复制到剪贴板 / Toast 用）。 */
    const val OFFICIAL_ACCOUNT_NAME = "舞萌 | 中二"

    private const val WECHAT_PACKAGE = "com.tencent.mm"

    /** 微信主界面（Launcher 入口，任何版本都存在）。 */
    private val WECHAT_LAUNCHER = ComponentName(WECHAT_PACKAGE, "com.tencent.mm.ui.LauncherUI")

    /**
     * 打开微信（获取玩家二维码需在「舞萌 | 中二」公众号菜单操作）。
     *
     * @param context Activity Context（自动补 FLAG_ACTIVITY_NEW_TASK 以防传入 Application Context）。
     */
    fun openOfficialAccount(context: Context) {
        val newTask = if (context !is Activity) Intent.FLAG_ACTIVITY_NEW_TASK else 0

        // 1) 官方姿势：由系统解析微信的 launcher 入口并启动
        val launchIntent = context.packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
        if (launchIntent != null) {
            val ok = runCatching {
                context.startActivity(launchIntent.addFlags(newTask))
            }.onFailure { Log.w(TAG, "getLaunchIntentForPackage 启动失败", it) }.isSuccess
            if (ok) {
                notifyLaunched(context)
                return
            }
        } else {
            Log.w(TAG, "getLaunchIntentForPackage 返回 null（微信未安装或不可见）")
        }

        // 2) 兜底：显式拉起微信主界面（与「机台成绩更新」页同款、实测可用）
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = WECHAT_LAUNCHER
            addFlags(newTask)
        }
        val okExplicit = runCatching {
            context.startActivity(homeIntent)
        }.onFailure { Log.w(TAG, "显式 LauncherUI 启动失败", it) }.isSuccess
        if (okExplicit) {
            notifyLaunched(context)
            return
        }

        // 3) 微信未安装 / 无法启动：复制公众号名，提示手动处理
        copyAccountName(context)
        Toast.makeText(
            context,
            "无法打开微信，已复制公众号名「$OFFICIAL_ACCOUNT_NAME」。" +
                "安装微信后搜索该公众号，点「发消息」获取登录二维码",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** 启动成功后的统一引导：复制公众号名 + Toast 提示下一步操作。 */
    private fun notifyLaunched(context: Context) {
        copyAccountName(context)
        Toast.makeText(
            context,
            "已打开微信并复制公众号名「$OFFICIAL_ACCOUNT_NAME」，" +
                "请在微信顶部搜索并进入该公众号，点「发消息」获取登录二维码",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** 复制公众号名称到剪贴板（兜底路径与设置页「复制」共用）。 */
    fun copyAccountName(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("微信公众号", OFFICIAL_ACCOUNT_NAME))
    }
}
