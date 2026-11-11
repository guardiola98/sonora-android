package org.sonora.music.update

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sonora.music.BuildConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SonoraReleaseTest {
    private val version = "0.6.0-beta01"
    private val digest = "a".repeat(64)
    private val path = "releases/$version/sonora-$version.apk"
    private val manifest = """
        {"version":"$version","versionCode":7,"sha256":"$digest","apk":"$path"}
    """.trimIndent()

    @Test fun readsLegacyManifestAndOnlyOffersHigherCodes() {
        val release = requireNotNull(SonoraRelease.parse(manifest, 6))
        assertEquals(version, release.version)
        assertEquals(7, release.versionCode)
        assertEquals(RELEASE_ROOT + path, release.apkUrl)
        assertEquals("", release.notes)
        assertNull(release.sizeBytes)
        assertNull(SonoraRelease.parse(manifest, 7))
        assertNull(SonoraRelease.parse(manifest, 8))
    }

    @Test fun readsNotesAndSize() {
        val enriched = manifest.dropLast(1) + ""","notes":"New player","sizeBytes":1024}"""
        val release = requireNotNull(SonoraRelease.parse(enriched, 6))
        assertEquals("New player", release.notes)
        assertEquals(1024L, release.sizeBytes)
    }

    @Test fun rejectsForeignApkPathsAndMalformedDigest() {
        assertThrows(IllegalArgumentException::class.java) {
            SonoraRelease.parse(manifest.replace(path, "https://example.org/sonora.apk"), 6)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SonoraRelease.parse(manifest.replace(digest, "bad"), 6)
        }
    }

    @Test fun checksActualPackageVersionAndSinglePinnedSigner() {
        val release = requireNotNull(SonoraRelease.parse(manifest, 6))
        val app = "org.sonora.music"
        val pinned = listOf(BuildConfig.OFFICIAL_CERTIFICATE_SHA256)
        fun accepts(packageName: String = app, code: Long = 7, name: String? = version, signers: List<String> = pinned) =
            matchesRelease(release, 6, app, packageName, code, name, signers)

        assertTrue(accepts())
        assertFalse(accepts(packageName = "org.other.music"))
        assertFalse(accepts(code = 6))
        assertFalse(accepts(code = 8))
        assertFalse(accepts(name = "other"))
        assertFalse(accepts(signers = listOf(digest)))
        assertFalse(accepts(signers = pinned + digest))
        assertFalse(accepts(signers = emptyList()))
    }
}
