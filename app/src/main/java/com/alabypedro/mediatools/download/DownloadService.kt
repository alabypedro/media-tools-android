package com.alabypedro.mediatools.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.alabypedro.mediatools.R
import com.alabypedro.mediatools.UmtApp
import com.alabypedro.mediatools.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Mantém o processo vivo (e a CPU acordada) enquanto houver downloads ativos. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, Notifications.PROGRESS_ID, Notifications.progress(this, emptyList()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "umt:downloads")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        }
        if (watcher == null) {
            val repo = (application as UmtApp).downloads
            watcher = scope.launch {
                repo.jobs.collectLatest { jobs ->
                    val active = jobs.filter { it.isActive }
                    if (active.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        Notifications.notify(this@DownloadService, Notifications.PROGRESS_ID, Notifications.progress(this@DownloadService, active))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}

object Notifications {
    const val PROGRESS_ID = 1
    private const val CH_PROGRESS = "progress"
    private const val CH_RESULT = "result"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Downloads em andamento", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "Downloads concluídos", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun progress(context: Context, active: List<DownloadJob>): Notification {
        val running = active.filter { it.status == JobStatus.RUNNING }
        val first = running.firstOrNull() ?: active.firstOrNull()
        val title = when {
            first == null -> "Preparando download"
            active.size == 1 -> first.title
            else -> "${active.size} downloads (${running.size} em andamento)"
        }
        val b = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(first?.let { listOfNotNull(it.stage, it.speed).joinToString(" · ") })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
        if (first != null && active.size == 1) {
            if (first.progress >= 0) b.setProgress(1000, (first.progress * 1000).toInt(), false)
            else b.setProgress(0, 0, true)
        }
        return b.build()
    }

    fun finished(context: Context, job: DownloadJob) {
        val file = job.files.singleOrNull()
        val intent = if (file != null) {
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.uri), file.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } else null
        val pending = intent?.let {
            PendingIntent.getActivity(context, job.id.hashCode(), Intent.createChooser(it, null),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } ?: openApp(context)
        val text = if (job.files.size > 1) "${job.files.size} arquivos em Download/${MediaStoreSaver.FOLDER}"
        else "Salvo em Download/${MediaStoreSaver.FOLDER}"
        notify(context, job.id.hashCode(), NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(job.title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build())
    }

    fun failed(context: Context, job: DownloadJob) {
        notify(context, job.id.hashCode(), NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Falhou: ${job.title}")
            .setContentText(job.error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(job.error))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build())
    }

    // Android 13+: a permissão é pedida na MainActivity; se negada, as notificações só não aparecem.
    @SuppressLint("MissingPermission")
    fun notify(context: Context, id: Int, notification: Notification) {
        val nm = NotificationManagerCompat.from(context)
        if (nm.areNotificationsEnabled()) runCatching { nm.notify(id, notification) }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
