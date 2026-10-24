package com.kira.kiba

import android.app.Application
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.utils.SpUtil

class KibaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SpUtil.init(this)
        AppDataBase.init(this)
    }
}
