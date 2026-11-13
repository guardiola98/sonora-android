package org.sonora.music.i18n

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.sonora.music.discovery.CatalogKind
import org.sonora.music.domain.MusicSourceException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LanguageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun language(tag: String): Context = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
    private fun select(tag: String) {
        app.getSharedPreferences("language", 0).edit().putString("tag", tag).commit()
        AppLanguage.install(app)
    }
    @After fun reset() { select("") }
    @Test fun spanishAndCatalanResourcesRenderControlsFormatsApostrophesAndUserContent() {
        assertEquals("Biblioteca", localizedString(language("es"), "Library"))
        assertEquals("Reprodueix", localizedString(language("ca"), "Play"))
        assertEquals("Descargando… 37%", localizedString(language("es"), "Downloading… %1\$s%%", 37))
        assertEquals("Afig Cançó d’estiu als preferits", localizedString(language("ca"), "Favorite %1\$s", "Cançó d’estiu"))
        assertEquals("App's title", localizedString(language("es"), "App's title"))
        assertTrue(localizedString(language("ca"), "This playlist is no longer available to this account.").contains("llista"))
    }
    @Test fun everyResourceResolvesWithoutBrokenFormattingInBothTranslations() {
        for (tag in listOf("en", "es", "ca")) {
            val context = language(tag)
            for ((source, id) in stringIds) {
                val count = Regex("%([0-9]+)\\\$s").findAll(source).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
                val text = if (count == 0) context.getString(id) else context.getString(id, *Array<Any>(count) { "Test ${it + 1}" })
                assertTrue("$tag / $source", text.isNotBlank())
                assertFalse("$tag / $source", text.contains("%1\$s"))
                if (tag == "en" && count == 0) assertEquals(source, text)
            }
        }
    }
    @Test fun legacySelectionPersistsWrapsContextsAndDoesNotFreezeEnumLabels() {
        select("es")
        assertEquals("es", AppLanguage.selected(app))
        assertEquals("es", AppLanguage.wrap(app).resources.configuration.locales[0].language)
        assertEquals("Canciones", CatalogKind.SONGS.label)
        assertTrue(MusicSourceException.Kind.NETWORK.message.startsWith("No"))
        select("ca")
        assertEquals("ca", AppLanguage.locale.language)
        assertEquals("Cançons", CatalogKind.SONGS.label)
        assertEquals("Reprodueix", tr("Play"))
        assertEquals("Cançó d’estiu", tr("Cançó d’estiu"))
        select("en")
        assertEquals("Songs", CatalogKind.SONGS.label)
        assertEquals("Play", tr("Play"))
    }
}
