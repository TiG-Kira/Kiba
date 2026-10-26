package com.kira.kiba.ui.material

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.kira.kiba.MainActivity

/**
 * 重启 MainActivity。
 *
 * 真·高级材质的开关 / 风格切换会改变全局 CompositionLocal，已经组合好的 UI 树
 * 不重建就拿不到新值，因此这里统一用重启 Activity 的方式落地：代价最小、状态最干净。
 */
fun Context.restartMainActivity() {
    val activity = findActivity()
    if (activity != null) {
        runCatching { activity.recreate() }.onFailure {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            activity.finish()
        }
    } else {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 从 Context 链向上找到宿主 Activity。 */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
