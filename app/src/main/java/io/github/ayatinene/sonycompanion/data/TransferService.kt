package io.github.ayatinene.sonycompanion.data

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ayatinene.sonycompanion.CompanionApplication
import io.github.ayatinene.sonycompanion.MainActivity
import io.github.ayatinene.sonycompanion.R
import kotlinx.coroutines.*

/** Owns the lifetime of active downloads, not camera discovery or the UI. */
class TransferService: Service() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("transfers","相机文件传输",NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AlphaCompanion:transfer").apply { acquire(6*60*60*1000L) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as CompanionApplication).controller
        if(intent?.action=="cancel") { controller.cancelTransfers(); stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this,11,notification("准备传输"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            controller.beginQueuedTransfers()
            ServiceCompat.stopForeground(this@TransferService,ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    fun notification(text: String): Notification {
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val cancel=PendingIntent.getService(this,2,Intent(this,TransferService::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"transfers").setSmallIcon(R.drawable.ic_camera).setContentTitle("Alpha Companion")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(0,"取消",cancel).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as CompanionApplication).controller.cancelTransfers(); stopSelf()
    }
    override fun onDestroy() {
        if((application as CompanionApplication).controller.state.value.transferring) (application as CompanionApplication).controller.cancelTransfers()
        scope.cancel(); if(wakeLock?.isHeld==true) wakeLock?.release(); super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
