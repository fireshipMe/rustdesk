package com.carriez.flutter_hbb

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * PrivacyScreenService — занавеска поверх экрана.
 *
 * ВАЖНО: работает только в XML capture режиме.
 * В XML режиме overlay НЕ попадает в захват — рисуем дерево нод а не пиксели.
 * В MP режиме — не показываем (MP захватывает всё включая overlay).
 *
 * PNG файл: flutter/android/app/src/main/res/drawable/privacy_screen.png
 */
class PrivacyScreenService : Service() {

    companion object {
        private const val TAG = "PrivacyScreen"
        const val ACTION_SHOW = "com.carriez.flutter_hbb.PRIVACY_SCREEN_SHOW"
        const val ACTION_HIDE = "com.carriez.flutter_hbb.PRIVACY_SCREEN_HIDE"

        @Volatile var isShowing = false
            private set

        fun show(context: Context) {
            // Показываем ТОЛЬКО в XML режиме
            if (CaptureController.activeMethod != CaptureController.METHOD_XML) {
                Log.d(TAG, "show() skipped — not in XML mode (MP captures overlay)")
                return
            }
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
            context.startService(
                Intent(context, PrivacyScreenService::class.java).apply {
                    action = ACTION_HIDE
                }
            )
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val animHandler = Handler(Looper.getMainLooper())
    private var animStep = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> { hideOverlay(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        animHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Overlay
    // -----------------------------------------------------------------------

    private fun showOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layout = buildView()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or      // InputService работает
            WindowManager.LayoutParams.FLAG_FULLSCREEN or          // перекрываем статус-бар
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS       // за края экрана

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            // Перекрываем cutout/notch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Блокируем ВСЕ касания пользователя
        layout.setOnTouchListener { _, _ -> true }

        try {
            windowManager?.addView(layout, params)
            overlayView = layout
            isShowing = true
            Log.i(TAG, "Privacy screen shown (XML mode)")
        } catch (e: Exception) {
            Log.e(TAG, "showOverlay failed: ${e.message}")
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) }
            catch (e: Exception) { Log.e(TAG, "hideOverlay: ${e.message}") }
        }
        overlayView = null
        isShowing = false
        Log.i(TAG, "Privacy screen hidden")
    }

    // -----------------------------------------------------------------------
    // View
    // -----------------------------------------------------------------------

    private fun buildView(): FrameLayout {
        val root = FrameLayout(this).apply {
            // Чёрный фон — гарантированно непрозрачный
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // PNG картинка на весь экран
        // Положи файл: res/drawable/privacy_screen.png
        val img = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_XY  // растянуть точно на экран
            val resId = resources.getIdentifier("privacy_screen", "drawable", packageName)
            if (resId != 0) {
                setImageResource(resId)
                Log.d(TAG, "privacy_screen.png loaded")
            } else {
                setBackgroundColor(Color.rgb(18, 18, 24))
                Log.w(TAG, "privacy_screen.png not found in res/drawable/")
            }
        }
        root.addView(img)

        // Анимация снизу
        val dots = TextView(this).apply {
            text = "● ● ●"
            textSize = 16f
            setTextColor(Color.argb(160, 255, 255, 255))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dpToPx(48)
            }
        }
        root.addView(dots)
        animateDots(dots)

        return root
    }

    private fun animateDots(view: TextView) {
        val frames = listOf("● ● ●", "○ ● ●", "● ○ ●", "● ● ○")
        val r = object : Runnable {
            override fun run() {
                if (overlayView == null) return
                view.text = frames[animStep++ % frames.size]
                animHandler.postDelayed(this, 500)
            }
        }
        animHandler.postDelayed(r, 500)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
