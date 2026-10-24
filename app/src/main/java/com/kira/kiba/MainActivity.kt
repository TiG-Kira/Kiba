package com.kira.kiba

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.OnBackInvokedDefaultInput
import androidx.navigationevent.setViewTreeNavigationEventDispatcherOwner
import com.kira.kiba.ui.navigation.KibaMainScreen
import com.kira.kiba.ui.theme.KibaTheme

/**
 * Kiba 主 Activity。
 *
 * navigationevent 装配（miuix OverlayDialog / 弹层的返回手势守门依赖）：
 * - 实现 [NavigationEventDispatcherOwner] 持有根派发器，并挂到 decorView 的 ViewTree 标签上，
 *   Compose 侧 `LocalNavigationEventDispatcherOwner` 向上查找即可获得；
 *   不装配时弹出任何 miuix 对话框都会抛
 *   `IllegalStateException: No NavigationEventDispatcher was provided`；
 * - 系统返回经 [OnBackInvokedDefaultInput] 喂给根派发器：有对话框时返回键关对话框，
 *   无对话框时走默认（结束 Activity）。
 */
class MainActivity : ComponentActivity(), NavigationEventDispatcherOwner {

    private val navEventDispatcher = NavigationEventDispatcher()

    override val navigationEventDispatcher: NavigationEventDispatcher
        get() = navEventDispatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setViewTreeNavigationEventDispatcherOwner(this)
        navEventDispatcher.addInput(OnBackInvokedDefaultInput(onBackInvokedDispatcher))
        setContent {
            KibaTheme {
                KibaMainScreen()
            }
        }
    }

    override fun onDestroy() {
        navEventDispatcher.dispose()
        super.onDestroy()
    }
}
