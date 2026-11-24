package com.touchbridge.rpe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.touchbridge.rpe.ui.AppNavHost
import com.touchbridge.rpe.ui.theme.TouchBridgeTheme

class MainActivity : ComponentActivity() {

    private var externalDestination by mutableStateOf<String?>(null)
    private var captureError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExternalIntent(intent)
        enableEdgeToEdge()
        setContent {
            TouchBridgeTheme {
                AppNavHost(
                    externalDestination = externalDestination,
                    captureError = captureError,
                    onExternalConsumed = {
                        externalDestination = null
                        captureError = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExternalIntent(intent)
    }

    private fun readExternalIntent(intent: Intent?) {
        if (intent == null) return
        val destination = intent.getStringExtra(EXTRA_DESTINATION)
        if (destination == DESTINATION_PREVIEW || destination == DESTINATION_RECORD) {
            externalDestination = destination
        }
        captureError = intent.getStringExtra(EXTRA_CAPTURE_ERROR)
        // 消费后移除，避免 Activity 重建时重复导航。
        intent.removeExtra(EXTRA_DESTINATION)
        intent.removeExtra(EXTRA_CAPTURE_ERROR)
    }

    companion object {
        const val EXTRA_DESTINATION = "com.touchbridge.rpe.extra.DESTINATION"
        const val EXTRA_CAPTURE_ERROR = "com.touchbridge.rpe.extra.CAPTURE_ERROR"
        const val DESTINATION_PREVIEW = "preview"
        const val DESTINATION_RECORD = "record"
    }
}
