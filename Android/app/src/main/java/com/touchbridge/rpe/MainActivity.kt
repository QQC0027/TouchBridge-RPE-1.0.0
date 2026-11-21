package com.touchbridge.rpe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.touchbridge.rpe.ui.AppNavHost
import com.touchbridge.rpe.ui.theme.TouchBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TouchBridgeTheme {
                AppNavHost()
            }
        }
    }
}
