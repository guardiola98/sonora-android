package org.sonora.music.update

import org.sonora.music.i18n.tr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.sonora.music.BuildConfig
import org.sonora.music.SonoraApplication
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val RELEASE_ROOT = "https://raw.githubusercontent.com/guardiola98/sonora-android/apk-releases/"
private const val MAX_MANIFEST_BYTES = 64L * 1024
private const val MAX_APK_BYTES = 100L * 1024 * 1024
private const val STORAGE_RESERVE_BYTES = 8L * 1024 * 1024

data class SonoraRelease(
    val version: String,
    val versionCode: Int,
    val sha256: String,
    val notes: String,
    val sizeBytes: Long?,
) {
    internal val apkUrl get() = "${RELEASE_ROOT}releases/$version/sonora-$version.apk"

    companion object {
        internal fun parse(json: String, installedCode: Int): SonoraRelease? {
            val manifest = JSONObject(json)
            val code = manifest.getInt("versionCode")
            require(code > 0)
            if (code <= installedCode) return null

            val version = manifest.getString("version")
            require(version.matches(Regex("[0-9][0-9A-Za-z.-]{0,63}")))
            require(manifest.getString("apk") == "releases/$version/sonora-$version.apk")
            val digest = manifest.getString("sha256").lowercase()
            require(digest.matches(Regex("[0-9a-f]{64}")))
            val size = if (manifest.has("sizeBytes")) manifest.getLong("sizeBytes") else null
            require(size == null || size in 1..MAX_APK_BYTES)
            return SonoraRelease(version, code, digest, manifest.optString("notes").take(8000), size)
        }
    }
}

internal fun matchesRelease(
    release: SonoraRelease,
    installedCode: Long,
    expectedPackage: String,
    packageName: String,
    versionCode: Long,
    versionName: String?,
    signers: List<String>,
): Boolean = packageName == expectedPackage && versionCode > installedCode &&
    versionCode == release.versionCode.toLong() && versionName == release.version &&
    signers == listOf(BuildConfig.OFFICIAL_CERTIFICATE_SHA256)

data class UpdateCheck(val release: SonoraRelease?)

class InsufficientUpdateStorage : IOException()
class InvalidUpdateApk : IOException()

class SonoraUpdates(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val checkLock = Mutex()
    private val downloadLock = Mutex()
    private val directory get() = File(context.cacheDir, "sonora-updates")
    private val mutableCheckResult = MutableStateFlow(
        runCatching { preferences.getString("manifest", null)?.let { UpdateCheck(parse(it)) } }.getOrNull()
    )
    val checkResult = mutableCheckResult.asStateFlow()
    val officialInstallation by lazy {
        runCatching {
            @Suppress("DEPRECATION")
            val installed = context.packageManager.getPackageInfo(context.packageName, signatureFlags)
            installed.signers() == listOf(BuildConfig.OFFICIAL_CERTIFICATE_SHA256)
        }.getOrDefault(false)
    }

    private fun parse(json: String) = SonoraRelease.parse(json, BuildConfig.VERSION_CODE)

    suspend fun check() = checkLock.withLock {
        val json = request(RELEASE_ROOT + "latest.json", 30) { _, response ->
            val source = response.body!!.source()
            if (source.request(MAX_MANIFEST_BYTES + 1)) throw IOException(tr("Manifest too large"))
            source.readUtf8()
        }
        val parsed = parse(json)
        preferences.edit().putString("manifest", json).apply()
        mutableCheckResult.value = UpdateCheck(parsed)
    }

    suspend fun download(release: SonoraRelease, onProgress: (Int?) -> Unit): File = downloadLock.withLock {
        check(officialInstallation)
        request(release.apkUrl, 15 * 60L) { call, response ->
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException(tr("Cannot create update directory"))
            val stale = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            directory.listFiles()?.filter { it.name.startsWith("transfer-") && it.lastModified() < stale }
                ?.forEach { it.deleteRecursively() }
            val apk = File(directory, "sonora-${release.version}-${release.sha256}.apk")
            val body = response.body!!
            val responseSize = body.contentLength().takeIf { it >= 0 }
            val expected = release.sizeBytes ?: responseSize
            if (expected != null && expected !in 1..MAX_APK_BYTES) throw InvalidUpdateApk()
            if (release.sizeBytes != null && responseSize != null && responseSize != expected) throw InvalidUpdateApk()
            if (directory.usableSpace < (expected ?: 0) + STORAGE_RESERVE_BYTES) throw InsufficientUpdateStorage()
            val transfer = Files.createTempDirectory(directory.toPath(), "transfer-").toFile()
            val partial = File(transfer, "sonora.apk")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastProgress: Int? = null
                body.byteStream().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (call.isCanceled()) throw IOException(tr("Download cancelled"))
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > (expected ?: MAX_APK_BYTES)) throw InvalidUpdateApk()
                            if (directory.usableSpace < STORAGE_RESERVE_BYTES + count) throw InsufficientUpdateStorage()
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val progress = expected?.let { (received * 100 / it).toInt() }
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
                if (expected != null && received != expected) throw IOException(tr("Incomplete APK"))
                if (digest.digest().hex() != release.sha256 || !validArchive(partial, release)) throw InvalidUpdateApk()
                if (call.isCanceled()) throw IOException(tr("Download cancelled"))
                if (!partial.renameTo(apk)) throw IOException(tr("Cannot save verified APK"))
                directory.listFiles()?.filter { it != apk && it.name.endsWith(".apk") && it.lastModified() < stale }
                    ?.forEach { it.delete() }
                apk
            } finally {
                transfer.deleteRecursively()
            }
        }
    }

    fun installerIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun validArchive(apk: File, release: SonoraRelease): Boolean {
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, signatureFlags) ?: return false
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        return matchesRelease(release, BuildConfig.VERSION_CODE.toLong(), context.packageName,
            archive.packageName, code, archive.versionName, archive.signers())
    }

    @Suppress("DEPRECATION")
    private val signatureFlags get() = if (Build.VERSION.SDK_INT >= 28)
        PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): List<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
        return certificates.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
        }
    }

    private suspend fun <T> request(url: String, timeoutSeconds: Long, read: (Call, Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(Request.Builder().url(url).build())
            call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful || it.body == null) throw IOException(tr("Update server HTTP %1\$s", it.code))
                            read(call, it)
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

@UnstableApi
class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val updates = (applicationContext as SonoraApplication).graph.updates
        return try {
            updates.check()
            Result.success()
        } catch (_: IOException) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (_: org.json.JSONException) {
            Result.failure()
        } catch (_: IllegalArgumentException) {
            Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sonora-update-check", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
