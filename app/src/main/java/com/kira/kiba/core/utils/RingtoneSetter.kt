package com.kira.kiba.core.utils

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri

/**
 * 系统铃声设置（播放页「设为铃声」入口）。
 *
 * 权限口径：修改来电铃声需要「修改系统设置」特殊权限（WRITE_SETTINGS）——
 * 不是普通运行时权限，`requestPermissions` 拿不到，只能引导用户去
 * 系统设置页手动开开关（[openWriteSettingsPage]）。所以 UI 流程必然是两段式：
 * 第一次点击跳设置页，用户授权回来再点一次才真正生效。
 *
 * 音源口径：铃声必须是本地文件。传入已下载歌曲的 MediaStore uri 即可；
 * 流媒体播放中的歌要先走 [com.kira.kiba.core.listen.SongDownloader.download]
 * 落盘（UI 层负责），再拿返回的 uri 调 [setRingtone]。
 */
object RingtoneSetter {

    /** 是否已持有「修改系统设置」授权（Android 6+ 的特殊权限判定）。 */
    fun canWriteSettings(context: Context): Boolean =
        Settings.System.canWrite(context)

    /** 跳系统「修改系统设置」页（已预填本应用，用户点一下开关即可）。 */
    fun openWriteSettingsPage(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * 把一个本地 MediaStore 音频 uri 设为来电铃声。
     *
     * Android 10+ 要求铃声文件在 MediaStore 里带 IS_RINGTONE 标记（部分机型
     * 没有它也能设成功，但有它才稳）；这里是补标记，失败不阻断主流程。
     *
     * @return 成功 true；[RingtoneManager.setActualDefaultRingtoneUri] 抛异常时
     *   原样向上抛（UI 层 runCatching 转 toast）。
     */
    fun setRingtone(context: Context, audioUri: Uri): Boolean {
        runCatching {
            context.contentResolver.update(
                audioUri,
                android.content.ContentValues().apply { put("is_ringtone", 1) },
                null, null,
            )
        }
        RingtoneManager.setActualDefaultRingtoneUri(
            context,
            RingtoneManager.TYPE_RINGTONE,
            audioUri,
        )
        return true
    }
}
