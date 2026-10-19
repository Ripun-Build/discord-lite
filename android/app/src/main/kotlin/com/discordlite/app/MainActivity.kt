package com.discordlite.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.discordlite.app.navigation.AppNavHost
import com.discordlite.designsystem.theme.DlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DlTheme {
                AppNavHost()
            }
        }
    }
}
