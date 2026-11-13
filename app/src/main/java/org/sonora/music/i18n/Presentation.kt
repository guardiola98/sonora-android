package org.sonora.music.i18n

/** Display labels only; stable version and evidence identifiers stay unchanged. */
fun versionLabel(code: String): String = when (code) {
    "live" -> tr("Live version")
    "cover" -> tr("Cover version")
    "remix" -> tr("Remix")
    "acoustic" -> tr("Acoustic version")
    "alternate" -> tr("Alternate version")
    else -> code
}
fun evidenceScope(code: String): String = when (code) {
    "artist" -> tr("Artist")
    "recording" -> tr("Recording")
    else -> code
}
