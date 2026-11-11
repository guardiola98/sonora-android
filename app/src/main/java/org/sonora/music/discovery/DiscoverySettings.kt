package org.sonora.music.discovery

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveryOptions(val autoplay: Boolean = true, val personalized: Boolean = true, val enrichment: Boolean = false,
    val onlineSuggestions: Boolean = true, val interests: List<String> = emptyList(), val resetAt: Long = 0)
class DiscoverySettings(private val preferences: SharedPreferences) {
    private val mutable = MutableStateFlow(DiscoveryOptions(preferences.getBoolean("autoplay", true), preferences.getBoolean("personalized", true),
        preferences.getBoolean("enrichment", false), preferences.getBoolean("suggestions", true),
        preferences.getString("interests", "").orEmpty().split('\n').filter { it.isNotBlank() }.take(5), preferences.getLong("resetAt", 0)))
    val state = mutable.asStateFlow()
    fun update(value: DiscoveryOptions) {
        val safe = value.copy(interests = value.interests.map { it.trim().take(100) }.filter { it.isNotBlank() }.distinct().take(5))
        mutable.value = safe
        preferences.edit { putBoolean("autoplay", safe.autoplay); putBoolean("personalized", safe.personalized); putBoolean("enrichment", safe.enrichment)
            putBoolean("suggestions", safe.onlineSuggestions); putString("interests", safe.interests.joinToString("\n")); putLong("resetAt", safe.resetAt) }
    }
}
