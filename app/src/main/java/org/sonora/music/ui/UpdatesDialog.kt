package org.sonora.music.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.sonora.music.BuildConfig
import org.sonora.music.update.InsufficientUpdateStorage
import org.sonora.music.update.InvalidUpdateApk
import org.sonora.music.update.SonoraUpdates
import org.sonora.music.update.SonoraRelease
import java.io.File

@Composable
internal fun UpdatesDialog(updates: SonoraUpdates, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val result by updates.checkResult.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val downloadProgress = remember { MutableStateFlow<Int?>(null) }
    val progress by downloadProgress.collectAsStateWithLifecycle()
    var downloaded by remember { mutableStateOf<Pair<SonoraRelease, File>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Updates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Installed version ${BuildConfig.VERSION_NAME}")
                val release = result?.release
                val apk = downloaded?.takeIf { it.first == release }?.second
                if (release == null && downloadJob == null && result != null) Text("Sonora is up to date.")
                if (release != null) {
                    Text("Version ${release.version} available", style = MaterialTheme.typography.titleMedium)
                    Text(release.notes.ifBlank { "Release notes are unavailable." })
                }
                when {
                    downloadJob != null -> {
                        val percent = progress
                        if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else {
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                            Text(if (percent == 100) "Verifying APK…" else "Downloading… $percent%")
                        }
                        TextButton(onClick = {
                            downloadJob?.cancel()
                            message = "Download cancelled."
                        }) { Text("Cancel download") }
                    }
                    release != null && !updates.officialInstallation ->
                        Text("In-app installation requires an existing Sonora APK signed with the official release key.")
                    release != null && apk != null -> TextButton(onClick = {
                        message = null
                        try {
                            if (!apk.isFile) {
                                downloaded = null
                                message = "The downloaded APK is no longer available. Download it again."
                            } else if (!context.packageManager.canRequestPackageInstalls()) {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")))
                                message = "Allow installs from Sonora in Android settings, then return and tap Install update."
                            } else {
                                context.startActivity(updates.installerIntent(apk))
                            }
                        } catch (_: Exception) {
                            message = "Android could not open the installer or installation settings."
                        }
                    }) { Text("Install update") }
                    release != null -> TextButton(
                        enabled = checkJob == null,
                        onClick = {
                            message = null
                            downloadProgress.value = null
                            downloadJob = scope.launch(start = CoroutineStart.LAZY) {
                                try {
                                    downloaded = release to updates.download(release) { downloadProgress.value = it }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: InsufficientUpdateStorage) {
                                    message = "Free up storage and try downloading again."
                                } catch (_: InvalidUpdateApk) {
                                    message = "The APK could not be verified. Try again later."
                                } catch (_: Exception) {
                                    message = "The download was interrupted. Check your connection and try again."
                                } finally {
                                    downloadJob = null
                                }
                            }
                            downloadJob?.start()
                        },
                    ) { Text("Download update") }
                }
                message?.let { Text(it) }
                TextButton(enabled = checkJob == null && downloadJob == null, onClick = {
                    message = null
                    checkJob = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            updates.check()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            message = "Could not check for updates. Check your connection and try again."
                        } finally {
                            checkJob = null
                        }
                    }
                    checkJob?.start()
                }) { Text(if (checkJob == null) "Check for updates" else "Checking…") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
