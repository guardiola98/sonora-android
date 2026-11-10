package org.sonora.music

import android.os.Bundle
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
        setContent {
            SonoraTheme {
                val vm: MusicViewModel = viewModel(factory = MusicViewModel.Factory(application as SonoraApplication))
                SonoraApp(vm)
            }
        }
    }
}
