package com.kira.kiba.core.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
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
 * 技术方案（微信不提供直接打开指定公众号对话页的公开 API，profile_ext
 * 是社区公认的公众号主页跳转链接，微信客户端会识别并渲染为公众号
 * 资料页，用户再点「发消息」即进入对话）：
 * - `__biz` 是公众号的唯一标识（Base64），取自该号已发布文章的
 *   `mp.weixin.qq.com/s?__biz=...` 链接（已验证发布账号名为「舞萌丨中二」）；
 * - `scene=110` 为关注场景值，`#wechat_redirect` 是微信识别该链接的关键标记；
 * - 拉起时显式 `setPackage("com.tencent.mm")`，避免弹系统应用选择器。
 *
 * 兜底链：微信直接拉起 → 系统选择器（浏览器打开会引导去微信）→
 * 复制公众号名 + Toast 提示手动搜索。
 */
object WeChatJump {

    /** 微信公众号「舞萌 | 中二」的 biz 标识（文章链接 __biz 参数，已核对发布账号）。 */
    private const val OFFICIAL_ACCOUNT_BIZ = "Mzg2OTI2Njc0Mg=="

    /** 公众号展示名（复制到剪贴板 / Toast 用）。 */
    const val OFFICIAL_ACCOUNT_NAME = "舞萌 | 中二"

    private val WECHAT_PACKAGE = "com.tencent.mm"

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
        val uri = Uri.parse(officialAccountUrl)

        // 1) 显式指定微信：无选择器，一步到公众号资料页
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(WECHAT_PACKAGE)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onSuccess { return }

        // 2) 微信未安装或该版本不认此链接：交系统选择器（浏览器会引导去微信打开）
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onSuccess { return }

        // 3) 兜底：复制公众号名，提示用户手动在微信搜索关注
        copyAccountName(context)
        Toast.makeText(
            context,
            "未找到可打开微信的应用，已复制公众号名「$OFFICIAL_ACCOUNT_NAME」，请在微信搜索关注",
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
