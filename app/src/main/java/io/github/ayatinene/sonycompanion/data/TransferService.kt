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

/**
 * Holds foreground-service ownership and a bounded wake lock while camera background work is active.
 * Ownership is shared through [CompanionController.backgroundActiveFlow], so the service only stops
 * once no scan or download holds a lease. The controller waits for [CompanionController.onServiceReady]
 * before starting work, and is told about a rejection so it can invalidate the session instead of
 * scanning without foreground protection.
 */
class TransferService: Service() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var observing = false
    private var lastStartId = 0
    private var wakeJob: Job? = null
    private var wakeDeadline = 0L
    private val controller get() = (application as CompanionApplication).controller

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("transfers","相机文件传输",NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AlphaCompanion:transfer")
            .apply { setReferenceCounted(false) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when(intent?.action) {
            "cancel" -> {
                if(controller.state.value.oneTouchImport) controller.cancelOneTouchImport()
                else { controller.cancelBackgroundWork();stopSelfSafely() }
                return START_NOT_STICKY
            }
            "stop" -> { stopSelfSafely(); return START_NOT_STICKY }
        }
        if(!ensureForeground()) return START_NOT_STICKY
        if(!observing) { observing = true; scope.launch { observe() } }
        return START_NOT_STICKY
    }
    private fun ensureForeground(): Boolean {
        // Already foreground: still confirm the new readiness ticket, otherwise a start that arrived
        // during the 400ms stop window would time out even though the service is running.
        if(foreground) { controller.onServiceReady(); return true }
        try {
            ServiceCompat.startForeground(this,11,notification(controller.state.value),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            foreground = true
            controller.onServiceReady()
            return true
        } catch(e: Exception) {
            // A rejected foreground start must never let work continue as if background were safe.
            controller.onBackgroundServiceRejected(e.message)
            stopSelfSafely()
            return false
        }
    }
    private suspend fun observe() = coroutineScope {
        launch {
            controller.backgroundActiveFlow.collect { active ->
                if(active) { ensureForeground(); updateNotification() }
                else {
                    // Debounce a transient gap between a finishing scan and a starting download.
                    delay(400)
                    if(!controller.backgroundActiveFlow.value) { stopWakeLock(); stopSelfSafely() }
                }
            }
        }
        launch {
            controller.state.collect { state ->
                if(controller.backgroundActiveFlow.value) { updateNotification(state); maintainWakeLock(state) }
            }
        }
    }
    private fun updateNotification(state: AppState = controller.state.value) {
        if(foreground) getSystemService(NotificationManager::class.java).notify(11,notification(state))
    }
    /** Keep the CPU awake only while work can progress; a user pause keeps the notification and the
     * foreground service but releases the CPU lock. The lock is re-armed periodically so a long scan
     * never silently loses it, capped at a finite total to respect Android's background limits. */
    private fun maintainWakeLock(state: AppState) {
        val needCpu = state.oneTouchImport || state.transferring || (state.scan.phase==ScanPhase.Scanning && !state.scan.paused)
        if(needCpu) startWakeLock() else stopWakeLock()
    }
    private fun startWakeLock() {
        if(wakeJob?.isActive==true) return
        // The total budget is service-scoped: pausing/resuming the wake job must not reset the 6h cap.
        if(wakeDeadline==0L) wakeDeadline = SystemClock.elapsedRealtime() + WAKE_LOCK_TOTAL_MILLIS
        wakeJob = scope.launch {
            while(isActive) {
                val lock = wakeLock ?: return@launch
                if(SystemClock.elapsedRealtime() >= wakeDeadline) {
                    // The finite budget is exhausted: never let work keep running without a wake lock.
                    controller.cancelBackgroundWork()
                    stopSelfSafely()
                    return@launch
                }
                // Reference counting is disabled, so each acquire renews the timeout with no gap.
                lock.acquire(WAKE_LOCK_SLICE_MILLIS)
                delay(WAKE_LOCK_SLICE_MILLIS - WAKE_LOCK_RENEW_MARGIN_MILLIS)
            }
        }
    }
    private fun stopWakeLock() { wakeJob?.cancel(); wakeJob=null; wakeLock?.let { if(it.isHeld) it.release() } }
    private fun stopSelfSafely() {
        // A newer start (or still-active lease) owns the service: never stop it out from under it.
        if(controller.backgroundActiveFlow.value) return
        stopWakeLock()
        if(foreground) { ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE); foreground=false }
        stopSelf(lastStartId)
    }
    fun notification(state: AppState): Notification {
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val cancel=PendingIntent.getService(this,2,Intent(this,TransferService::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE)
        val text=when {
            state.oneTouchImport -> "正在自动导入相机共享照片"
            state.transferring && state.scan.phase==ScanPhase.Scanning -> "正在扫描并传输相机文件"
            state.transferring -> "正在传输相机文件"
            state.scan.phase==ScanPhase.Scanning -> "正在扫描相机文件 ${state.scan.scanned}/${state.scan.total ?: "?"}"
            else -> "准备相机文件"
        }
        return NotificationCompat.Builder(this,"transfers").setSmallIcon(R.drawable.ic_camera).setContentTitle("Alpha Companion")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(0,"取消",cancel).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        // A stale timeout for an older start must not cancel a newer session.
        if(startId!=lastStartId) return
        controller.cancelBackgroundWork()
        stopSelfSafely()
    }
    override fun onDestroy() {
        stopWakeLock(); scope.cancel()
        // Only tear down work this instance still owns; a rejected start or a normal stop already
        // cleared ownership, so a newer session is never killed here.
        if(controller.backgroundActiveFlow.value) controller.cancelBackgroundWork()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object {
        private const val WAKE_LOCK_SLICE_MILLIS = 5L*60*1000
        private const val WAKE_LOCK_TOTAL_MILLIS = 6L*60*60*1000
        private const val WAKE_LOCK_RENEW_MARGIN_MILLIS = 30_000L
    }
}

