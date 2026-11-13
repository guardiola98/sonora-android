package org.sonora.music.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Uses Android locale resources, including the system's per-app setting on Android 13+. */
object AppLanguage {
    @Volatile private var application: Context? = null
    @Volatile private var legacyResources: Pair<String, Context>? = null
    fun install(context: Context) {
        application = context.applicationContext
        legacyResources = null
        // Preserve a choice made on Android 8–12 when the device upgrades to Android 13+.
        if (Build.VERSION.SDK_INT >= 33) {
            val preferences = context.getSharedPreferences("language", Context.MODE_PRIVATE)
            if (!preferences.getBoolean("system_migrated", false)) {
                val manager = context.getSystemService(LocaleManager::class.java)
                val previous = preferences.getString("tag", "").orEmpty()
                if (manager.applicationLocales.isEmpty && previous in setOf("en", "es", "ca"))
                    manager.applicationLocales = LocaleList.forLanguageTags(previous)
                preferences.edit().putBoolean("system_migrated", true).apply()
            }
        }
    }
    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33)
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        else context.getSharedPreferences("language", Context.MODE_PRIVATE).getString("tag", "").orEmpty()
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = selected(context)
        if (tag.isEmpty()) return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
    }
    fun select(activity: Activity, tag: String) {
        require(tag in setOf("", "en", "es", "ca"))
        legacyResources = null
        if (Build.VERSION.SDK_INT >= 33) activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        else {
            activity.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("tag", tag).apply()
            activity.recreate()
        }
    }
    val locale: Locale get() = resources()?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault()
    internal fun resources(): Context? {
        val context = application ?: return null
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = selected(context)
        if (tag.isEmpty()) return context
        return legacyResources?.takeIf { it.first == tag }?.second ?: wrap(context).also { legacyResources = tag to it }
    }
}

/** Explicit app-owned templates only. Track titles, artist names and user text are never translated. */
fun tr(source: String, vararg args: Any?): String = localizedString(AppLanguage.resources(), source, *args)
internal fun localizedString(context: Context?, source: String, vararg args: Any?): String {
    val id = stringIds[source]
    if (context != null && id != null) return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    // Pure domain tests do not create an Android application; English is the default resource language.
    return if (args.isEmpty()) source else String.format(Locale.ROOT, source, *args)
}
