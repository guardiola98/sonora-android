package org.sonora.music

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.discovery.DiscoverySettings
import org.sonora.music.domain.Track
import org.sonora.music.playback.PlayerSnapshot
import org.sonora.music.ui.DiscoveryActions
import org.sonora.music.ui.FullPlayer
import org.sonora.music.ui.LocalAddToPlaylist
import org.sonora.music.ui.LocalDiscoveryActions
import org.sonora.music.ui.SonoraTheme
import org.junit.Assert.assertFalse

@RunWith(AndroidJUnit4::class)
class NowPlayingAutoplayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun togglesAndPersistsAutoplayOnCompactPlayer() {
        val prefs = compose.activity.getSharedPreferences("autoplay-control-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = DiscoverySettings(prefs)
        var autoplay by mutableStateOf(settings.state.value.autoplay)
        val track = Track("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "Midnight City", "M83", "", 244)
        compose.setContent {
            SonoraTheme {
                Surface {
                    Box(Modifier.size(width = 320.dp, height = 570.dp)) {
                        CompositionLocalProvider(
                            LocalAddToPlaylist provides {},
                            LocalDiscoveryActions provides DiscoveryActions(autoplay = autoplay, setAutoplay = {
                                settings.update(settings.state.value.copy(autoplay = it))
                                autoplay = it
                            }),
                        ) {
                            FullPlayer(
                                player = PlayerSnapshot(queue = listOf(track), index = 0, positionMs = 62_000, durationMs = 244_000),
                                error = null, favorite = false, onClose = {}, onFavorite = {}, onToggle = {},
                                onNext = {}, onPrevious = {}, onSeek = {}, onSpeed = {}, onQueue = {}, onRetry = {},
                            )
                        }
                    }
                }
            }
        }

        val control = compose.onNodeWithContentDescription("Autoplay")
        control.assertIsDisplayed().assertIsOn().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        screenshot("now-playing-autoplay-on.png")
        control.performClick().assertIsOff()
        compose.runOnIdle { assertFalse(DiscoverySettings(prefs).state.value.autoplay) }
        screenshot("now-playing-autoplay-off.png")
        compose.onNodeWithContentDescription("Play").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Queue · 1 of 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Related music continues after this track").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        compose.activity.openFileOutput(name, Context.MODE_PRIVATE).use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
