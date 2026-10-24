package com.kira.kiba

import android.app.Application
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.network.MaimaiDataClient
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.core.utils.SpUtil

class KibaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SpUtil.init(this)
        AppDataBase.init(this)
        MaimaiDataClient.instance.init()
        // 启动引导：恢复查分器登录态 + 静默同步曲库（内部全部降级，不阻塞启动）
        KibaState.bootstrap(this)
    }
}
