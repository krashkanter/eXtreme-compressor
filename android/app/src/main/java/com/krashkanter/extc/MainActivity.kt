package com.krashkanter.extc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var model: CompressorModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[CompressorModel::class.java]
        enableEdgeToEdge()
        setContent {
            val preferences = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            val systemDark = isSystemInDarkTheme()
            var dark by remember { mutableStateOf(preferences.getBoolean("dark", systemDark)) }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val state by model.state.collectAsStateWithLifecycle()
            ExperimentalCompressorTheme(dark) {
                ExperimentalCompressorApp(state, model, dark) {
                    dark = !dark
                    preferences.edit { putBoolean("dark", dark) }
                }
            }
        }
        if(savedInstanceState == null) acceptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun acceptIntent(intent: Intent) {
        when(intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                if(uri != null) model.convertImage(uri)
            }
            Intent.ACTION_VIEW -> intent.data?.let(model::openImage)
        }
    }
}
