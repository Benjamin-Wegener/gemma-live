package com.sisa.app.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Dummy-Foreground-Service vom Typ mediaProjection: Android verlangt für
 * getMediaProjection() einen aktiven FGS dieses Typs (API 29+). Hält kein
 * Audio, keine Logik — nur das Token-Fenster offen.
 */
class CaptureHolderService : Service() {

    companion object {
        const val CHANNEL_ID = "capture_holder"
    }

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr?.getNotificationChannel(CHANNEL_ID) == null) {
            mgr?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Capture-Bereitschaft", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sisa Capture bereit")
            .setContentText("In-Emulator-Audiopfad aktiv")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, notif)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
