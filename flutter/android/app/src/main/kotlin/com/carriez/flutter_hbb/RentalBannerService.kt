package com.carriez.flutter_hbb

/**
 * RentalBannerService — полноэкранная «штора» «ИДЁТ АРЕНДА».
 *
 * Цель: пока админ подключён к телефону через RustDesk, сотрудник
 * (физически держащий устройство) НЕ должен видеть, что админ делает —
 * какие приложения открыты, какие пароли вводятся, какие настройки меняются.
 *
 * Архитектура:
 *   • Полноэкранный непрозрачный TYPE_APPLICATION_OVERLAY поверх всего экрана.
 *   • Все касания сотрудника поглощаются (он не может ткнуть в управление).
 *   • Админ управляет через AccessibilityService (InputService) —
 *     эти gesture-инъекции идут на уровне системы, мимо touch-диспетчера
 *     overlay-окна, поэтому работают свободно.
 *   • Штора скрыта из MediaProjection-стрима двумя независимыми механизмами:
 *       1. SurfaceControl#setSkipScreenshot(true) через reflection (API 30+)
 *       2. VirtualDisplay пересоздаётся с VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
 *          (см. MainService.recreateVirtualDisplay)
 *     Оба хрупкие/OEM-зависимые, поэтому держим оба — на разных прошивках
 *     срабатывает хоть один.
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class RentalBannerService : Service() {

    companion object {
        private const val TAG = "RentalBanner"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_SHOW = "com.carriez.flutter_hbb.BANNER_SHOW"
        const val ACTION_HIDE = "com.carriez.flutter_hbb.BANNER_HIDE"

        @Volatile var isShowing = false
            private set

        /**
         * Запускает штору. Возвращает false, если SYSTEM_ALERT_WINDOW
         * не выдан (звонящая сторона должна обработать — например,
         * не давать сессии начаться).
         */
        fun show(context: Context): Boolean {
            Log.i(TAG, "show() entry, sdk=${Build.VERSION.SDK_INT}, isShowing=$isShowing")
            toastUi(context, "RentalBanner: show() called")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val canDraw = android.provider.Settings.canDrawOverlays(context)
                Log.i(TAG, "show() canDrawOverlays=$canDraw")
                if (!canDraw) {
                    Log.e(TAG, "show() FAILED — SYSTEM_ALERT_WINDOW NOT GRANTED")
                    Log.e(TAG, "Fix: Settings → Apps → RustDesk → Display over other apps → Allow")
                    toastUi(context, "RentalBanner ERROR: overlay-permission NOT granted")
                    return false
                }
            }
            val intent = Intent(context, RentalBannerService::class.java).apply {
                action = ACTION_SHOW
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.i(TAG, "show() calling startForegroundService()")
                    context.startForegroundService(intent)
                } else {
                    Log.i(TAG, "show() calling startService() (pre-O)")
                    context.startService(intent)
                }
                Log.i(TAG, "show() startService returned cleanly")
            } catch (e: Exception) {
                Log.e(TAG, "show() startForegroundService THREW: ${e.javaClass.simpleName}: ${e.message}", e)
                toastUi(context, "RentalBanner ERROR: startService failed: ${e.message}")
                return false
            }
            return true
        }

        fun hide(context: Context) {
            Log.i(TAG, "hide() entry, isShowing=$isShowing")
            toastUi(context, "RentalBanner: hide() called")
            try {
                context.startService(
                    Intent(context, RentalBannerService::class.java).apply {
                        action = ACTION_HIDE
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "hide() startService failed (likely OK if not running): ${e.message}")
            }
        }

        /** Toast в UI-thread — пользователь видит, что что-то происходит, без logcat. */
        private fun toastUi(context: Context, msg: String) {
            try {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) { /* ignore — toast — best-effort диагностика */ }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val animHandler = Handler(Looper.getMainLooper())
    private var animStep = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> { hideOverlay(); stopSelf() }
            else -> Log.w(TAG, "onStartCommand: unknown action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
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
        Log.i(TAG, "showOverlay() entry, overlayView=${overlayView != null}")
        // startForeground обязателен в течение 5 секунд после
        // startForegroundService() — иначе ANR (ForegroundServiceDidNotStartInTimeException).
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "showOverlay: startForeground OK")
        } catch (e: Exception) {
            Log.e(TAG, "showOverlay: startForeground FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            // Не возвращаемся — попробуем хотя бы добавить view, может OEM позволит
        }

        if (overlayView != null) {
            Log.d(TAG, "showOverlay: already shown, skip")
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ERROR

        // FLAG_NOT_TOUCHABLE  — КЛЮЧЕВОЙ флаг. Штора прозрачна для касаний:
        //                       инжектированные жесты от RustDesk InputService
        //                       (dispatchGesture) проходят hit-test и попадают
        //                       в приложение под шторой. Без этого флага штора
        //                       съедала бы ввод админа — управление не работало.
        //                       Сотрудник видит только непрозрачную штору и не
        //                       видит, что делает админ — приватность сохранена,
        //                       даже если он коснётся экрана вслепую.
        // FLAG_NOT_FOCUSABLE  — input-фокус остаётся у нижележащих окон.
        // FLAG_FULLSCREEN     — перекрываем статус-бар.
        // FLAG_LAYOUT_IN_SCREEN — рендеримся в системной области.
        // FLAG_LAYOUT_NO_LIMITS — за edges экрана (под cutout/notch).
        // FLAG_HARDWARE_ACCELERATED — гарантирует рендеринг через SurfaceControl,
        //                       чтобы setSkipScreenshot был применим.
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            // Тянем под notch/cutout, иначе сверху останется полоса экрана.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val view = buildView()
        // Без setOnTouchListener — окно с FLAG_NOT_TOUCHABLE и так не получает
        // касаний, они проходят сквозь штору к приложению (нужно для ввода админа).

        Log.i(TAG, "showOverlay: about to addView wm=$windowManager type=$type flags=0x${flags.toString(16)}")
        try {
            windowManager?.addView(view, params)
            overlayView = view
            isShowing = true
            Log.i(TAG, "✅ Privacy overlay ADDED to WindowManager — should be visible now")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "RentalBanner: overlay shown", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ showOverlay addView FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext,
                    "RentalBanner ERROR: addView failed: ${e.message}",
                    Toast.LENGTH_LONG).show()
            }
            return
        }

        // Скрываем штору из MediaProjection двумя независимыми путями:
        // 1) пометить Surface как skipScreenshot (API 30+, reflection)
        applySkipScreenshot(view)
        // 2) пересоздать VirtualDisplay с OWN_CONTENT_ONLY
        val mainSvc = MainService.instance
        Log.i(TAG, "showOverlay: MainService.instance=${mainSvc != null}")
        try {
            mainSvc?.recreateVirtualDisplay()
        } catch (e: Exception) {
            Log.w(TAG, "recreateVirtualDisplay failed: ${e.message}")
        }
    }

    private fun hideOverlay() {
        Log.i(TAG, "hideOverlay() entry, overlayView=${overlayView != null}")
        val hadOverlay = overlayView != null
        overlayView?.let {
            try {
                windowManager?.removeView(it)
                Log.i(TAG, "hideOverlay: removeView OK")
            } catch (e: Exception) { Log.e(TAG, "hideOverlay removeView FAILED: ${e.message}") }
        }
        overlayView = null
        isShowing = false
        Log.i(TAG, "Privacy overlay hidden")
        // Пересоздаём VirtualDisplay только если штора реально была показана —
        // иначе ничего не изменилось во флагах VD, а лишний recreate в момент
        // завершения сессии может создать zombie VD на освобождённом surface
        // (MainService.stopCapture отпускает surface), который удерживает
        // PowerManager и не даёт устройству нормально уснуть.
        if (hadOverlay) {
            try {
                MainService.instance?.recreateVirtualDisplay()
            } catch (e: Exception) {
                Log.w(TAG, "recreateVirtualDisplay (hide path) failed: ${e.message}")
            }
        } else {
            Log.i(TAG, "hideOverlay: overlay wasn't shown, skip recreateVirtualDisplay")
        }
    }

    /**
     * Снимает hidden-API блоклист для текущего процесса через meta-reflection.
     * Без этого SurfaceControl$Transaction.setSkipScreenshot (@hide) недоступен
     * для рефлексии — getMethod кидает NoSuchMethodException.
     *
     * Техника: вызываем Class.getDeclaredMethod через рефлексию-над-рефлексией,
     * тогда «вызывающим» для blocklisted-метода считается сам фреймворк, а не
     * наш код. Дальше VMRuntime.setHiddenApiExemptions(["L"]) снимает блок для
     * всех классов. Работает на Android 9-14 (значение проверено).
     */
    private fun relaxHiddenApiPolicy() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = getDeclaredMethod.invoke(
                vmRuntimeClass, "getRuntime", arrayOfNulls<Class<*>>(0)) as java.lang.reflect.Method
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions",
                arrayOf<Class<*>>(arrayOf<String>()::class.java)) as java.lang.reflect.Method
            val vmRuntime = getRuntime.invoke(null)
            setExemptions.invoke(vmRuntime, arrayOf("L"))
            Log.i(TAG, "relaxHiddenApiPolicy: hidden-API exemptions applied")
        } catch (e: Exception) {
            Log.w(TAG, "relaxHiddenApiPolicy failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Помечает Surface overlay-окна как skipScreenshot — кадры этого окна
     * не попадают в MediaProjection. Hidden API, доступен на Android 11+ (R).
     *
     * Это ЕДИНСТВЕННЫЙ рабочий механизм скрытия шторы от админского стрима
     * (флаг OWN_CONTENT_ONLY оказался взаимоисключающим с AUTO_MIRROR и не
     * исключает overlay). Если рефлексия упала — штора будет видна в стриме.
     */
    private fun applySkipScreenshot(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "applySkipScreenshot: SDK<R — setSkipScreenshot недоступен")
            return
        }
        // Снять блоклист ДО рефлексии setSkipScreenshot.
        relaxHiddenApiPolicy()
        view.post {
            try {
                val getViewRootImpl = view.javaClass.getMethod("getViewRootImpl")
                val viewRootImpl = getViewRootImpl.invoke(view) ?: run {
                    Log.w(TAG, "applySkipScreenshot: getViewRootImpl() == null")
                    return@post
                }

                val getSurfaceControl = viewRootImpl.javaClass.getMethod("getSurfaceControl")
                val surfaceControl = getSurfaceControl.invoke(viewRootImpl) ?: run {
                    Log.w(TAG, "applySkipScreenshot: getSurfaceControl() == null")
                    return@post
                }

                val surfaceControlClass = Class.forName("android.view.SurfaceControl")
                val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
                val tx = txClass.getConstructor().newInstance()

                val setSkip = txClass.getMethod(
                    "setSkipScreenshot",
                    surfaceControlClass,
                    Boolean::class.javaPrimitiveType
                )
                setSkip.invoke(tx, surfaceControl, true)
                txClass.getMethod("apply").invoke(tx)

                Log.i(TAG, "✅ setSkipScreenshot(true) applied — overlay hidden from MediaProjection")
            } catch (e: NoSuchMethodException) {
                Log.e(TAG, "❌ applySkipScreenshot: setSkipScreenshot NOT FOUND даже после relaxHiddenApiPolicy — " +
                        "штора будет видна в стриме админа: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ applySkipScreenshot failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // -----------------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------------

    private fun buildView(): FrameLayout {
        val root = FrameLayout(this).apply {
            // Тёмный фон — гарантированно непрозрачный + спокойный для глаз.
            setBackgroundColor(Color.rgb(15, 18, 28))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "ИДЁТ АРЕНДА"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.15f
            setPadding(dpToPx(24), 0, dpToPx(24), dpToPx(16))
        }
        column.addView(title)

        val subtitle = TextView(this).apply {
            text = "Идёт удалённая сессия.\nЭкран временно недоступен."
            textSize = 14f
            setTextColor(Color.argb(180, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(dpToPx(24), 0, dpToPx(24), dpToPx(32))
        }
        column.addView(subtitle)

        val dots = TextView(this).apply {
            text = "● ● ●"
            textSize = 20f
            setTextColor(Color.argb(200, 220, 38, 38))
            gravity = Gravity.CENTER
            letterSpacing = 0.4f
        }
        column.addView(dots)
        animateDots(dots)

        root.addView(column)
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

    // -----------------------------------------------------------------------
    // Notification (требование foreground service)
    // -----------------------------------------------------------------------

    private fun buildNotification(): android.app.Notification {
        val channelId = "rental_banner"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                channelId, "Rental Privacy Screen",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
        return android.app.Notification.Builder(this, channelId)
            .setContentTitle("Идёт аренда")
            .setContentText("Удалённая сессия активна")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
