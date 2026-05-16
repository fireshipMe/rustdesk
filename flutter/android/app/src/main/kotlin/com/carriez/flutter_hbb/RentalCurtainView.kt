package com.carriez.flutter_hbb

/**
 * RentalCurtainView — общий билдер UI «шторы» «ИДЁТ АРЕНДА» и хелпер
 * скрытия её из MediaProjection.
 *
 * Используется из двух мест:
 *   • InputService (AccessibilityService) — добавляет штору как
 *     TYPE_ACCESSIBILITY_OVERLAY. Такое окно — доверенный overlay,
 *     НЕ зажимается лимитом непрозрачности Android 12+ (0.8), поэтому
 *     штора там полностью непрозрачна. Основной путь.
 *   • RentalBannerService — fallback через TYPE_APPLICATION_OVERLAY,
 *     когда accessibility-сервис не запущен. Там Android зажмёт альфу
 *     до 0.8 (ничего не поделать без accessibility).
 */

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object RentalCurtainView {

    private const val TAG = "RentalBanner"

    /**
     * Строит самодостаточный View шторы. Анимация точек крутится сама
     * (через postDelayed) и останавливается, когда View отсоединяется
     * от окна — внешний Handler не нужен.
     */
    fun build(context: Context): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(context).apply {
            // Сплошной непрозрачный тёмный фон.
            setBackgroundColor(Color.rgb(15, 18, 28))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(context).apply {
            text = "ИДЁТ АРЕНДА"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.15f
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        column.addView(title)

        val subtitle = TextView(context).apply {
            text = "Идёт удалённая сессия.\nЭкран временно недоступен."
            textSize = 14f
            setTextColor(Color.argb(180, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), dp(32))
        }
        column.addView(subtitle)

        val dots = TextView(context).apply {
            text = "● ● ●"
            textSize = 20f
            setTextColor(Color.argb(200, 220, 38, 38))
            gravity = Gravity.CENTER
            letterSpacing = 0.4f
        }
        column.addView(dots)
        root.addView(column)

        animateDots(dots)
        return root
    }

    private fun animateDots(view: TextView) {
        val frames = listOf("● ● ●", "○ ● ●", "● ○ ●", "● ● ○")
        var step = 0
        val r = object : Runnable {
            override fun run() {
                // Останавливаемся, когда штора снята с окна.
                if (!view.isAttachedToWindow) return
                view.text = frames[step++ % frames.size]
                view.postDelayed(this, 500)
            }
        }
        view.postDelayed(r, 500)
    }

    // -----------------------------------------------------------------------
    // Скрытие шторы из MediaProjection
    // -----------------------------------------------------------------------

    /**
     * Помечает Surface окна шторы как skipScreenshot — кадры окна не
     * попадают в MediaProjection-стрим. Hidden API (API 30+).
     *
     * Требует разблокированного hidden-API. Встроенный обход на Android 13
     * закрыт Google, поэтому на флоте hidden_api_policy=1 раскатывается
     * через Esper. relaxHiddenApiPolicy() вызывается как best-effort.
     */
    fun applySkipScreenshot(view: View) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Log.d(TAG, "applySkipScreenshot: SDK<R — setSkipScreenshot недоступен")
            return
        }
        relaxHiddenApiPolicy()
        view.post {
            try {
                val viewRootImpl = view.javaClass.getMethod("getViewRootImpl").invoke(view)
                    ?: run { Log.w(TAG, "applySkipScreenshot: getViewRootImpl() == null"); return@post }
                val surfaceControl = viewRootImpl.javaClass.getMethod("getSurfaceControl")
                    .invoke(viewRootImpl)
                    ?: run { Log.w(TAG, "applySkipScreenshot: getSurfaceControl() == null"); return@post }

                val surfaceControlClass = Class.forName("android.view.SurfaceControl")
                val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
                val tx = txClass.getConstructor().newInstance()
                txClass.getMethod(
                    "setSkipScreenshot", surfaceControlClass, Boolean::class.javaPrimitiveType
                ).invoke(tx, surfaceControl, true)
                txClass.getMethod("apply").invoke(tx)

                Log.i(TAG, "✅ setSkipScreenshot(true) applied — штора скрыта из MediaProjection")
            } catch (e: NoSuchMethodException) {
                Log.e(TAG, "❌ applySkipScreenshot: setSkipScreenshot NOT FOUND — " +
                        "нужен hidden_api_policy=1 (Esper): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ applySkipScreenshot failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Best-effort снятие hidden-API блоклиста через meta-reflection.
     * На Android 13 Google закрыл этот обход — основной путь это
     * hidden_api_policy=1 на устройстве (раскатывается через Esper).
     */
    private fun relaxHiddenApiPolicy() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        try {
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = getDeclaredMethod.invoke(
                vmRuntimeClass, "getRuntime", arrayOfNulls<Class<*>>(0)) as java.lang.reflect.Method
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions",
                arrayOf<Class<*>>(arrayOf<String>()::class.java)) as java.lang.reflect.Method
            setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
            Log.i(TAG, "relaxHiddenApiPolicy: hidden-API exemptions applied")
        } catch (e: Exception) {
            Log.w(TAG, "relaxHiddenApiPolicy failed (ожидаемо на Android 13): ${e.javaClass.simpleName}")
        }
    }
}
