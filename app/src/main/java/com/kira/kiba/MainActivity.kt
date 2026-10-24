package com.kira.kiba

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kira.kiba.ui.navigation.KibaMainScreen
import com.kira.kiba.ui.theme.KibaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KibaTheme {
                KibaMainScreen()
            }
        }
    }
}
