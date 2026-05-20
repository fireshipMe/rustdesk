package com.carriez.flutter_hbb

import android.app.Application
import android.util.Log
import ffi.FFI

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "App start")
        FFI.onAppStart(applicationContext)
        // Activate Knox KPE license + resolve RemoteInjection early, so the first
        // remote session can already inject privileged taps. No-op on non-Samsung
        // devices and when no license key is baked in (gesture fallback stays).
        try {
            KnoxInput.activate(applicationContext)
        } catch (e: Throwable) {
            Log.w(TAG, "KnoxInput.activate failed: ${e.message}")
        }
    }
}
