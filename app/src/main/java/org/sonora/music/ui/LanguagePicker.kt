package org.sonora.music.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.sonora.music.i18n.AppLanguage
import org.sonora.music.i18n.tr

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
    else -> null
}
@Composable internal fun LanguagePicker() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(tr("App language")) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(tr("App language")) }, text = {
        Column {
            val selected = AppLanguage.selected(context).substringBefore(',').substringBefore('-')
            listOf("" to tr("System language"), "en" to "English", "es" to "Español", "ca" to "Català").forEach { (tag, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                    open = false; context.activity()?.let { AppLanguage.select(it, tag) }
                }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == tag, onClick = null)
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(tr("Close")) } })
}
