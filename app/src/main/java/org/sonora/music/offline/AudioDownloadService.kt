package org.sonora.music.offline

import org.sonora.music.i18n.tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import org.sonora.music.MainActivity
import org.sonora.music.SonoraApplication

@UnstableApi
class AudioDownloadService : DownloadService(7201, 1_000L) {
    private val downloads get() = (application as SonoraApplication).graph.downloads
    override fun onCreate() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("audio-downloads", tr("Audio downloads"), NotificationManager.IMPORTANCE_LOW))
        super.onCreate()
    }
    override fun getDownloadManager() = downloads.manager
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING }
        val known = active.map { it.percentDownloaded }.filter { it >= 0 }
        val progress = if (known.isEmpty()) 0 else known.average().toInt()
        val open = PendingIntent.getActivity(this, 7201, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 7202, buildSetStopReasonIntent(this, AudioDownloadService::class.java, null, 1, false), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "audio-downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("Sonora downloads"))
            .setContentText(if (notMetRequirements != 0) tr("Waiting for an allowed network") else tr("%1\$s downloading · Library → Downloads", active.size))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress, known.isEmpty()).addAction(0, tr("Pause all"), pause).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits dataSync foreground time. Persist the pause and stop promptly.
        downloads.manager.setStopReason(null, 2)
        downloads.manager.pauseDownloads()
        stopSelf()
    }
}
