package com.carriez.flutter_hbb

/**
 * RentalCurtainView — общий билдер UI «шторы» и хелпер скрытия её из
 * MediaProjection.
 *
 * Используется из двух мест:
 *   • InputService (AccessibilityService) — добавляет штору как
 *     TYPE_ACCESSIBILITY_OVERLAY. Доверенный overlay, НЕ зажимается
 *     лимитом непрозрачности Android 12+ (0.8). Основной путь.
 *   • RentalBannerService — fallback через TYPE_APPLICATION_OVERLAY,
 *     когда accessibility-сервис не запущен.
 */

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object RentalCurtainView {

    private const val TAG = "RentalBanner"

    // Палитра «технического» экрана.
    private val COLOR_BG     = Color.BLACK
    private val COLOR_TITLE  = Color.WHITE
    private val COLOR_ACCENT = Color.rgb(111, 157, 214)   // стальной синий
    private val COLOR_TASK   = Color.rgb(205, 205, 205)
    private val COLOR_FOOTER = Color.rgb(112, 112, 112)

    private val DIAG_TASKS = listOf(
        "Initializing system diagnostics...",
        "Loading required modules...",
        "Running performance tests...",
        "Analyzing data sets....",
        "Executing calculations...",
        "Verifying results..."
    )

    /**
     * Строит самодостаточный View шторы. Мигающий курсор крутится сам
     * (через postDelayed) и останавливается, когда View отсоединяется
     * от окна — внешний Handler не нужен.
     */
    fun build(context: Context): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(context).apply {
            setBackgroundColor(COLOR_BG)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ----- центральный блок -----
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(context).apply {
            text = "WORK IN PROGRESS"
            textSize = 26f
            setTextColor(COLOR_TITLE)
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        center.addView(title)

        val subtitle = TextView(context).apply {
            text = "SYSTEM TESTING & CALCULATIONS IN PROGRESS"
            textSize = 11f
            setTextColor(COLOR_ACCENT)
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.10f
            setPadding(0, dp(10), 0, dp(40))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        center.addView(subtitle)

        // Диагностические строки — моноширинный блок, [OK] в одной колонке.
        val colWidth = DIAG_TASKS.maxOf { it.length } + 4
        for (task in DIAG_TASKS) {
            val line = task.padEnd(colWidth) + "[OK]"
            val span = SpannableString(line).apply {
                setSpan(ForegroundColorSpan(COLOR_TASK), 0, colWidth,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(COLOR_ACCENT), colWidth, line.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val row = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                text = span
                textSize = 13f
                setPadding(0, dp(3), 0, dp(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            center.addView(row)
        }

        // Мигающий курсор — визуальный признак «процесс идёт» (не завис).
        val cursor = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            text = "▮"
            textSize = 13f
            setTextColor(COLOR_ACCENT)
            setPadding(0, dp(14), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        center.addView(cursor)
        blinkCursor(cursor)

        root.addView(center)

        // ----- футер -----
        val footer = TextView(context).apply {
            text = "PLEASE DO NOT INTERFERE\nYOUR PATIENCE IS APPRECIATED"
            textSize = 10f
            setTextColor(COLOR_FOOTER)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setLineSpacing(dp(4).toFloat(), 1f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(44)
            }
        }
        root.addView(footer)

        return root
    }

    private fun blinkCursor(view: TextView) {
        val r = object : Runnable {
            private var on = true
            override fun run() {
                if (!view.isAttachedToWindow) return
                view.visibility = if (on) View.VISIBLE else View.INVISIBLE
                on = !on
                view.postDelayed(this, 530)
            }
        }
        view.postDelayed(r, 530)
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
