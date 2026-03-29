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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PrivacyScreenService — занавеска поверх экрана во время удалённого сеанса.
 *
 * Показывает PNG картинку (res/drawable/privacy_screen.png) поверх экрана.
 * Пользователь видит занавеску, не может трогать устройство.
 * Администратор видит экран через RustDesk.
 *
 * Для скрытия от RustDesk MP захвата вызывай setTransparentForCapture(true/false)
 * непосредственно перед/после каждого кадра.
 *
 * Управление:
 *   PrivacyScreenService.show(context)
 *   PrivacyScreenService.hide(context)
 *   PrivacyScreenService.setTransparentForCapture(true/false)
 */
class PrivacyScreenService : Service() {

    companion object {
        private const val TAG = "PrivacyScreen"
        const val ACTION_SHOW = "com.carriez.flutter_hbb.PRIVACY_SCREEN_SHOW"
        const val ACTION_HIDE = "com.carriez.flutter_hbb.PRIVACY_SCREEN_HIDE"

        @Volatile var isShowing = false
            private set

        @Volatile var instance: PrivacyScreenService? = null
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
            context.startService(Intent(context, PrivacyScreenService::class.java).apply {
                action = ACTION_HIDE
            })
        }

        /**
         * Делает занавеску прозрачной на момент захвата кадра RustDesk.
         * Вызывать из XmlCapture или MainService перед/после FFI.onVideoFrameUpdate().
         *
         * transparent=true  → alpha=0 (невидима в захвате)
         * transparent=false → alpha=1 (видима пользователю)
         */
        fun setTransparentForCapture(transparent: Boolean) {
            instance?.setOverlayAlpha(if (transparent) 0f else 1f)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate")
    }

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
        instance = null
        hideOverlay()
        animHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Alpha control
    // -----------------------------------------------------------------------

    fun setOverlayAlpha(alpha: Float) {
        Handler(Looper.getMainLooper()).post {
            overlayView?.alpha = alpha
        }
    }

    // -----------------------------------------------------------------------
    // Overlay
    // -----------------------------------------------------------------------

    private fun showOverlay() {
        if (overlayView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val layout = buildOverlayView()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            // Поглощаем касания — пользователь не может взаимодействовать
            // FLAG_NOT_FOCUSABLE — InputService продолжает работать
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Поглощаем все касания пользователя
        layout.setOnTouchListener { _, _ -> true }

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

    // -----------------------------------------------------------------------
    // View — PNG фон + анимация
    // -----------------------------------------------------------------------

    private fun buildOverlayView(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // PNG картинка — растянуть на весь экран
        // Файл: flutter/android/app/src/main/res/drawable/privacy_screen.png
        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_XY
            val resId = resources.getIdentifier("privacy_screen", "drawable", packageName)
            if (resId != 0) {
                setImageResource(resId)
                Log.d(TAG, "privacy_screen.png loaded")
            } else {
                setBackgroundColor(Color.rgb(18, 18, 24))
                Log.w(TAG, "privacy_screen.png not found — using dark fallback")
            }
        }
        root.addView(imageView)

        // Анимация точек поверх картинки (снизу по центру)
        val progress = TextView(this).apply {
            text = "● ● ●"
            textSize = 18f
            setTextColor(Color.argb(180, 255, 255, 255))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dpToPx(60)
            }
        }
        root.addView(progress)
        startProgressAnimation(progress)

        return root
    }

    // -----------------------------------------------------------------------
    // Анимация точек
    // -----------------------------------------------------------------------

    private val animHandler = Handler(Looper.getMainLooper())
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
