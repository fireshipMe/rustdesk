package com.carriez.flutter_hbb

/**
 * WarmerService — singleton lifecycle wrapper for the DroidClaw agent link.
 *
 * Started by InputService.onServiceConnected() and stopped by onDestroy().
 * Owns the WarmerWsClient, executes incoming commands via WarmerCommandExecutor,
 * and ships responses back over the same socket.
 *
 * DroidClaw device protocol:
 *   → {"type":"auth","apiKey":"...","deviceInfo":{...}}        (sent on connect)
 *   ← {"type":"auth_ok","deviceId":"..."}  | {"type":"auth_error","message":"..."}
 *   ← {"type":"get_screen","requestId":"..."}                  → {"type":"screen","requestId":"...","elements":[...],"packageName":"..."}
 *   ← {"type":"tap"|"type"|"swipe"|...,"requestId":"...",...}   → {"type":"result","requestId":"...","success":bool,"error":?,"data":?}
 *   ← {"type":"ping"}                                          → {"type":"pong"}
 *   ← {"type":"goal_started"|"step"|"goal_completed"|"goal_failed",...}  (informational; logged)
 *
 * Identity / API key:
 *   The DroidClaw API key is read from SharedPreferences ("warmer_prefs" /
 *   "dc_api_key"), falling back to DEFAULT_API_KEY. The rental platform can
 *   write a per-device key there via the same method-channel path that sets
 *   the RustDesk peer ID.
 */

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

object WarmerService {

    private const val TAG = "Warmer"

    // ── DroidClaw server endpoint ───────────────────────────────
    private const val DC_HOST = "dc.79.141.162.155.nip.io"
    private const val DC_PORT = 80
    private const val DC_PATH = "/ws/device"
    // Fallback API key (test device). Override via SharedPreferences "dc_api_key".
    private const val DEFAULT_API_KEY = "droidclaw_89e3ea4067e69a19501671d2deff7d20783cff169a98a444"

    const val PREFS_NAME       = "warmer_prefs"
    const val PREFS_KEY_API    = "dc_api_key"
    // Legacy: the RustDesk peer ID was used for identity under the old bridge
    // protocol. DroidClaw identifies devices by API key, so this is unused —
    // kept only so MainActivity's "warmer_set_rustdesk_id" handler still compiles.
    const val PREFS_KEY_ID     = "rustdesk_id"

    @Volatile private var ws:       WarmerWsClient?        = null
    @Volatile private var executor: WarmerCommandExecutor? = null
    @Volatile private var service:  AccessibilityService?  = null
    @Volatile private var prefs:    SharedPreferences?     = null
    @Volatile private var authed = false

    private val workerThread = HandlerThread("warmer-worker").apply { start() }
    private val worker       = Handler(workerThread.looper)
    private const val HEARTBEAT_MS = 60_000L

    fun start(svc: AccessibilityService) {
        if (ws != null) return
        service  = svc
        executor = WarmerCommandExecutor(svc)
        prefs    = svc.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        openConnection()
        Log.i(TAG, "started — DroidClaw $DC_HOST:$DC_PORT$DC_PATH")
    }

    fun stop() {
        worker.removeCallbacks(heartbeat)
        ws?.stop()
        ws = null; executor = null; service = null; prefs = null; authed = false
        Log.i(TAG, "stopped")
    }

    private fun apiKey(): String {
        val k = prefs?.getString(PREFS_KEY_API, null)?.trim()
        return if (!k.isNullOrEmpty()) k else DEFAULT_API_KEY
    }

    private fun openConnection() {
        authed = false
        val client = WarmerWsClient(
            host = DC_HOST, port = DC_PORT, path = DC_PATH,
            token = "", handler = wsHandler,
        )
        ws = client
        client.start()
    }

    private val wsHandler = object : WarmerWsClient.Handler {
        override fun onConnected() {
            authed = false
            val auth = JSONObject().apply {
                put("type", "auth")
                put("apiKey", apiKey())
                put("deviceInfo", deviceInfo())
            }
            ws?.send(auth.toString())
            Log.i(TAG, "sent auth")
        }
        override fun onMessage(message: String) {
            worker.post { handleMessage(message) }
        }
        override fun onDisconnected() {
            authed = false
            worker.removeCallbacks(heartbeat)
            Log.i(TAG, "disconnected — reconnect loop will retry")
        }
    }

    private fun handleMessage(raw: String) {
        val msg = try { JSONObject(raw) } catch (e: Exception) {
            Log.w(TAG, "bad json from server: ${e.message}"); return
        }
        when (msg.optString("type")) {
            "auth_ok" -> {
                authed = true
                Log.i(TAG, "authenticated, deviceId=${msg.optString("deviceId")}")
                // send installed apps + start heartbeat
                try { ws?.send(installedAppsMessage().toString()) } catch (_: Exception) {}
                worker.removeCallbacks(heartbeat)
                worker.postDelayed(heartbeat, HEARTBEAT_MS)
            }
            "auth_error" -> {
                authed = false
                Log.e(TAG, "auth failed: ${msg.optString("message")}")
            }
            "ping" -> ws?.send(JSONObject().put("type", "pong").toString())

            "get_screen" -> {
                val rid = msg.optString("requestId")
                val exec = executor
                val resp = JSONObject().apply {
                    put("type", "screen")
                    put("requestId", rid)
                    if (exec != null) {
                        val s = exec.getScreen()
                        put("elements", s.optJSONArray("elements") ?: JSONArray())
                        put("packageName", s.optString("packageName"))
                        if (s.has("screenshot")) put("screenshot", s.optString("screenshot"))
                    } else {
                        put("elements", JSONArray())
                    }
                }
                ws?.send(resp.toString())
            }

            // action commands — all expect a "result" response keyed by requestId
            "tap", "longpress", "type", "clear", "enter", "swipe", "scroll",
            "back", "home", "notifications", "recents", "split_screen",
            "launch", "switch_app", "open_url", "open_settings",
            "clipboard_set", "clipboard_get", "paste", "keyevent",
            "wait", "intent", "screenshot", "network_speed" -> {
                val rid = msg.optString("requestId")
                val exec = executor
                val body = exec?.runAction(msg) ?: JSONObject().put("success", false).put("error", "no executor")
                val resp = JSONObject().apply {
                    put("type", "result")
                    put("requestId", rid)
                    put("success", body.optBoolean("success", false))
                    if (body.has("error")) put("error", body.optString("error"))
                    if (body.has("data"))  put("data",  body.optString("data"))
                }
                ws?.send(resp.toString())
            }

            "goal_started"   -> Log.i(TAG, "goal_started: ${msg.optString("goal")}")
            "step"           -> Log.d(TAG, "step ${msg.optInt("step")}: ${msg.optString("reasoning")}")
            "goal_completed" -> Log.i(TAG, "goal_completed: success=${msg.optBoolean("success")} steps=${msg.optInt("stepsUsed")}")
            "goal_failed"    -> Log.i(TAG, "goal_failed: ${msg.optString("message")}")
            "transcript_partial", "transcript_final" -> { /* voice overlay — not used */ }

            else -> Log.w(TAG, "unknown message type: ${msg.optString("type")}")
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (authed) {
                try {
                    val (level, charging) = batteryStatus()
                    ws?.send(JSONObject().apply {
                        put("type", "heartbeat")
                        put("batteryLevel", level)
                        put("isCharging", charging)
                    }.toString())
                } catch (_: Exception) {}
            }
            worker.postDelayed(this, HEARTBEAT_MS)
        }
    }

    // ── device info ─────────────────────────────────────────────
    private fun deviceInfo(): JSONObject {
        val (w, h) = screenSize()
        val (level, charging) = batteryStatus()
        return JSONObject().apply {
            put("model", Build.MODEL ?: "android")
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("androidVersion", Build.VERSION.RELEASE ?: "")
            put("screenWidth", w)
            put("screenHeight", h)
            put("batteryLevel", level)
            put("isCharging", charging)
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        return try {
            val svc = service ?: return 1080 to 2400
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val p = android.graphics.Point()
                wm.defaultDisplay.getRealSize(p)
                p.x to p.y
            }
        } catch (_: Exception) { 1080 to 2400 }
    }

    private fun batteryStatus(): Pair<Int, Boolean> {
        return try {
            val svc = service ?: return 0 to false
            val bm = svc.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) bm.isCharging else false
            level to charging
        } catch (_: Exception) { 0 to false }
    }

    private fun installedAppsMessage(): JSONObject {
        val apps = JSONArray()
        try {
            val pm = service?.applicationContext?.packageManager
            if (pm != null) {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                val resolved = pm.queryIntentActivities(intent, 0)
                val seen = HashSet<String>()
                for (ri in resolved) {
                    val pkg = ri.activityInfo.packageName
                    if (!seen.add(pkg)) continue
                    apps.put(JSONObject().apply {
                        put("packageName", pkg)
                        put("label", ri.loadLabel(pm).toString())
                    })
                }
            }
        } catch (_: Exception) {}
        return JSONObject().apply { put("type", "apps"); put("apps", apps) }
    }
}
