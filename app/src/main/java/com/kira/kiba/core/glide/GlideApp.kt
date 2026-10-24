package com.kira.kiba.core.glide

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager

/**
 * 极简 GlideApp 包装（仅运行时，不引 annotation processor）。
 * 分享图生成逻辑后续由用户重写，届时可直接替换为 Glide 生成的 API 形态。
 */
object GlideApp {
    fun with(context: Context): RequestManager = Glide.with(context)
}
