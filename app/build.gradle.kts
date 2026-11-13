import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}
val signingProperties = Properties().apply {
    val local = rootProject.file(".signing/signing.properties")
    if (local.isFile) local.inputStream().use { load(it) }
}
fun signingValue(name: String, environment: String): String? = System.getenv(environment)?.takeIf { it.isNotBlank() } ?: signingProperties.getProperty(name)
val releaseKeyStore = signingValue("storeFile", "SONORA_KEYSTORE_PATH")
android {
    namespace = "org.sonora.music"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.sonora.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.8.0-beta01"
        val releaseCertificate = rootProject.file("signing/certificate.sha256").readText().trim()
        require(releaseCertificate.matches(Regex("[0-9a-f]{64}")))
        buildConfigField("String", "OFFICIAL_CERTIFICATE_SHA256", "\"$releaseCertificate\"")
        // Public client configuration only. Never add an OAuth client secret here.
        val spotifyClient = providers.gradleProperty("sonora.spotifyClientId").orElse(providers.environmentVariable("SONORA_SPOTIFY_CLIENT_ID")).getOrElse("")
        require(spotifyClient.isEmpty() || spotifyClient.matches(Regex("[A-Za-z0-9]{20,100}")))
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClient\"")
        val googleClient = providers.gradleProperty("sonora.googleClientId").orElse(providers.environmentVariable("SONORA_GOOGLE_CLIENT_ID")).getOrElse("")
        val googleHost = providers.gradleProperty("sonora.googleOAuthHost").orElse(providers.environmentVariable("SONORA_GOOGLE_OAUTH_HOST")).getOrElse("")
        require(googleClient.isEmpty() || googleClient.matches(Regex("[A-Za-z0-9-]+\\.apps\\.googleusercontent\\.com")))
        require(googleHost.isEmpty() || googleHost.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*\\.[a-z]{2,}")))
        require(googleClient.isEmpty() == googleHost.isEmpty()) { "Configure both Google public client ID and HTTPS broker host." }
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClient\"")
        buildConfigField("String", "GOOGLE_REDIRECT_URI", "\"${if (googleHost.isEmpty()) "" else "https://$googleHost/oauth/callback"}\"")
        buildConfigField("String", "GOOGLE_TOKEN_ENDPOINT", "\"${if (googleHost.isEmpty()) "" else "https://$googleHost/oauth/exchange"}\"")
        val googleImportApproved = providers.gradleProperty("sonora.googleImportApproved").orElse(providers.environmentVariable("SONORA_GOOGLE_IMPORT_APPROVED")).getOrElse("false").toBoolean()
        buildConfigField("boolean", "YOUTUBE_OAUTH_ENABLED", (googleClient.isNotEmpty() && googleImportApproved).toString())
        manifestPlaceholders["googleOAuthHost"] = googleHost.ifEmpty { "oauth-disabled.invalid" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The in-app picker must work offline even if a distributor builds an App Bundle.
    bundle { language { enableSplit = false } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    testBuildType = providers.gradleProperty("sonora.testBuildType").getOrElse("debug")
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
    signingConfigs {
        if (releaseKeyStore != null) create("persistentRelease") {
            storeFile = rootProject.file(releaseKeyStore)
            storePassword = signingValue("storePassword", "SONORA_STORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "SONORA_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "SONORA_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            if (releaseKeyStore != null) signingConfig = signingConfigs.getByName("persistentRelease")
            // Keep the beta executable identical for release instrumentation and diagnosis.
            // Signing and debuggability are independent of code shrinking.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.newpipe)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.test.host)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.ktx)
}
tasks.withType<Test>().configureEach {
    systemProperty("sonora.liveImport", System.getenv("SONORA_LIVE_IMPORT") ?: "false")
    systemProperty("sonora.liveTests", System.getenv("SONORA_LIVE_TESTS") ?: "false")
    testLogging { events("passed", "skipped", "failed") }
}
