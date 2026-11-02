package com.trailo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.trailo.app.ui.home.HomeRoute
import com.trailo.app.ui.theme.TrailoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrailoTheme {
                HomeRoute()
            }
        }
    }
}
