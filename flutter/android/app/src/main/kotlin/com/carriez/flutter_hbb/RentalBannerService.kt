package com.carriez.flutter_hbb

/**
 * RentalBannerService — узкая полоса сверху экрана «ИДЁТ АРЕНДА».
 *
 * Видна и хосту (физически), и арендатору (через MediaProjection-стрим).
 * Касания проходят насквозь — арендатор пользуется телефоном как обычно.
 *
 * Жизненный цикл:
 *   • show() — при авторизации первого клиента (server_model.dart sendLoginResponse)
 *   • hide() — при уходе последнего клиента (onClientRemove) или остановке сервиса
 */

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class RentalBannerService : Service() {

    companion object {
        private const val TAG = "RentalBanner"
        const val ACTION_SHOW = "com.carriez.flutter_hbb.BANNER_SHOW"
        const val ACTION_HIDE = "com.carriez.flutter_hbb.BANNER_HIDE"

        @Volatile var isShowing = false
            private set

        fun show(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(context)) {
                Log.e(TAG, "show() failed — SYSTEM_ALERT_WINDOW not granted")
                return
            }
            val intent = Intent(context, RentalBannerService::class.java).apply {
                action = ACTION_SHOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            context.startService(
                Intent(context, RentalBannerService::class.java).apply {
                    action = ACTION_HIDE
                }
            )
        }
    }

    private var windowManager: WindowManager? = null
    private var bannerView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBanner()
            ACTION_HIDE -> { hideBanner(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideBanner()
        super.onDestroy()
    }

    private fun showBanner() {
        startForeground(1, buildNotification())
        if (bannerView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

        // NOT_TOUCHABLE     — касания проходят насквозь
        // NOT_FOCUSABLE     — не перехватывает фокус ввода
        // LAYOUT_IN_SCREEN  — рендеримся в системной области (поверх status bar)
        // LAYOUT_NO_LIMITS  — за edges экрана (под cutout)
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(36),
            type, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val banner = buildBanner()
        try {
            windowManager?.addView(banner, params)
            bannerView = banner
            isShowing = true
            Log.i(TAG, "banner shown")
        } catch (e: Exception) {
            Log.e(TAG, "showBanner failed: ${e.message}")
        }
    }

    private fun hideBanner() {
        bannerView?.let {
            try { windowManager?.removeView(it) }
            catch (e: Exception) { Log.e(TAG, "hideBanner: ${e.message}") }
        }
        bannerView = null
        isShowing = false
        Log.i(TAG, "banner hidden")
    }

    private fun buildBanner(): FrameLayout {
        val root = FrameLayout(this)
        root.background = GradientDrawable().apply {
            setColor(Color.argb(220, 220, 38, 38))
        }

        val text = TextView(this).apply {
            text = "● ИДЁТ АРЕНДА"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
        }
        root.addView(text)
        return root
    }

    private fun buildNotification(): android.app.Notification {
        val channelId = "rental_banner"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                channelId, "Rental Banner",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
        return android.app.Notification.Builder(this, channelId)
            .setContentTitle("Rental active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
