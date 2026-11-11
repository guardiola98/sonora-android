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
        versionCode = 3
        versionName = "0.2.0-beta01"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
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
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.ktx)
}
tasks.withType<Test>().configureEach {
    systemProperty("sonora.liveImport", System.getenv("SONORA_LIVE_IMPORT") ?: "false")
    systemProperty("sonora.liveTests", System.getenv("SONORA_LIVE_TESTS") ?: "false")
    testLogging { events("passed", "skipped", "failed") }
}
