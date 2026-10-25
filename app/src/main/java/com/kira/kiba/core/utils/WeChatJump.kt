package com.kira.kiba.core.utils

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 微信跳转工具。
 *
 * 目前用于「我 → 登录机台」：国行舞萌 DX 的玩家二维码由微信公众号
 * **「舞萌 | 中二」**（华立官方账号）发放，机台扫码即登录。
 *
 * 技术现状（2026 实测/考证）：
 * - 微信 8.x **没有注册** `mp.weixin.qq.com` 的 http/https intent-filter，
 *   外部 `ACTION_VIEW` + `setPackage(com.tencent.mm)` 会抛 ActivityNotFoundException，
 *   这正是「拉不起微信」的根因；
 * - `weixin://` scheme 白名单（WXCustomSchemeEntryActivity）没有直达特定公众号的入口，
 *   且白名单外路径会被静默丢弃，不可依赖；
 * - 公众号 profile_ext 链接（`__biz` + `scene=110` + `#wechat_redirect`）只在
 *   **微信内**打开才渲染为公众号资料页；部分微信版本/ROM 会声明该域名的深链，
 *   用 `resolveActivity` 探测后拉起即直达资料页（最理想路径）；
 * - 显式 `ComponentName("com.tencent.mm", ".ui.LauncherUI")` 拉微信主界面是唯一
 *   版本无关的可靠路径（上游 MaimaiData 同款姿势），配合复制公众号名引导搜索。
 *
 * 兜底链：微信深链（探测通过才走）→ LauncherUI 主界面 + 复制公众号名 →
 * 系统启动意图 + 复制公众号名 → 复制公众号名 + 提示手动搜索。
 */
object WeChatJump {

    /** 微信公众号「舞萌 | 中二」的 biz 标识（文章链接 __biz 参数，已核对发布账号）。 */
    private const val OFFICIAL_ACCOUNT_BIZ = "Mzg2OTI2Njc0Mg=="

    /** 公众号展示名（复制到剪贴板 / Toast 用）。 */
    const val OFFICIAL_ACCOUNT_NAME = "舞萌 | 中二"

    private const val WECHAT_PACKAGE = "com.tencent.mm"

    /** 微信主界面（Launcher 入口，任何版本都存在）。 */
    private val WECHAT_LAUNCHER = ComponentName(WECHAT_PACKAGE, "com.tencent.mm.ui.LauncherUI")

    /** 公众号主页链接：微信内打开会渲染为该公众号的资料页。 */
    private val officialAccountUrl: String
        get() = "https://mp.weixin.qq.com/mp/profile_ext" +
            "?action=home&__biz=$OFFICIAL_ACCOUNT_BIZ&scene=110#wechat_redirect"

    /**
     * 打开微信「舞萌 | 中二」公众号页面（获取玩家二维码扫码登录机台）。
     *
     * @param context Activity Context（自动补 FLAG_ACTIVITY_NEW_TASK 以防传入 Application Context）。
     */
    fun openOfficialAccount(context: Context) {
        val pm = context.packageManager

        // 1) 微信深链：只有微信确实声明了 mp.weixin.qq.com 的 filter 才走（探测，不盲试）
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(officialAccountUrl)).apply {
            setPackage(WECHAT_PACKAGE)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (deepLink.resolveActivity(pm) != null) {
            runCatching { context.startActivity(deepLink) }.onSuccess { return }
        }

        // 2) 显式拉起微信主界面 + 复制公众号名引导搜索（版本无关，必成功路径）
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            component = WECHAT_LAUNCHER
            addCategory(Intent.CATEGORY_LAUNCHER)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (homeIntent.resolveActivity(pm) != null) {
            runCatching { context.startActivity(homeIntent) }.onSuccess {
                copyAccountName(context)
                Toast.makeText(
                    context,
                    "已打开微信并复制公众号名「$OFFICIAL_ACCOUNT_NAME」，" +
                        "请在微信顶部搜索并进入该公众号，点「发消息」获取登录二维码",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
        }

        // 3) LauncherUI 组件名变化等极端情况：退到系统启动意图
        val launchIntent = pm.getLaunchIntentForPackage(WECHAT_PACKAGE)
        if (launchIntent != null) {
            runCatching {
                if (context !is Activity) launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }.onSuccess {
                copyAccountName(context)
                Toast.makeText(
                    context,
                    "已打开微信并复制公众号名「$OFFICIAL_ACCOUNT_NAME」，" +
                        "请在微信顶部搜索并进入该公众号，点「发消息」获取登录二维码",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
        }

        // 4) 微信未安装：复制公众号名，提示手动处理
        copyAccountName(context)
        Toast.makeText(
            context,
            "未检测到微信，已复制公众号名「$OFFICIAL_ACCOUNT_NAME」，安装微信后搜索关注获取二维码",
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
