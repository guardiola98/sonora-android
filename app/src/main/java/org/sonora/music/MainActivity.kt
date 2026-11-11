package org.sonora.music

import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import org.sonora.music.ui.MusicViewModel
import org.sonora.music.ui.SonoraApp
import org.sonora.music.ui.SonoraTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = importViewModel()
        intent?.data?.toString()?.let(model.googleImportAuthorization::onRedirect)
        intent?.data = null
        setContent {
            SonoraTheme {
                val vm: MusicViewModel = viewModel(factory = MusicViewModel.Factory(application as SonoraApplication))
                SonoraApp(vm)
            }
        }
    }
    private fun importViewModel() = ViewModelProvider(this, MusicViewModel.Factory(application as SonoraApplication))[MusicViewModel::class.java]
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let(importViewModel().googleImportAuthorization::onRedirect)
        intent.data = null
    }
}
