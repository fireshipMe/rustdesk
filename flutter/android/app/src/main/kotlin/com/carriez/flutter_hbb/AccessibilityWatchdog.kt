package com.carriez.flutter_hbb

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * AccessibilityWatchdog — следит за состоянием InputService и перезапускает его при падении.
 *
 * Требует: android.permission.WRITE_SECURE_SETTINGS
 *
 * Выдать через ADB (один раз):
 *   adb shell pm grant com.carriez.flutter_hbb android.permission.WRITE_SECURE_SETTINGS
 *
 * Через Esper MDM:
 *   Device Policy → App Permissions → WRITE_SECURE_SETTINGS = grant
 *
 * Принцип: ContentObserver на ENABLED_ACCESSIBILITY_SERVICES.
 * Если наш сервис исчез из списка — toggle off/on через Settings.Secure.
 */
object AccessibilityWatchdog {

    private const val TAG = "AccessibilityWatchdog"
    private const val RESTART_DELAY_MS = 1_000L  // пауза между off и on

    private var observer: ContentObserver? = null
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun start(context: Context) {
        if (observer != null) return  // уже запущен
        appContext = context.applicationContext

        if (!hasPermission(context)) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted — watchdog disabled")
            Log.w(TAG, "Grant via: adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
            return
        }

        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "ENABLED_ACCESSIBILITY_SERVICES changed")
                checkAndRestart()
            }
        }

        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer!!
        )

        Log.i(TAG, "Watchdog started")
    }

    fun stop(context: Context) {
        observer?.let {
            context.contentResolver.unregisterContentObserver(it)
            observer = null
        }
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Watchdog stopped")
    }

    fun hasPermission(context: Context): Boolean {
        return try {
            context.checkCallingOrSelfPermission(
                "android.permission.WRITE_SECURE_SETTINGS"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    // -----------------------------------------------------------------------
    // Restart logic
    // -----------------------------------------------------------------------

    private fun checkAndRestart() {
        val context = appContext ?: return
        if (isOurServiceEnabled(context)) {
            Log.d(TAG, "Service still enabled — no action needed")
            return
        }

        Log.w(TAG, "InputService disappeared! Attempting restart...")
        restartAccessibilityService(context)
    }

    private fun isOurServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val ourService = "${context.packageName}/${InputService::class.java.canonicalName}"
        return enabled.split(":").any { it.equals(ourService, ignoreCase = true) }
    }

    private fun restartAccessibilityService(context: Context) {
        try {
            val ourService = "${context.packageName}/${InputService::class.java.canonicalName}"
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            // Шаг 1: убираем наш сервис из списка (off)
            val withoutUs = current.split(":").filter {
                !it.equals(ourService, ignoreCase = true)
            }.joinToString(":")

            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                withoutUs
            )
            Log.d(TAG, "Step 1: removed from enabled list")

            // Шаг 2: через 1 секунду добавляем обратно (on)
            handler.postDelayed({
                try {
                    val refreshed = Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ) ?: ""

                    val withUs = if (refreshed.isEmpty()) ourService
                                 else "$refreshed:$ourService"

                    Settings.Secure.putString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        withUs
                    )

                    // Убеждаемся что accessibility вообще включён
                    Settings.Secure.putInt(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1
                    )

                    Log.i(TAG, "Step 2: re-enabled InputService successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Step 2 failed: ${e.message}")
                }
            }, RESTART_DELAY_MS)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException — WRITE_SECURE_SETTINGS not granted: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "restartAccessibilityService failed: ${e.message}")
        }
    }
}
