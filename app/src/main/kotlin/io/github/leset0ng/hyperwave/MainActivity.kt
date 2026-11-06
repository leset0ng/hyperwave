package io.github.leset0ng.hyperwave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.leset0ng.hyperwave.settings.SettingsScreen

/** Compose host for the module settings page. The Xposed hooks live in [module.HyperWaveModule]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SettingsScreen() }
    }
}
