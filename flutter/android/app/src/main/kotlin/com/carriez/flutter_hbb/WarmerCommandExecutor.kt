package com.carriez.flutter_hbb

/**
 * WarmerCommandExecutor — executes DroidClaw server commands via the
 * AccessibilityService's tree access and gesture dispatch.
 *
 * Speaks the DroidClaw device protocol:
 *   • get_screen  → { elements: [UIElement...], packageName }
 *   • actions     → { success, error?, data? }
 *
 * UIElement shape mirrors droidclaw android ScreenTreeBuilder so the
 * server-side agent loop and LLM prompt see the exact format they expect.
 *
 * All AccessibilityNodeInfo lookups happen on a thread that may access
 * rootInActiveWindow (the worker handler in WarmerService is fine —
 * AccessibilityNodeInfo is documented thread-safe for reads).
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class WarmerCommandExecutor(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "WarmerExec"
    }

    // ── get_screen ──────────────────────────────────────────────
    // Returns { packageName, elements: [UIElement...] } in DroidClaw format.
    fun getScreen(): JSONObject {
        val root = service.rootInActiveWindow
        val elements = JSONArray()
        var pkg = ""
        if (root != null) {
            try {
                pkg = root.packageName?.toString() ?: ""
                walkTree(root, elements, 0, "")
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
        return JSONObject().apply {
            put("elements", elements)
            put("packageName", pkg)
        }
    }

    private fun walkTree(node: AccessibilityNodeInfo?, out: JSONArray, depth: Int, parentCls: String) {
        if (node == null) return
        try {
            val text        = node.text?.toString().orEmpty()
            val contentDesc  = node.contentDescription?.toString().orEmpty()
            val viewId       = node.viewIdResourceName.orEmpty()
            val className    = node.className?.toString().orEmpty()
            val displayText  = text.ifEmpty { contentDesc }

            val isInteractive = node.isClickable || node.isLongClickable ||
                node.isEditable || node.isScrollable || node.isFocusable

            if (isInteractive || displayText.isNotEmpty()) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val cx = (rect.left + rect.right) / 2
                val cy = (rect.top + rect.bottom) / 2
                val action = when {
                    node.isEditable     -> "type"
                    node.isScrollable    -> "scroll"
                    node.isLongClickable -> "longpress"
                    node.isClickable     -> "tap"
                    else                 -> "read"
                }
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    node.hintText?.toString().orEmpty() else ""

                val obj = JSONObject().apply {
                    put("id",            viewId)
                    put("text",          displayText)
                    put("type",          className.substringAfterLast("."))
                    put("bounds",        "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
                    put("center",        JSONArray().put(cx).put(cy))
                    put("size",          JSONArray().put(rect.width()).put(rect.height()))
                    put("clickable",     node.isClickable)
                    put("editable",      node.isEditable)
                    put("enabled",       node.isEnabled)
                    put("checked",       node.isChecked)
                    put("focused",       node.isFocused)
                    put("selected",      node.isSelected)
                    put("scrollable",    node.isScrollable)
                    put("longClickable", node.isLongClickable)
                    put("password",      node.isPassword)
                    put("hint",          hint)
                    put("action",        action)
                    put("parent",        parentCls)
                    put("depth",         depth)
                }
                out.put(obj)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walkTree(child, out, depth + 1, className)
                try { child.recycle() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            // node may have been recycled mid-traversal
        }
    }

    // ── actions ─────────────────────────────────────────────────
    // Returns { success: Boolean, error?: String, data?: String }.
    fun runAction(cmd: JSONObject): JSONObject = try {
        when (val type = cmd.getString("type")) {
            "tap"           -> doTap(cmd.getInt("x"), cmd.getInt("y"))
            "longpress"     -> doLongpress(cmd.getInt("x"), cmd.getInt("y"))
            "type"          -> doType(cmd.optString("text"))
            "clear"         -> doClear()
            "enter"         -> doEnter()
            "swipe"         -> doSwipe(cmd.getInt("x1"), cmd.getInt("y1"),
                                       cmd.getInt("x2"), cmd.getInt("y2"),
                                       cmd.optInt("duration", 300))
            "scroll"        -> doScrollDir(cmd.optString("direction", "down"))
            "back"          -> doGlobal(AccessibilityService.GLOBAL_ACTION_BACK)
            "home"          -> doGlobal(AccessibilityService.GLOBAL_ACTION_HOME)
            "notifications" -> doGlobal(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "recents"       -> doGlobal(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "split_screen"  -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                                 doGlobal(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                               else err("split_screen requires Android 7+")
            "launch"        -> doLaunch(cmd)
            "switch_app"    -> doLaunch(cmd)
            "open_url"      -> doOpenUrl(cmd.optString("url"))
            "open_settings" -> doOpenSettings(cmd.optString("setting", ""))
            "clipboard_set" -> doClipboardSet(cmd.optString("text"))
            "clipboard_get" -> doClipboardGet()
            "paste"         -> doPaste()
            "keyevent"      -> doKeyEvent(cmd.optInt("code"))
            "wait"          -> doWait(cmd.optInt("duration", 1000))
            "intent"        -> doIntent(cmd)
            "screenshot"    -> doScreenshot()
            "network_speed" -> JSONObject().put("success", true).put("data", SpeedTestExecutor.measure().toString())
            "ping"          -> ok("pong")
            else            -> err("unknown command: $type")
        }
    } catch (e: Exception) {
        Log.w(TAG, "action failed: ${e.message}")
        err(e.message ?: "unknown error")
    }

    // ── result helpers ──────────────────────────────────────────
    private fun ok(data: String? = null): JSONObject = JSONObject().apply {
        put("success", true); if (data != null) put("data", data)
    }
    private fun err(msg: String): JSONObject = JSONObject().apply {
        put("success", false); put("error", msg)
    }

    // ── tap / longpress ─────────────────────────────────────────
    private fun doTap(x: Int, y: Int): JSONObject {
        // Try a real CLICK on a clickable node under (x,y) first — fires JS
        // handlers / native click listeners more reliably than a bare tap on
        // some native apps. Web pages get a real gesture (handled below).
        val root = service.rootInActiveWindow
        val pkg = root?.packageName?.toString().orEmpty()
        val isWeb = pkg.contains("chrome") || pkg.contains("browser") ||
            pkg.contains("webview")
        var clicked = false
        if (root != null && !isWeb) {
            val node = findClickableAt(root, x, y)
            if (node != null) {
                try { clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                catch (_: Exception) {}
                finally { try { node.recycle() } catch (_: Exception) {} }
            }
        }
        try { root?.recycle() } catch (_: Exception) {}
        if (!clicked) clicked = tapGesture(x, y)
        return if (clicked) ok() else err("tap failed at ($x,$y)")
    }

    private fun doLongpress(x: Int, y: Int): JSONObject {
        val root = service.rootInActiveWindow
        var done = false
        if (root != null) {
            val node = findClickableAt(root, x, y)
            if (node != null) {
                try { done = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
                catch (_: Exception) {}
                finally { try { node.recycle() } catch (_: Exception) {} }
            }
            try { root.recycle() } catch (_: Exception) {}
        }
        if (!done) {
            // long-press gesture: tap-and-hold ~700ms at the point
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat() + 1, y.toFloat()) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 700))
                .build()
            done = service.dispatchGesture(g, null, null)
        }
        return if (done) ok() else err("longpress failed at ($x,$y)")
    }

    // ── text input ──────────────────────────────────────────────
    private fun doType(text: String): JSONObject {
        val root = service.rootInActiveWindow ?: return err("no active window")
        try {
            var target = findFocusedEditable(root)
            if (target == null) {
                target = findEditable(root)
                target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target ?: return err("no editable field found")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val okSet = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            try { target.recycle() } catch (_: Exception) {}
            return if (okSet) ok() else err("set_text failed")
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun doClear(): JSONObject {
        val root = service.rootInActiveWindow ?: return err("no active window")
        try {
            val target = findFocusedEditable(root) ?: findEditable(root)
                ?: return err("no editable field to clear")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            val okSet = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            try { target.recycle() } catch (_: Exception) {}
            return if (okSet) ok() else err("clear failed")
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun doEnter(): JSONObject {
        val root = service.rootInActiveWindow
        var done = false
        if (root != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null && focused.isEditable) {
                        try {
                            done = focused.performAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        } catch (_: Exception) {}
                        try { focused.recycle() } catch (_: Exception) {}
                    }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
        if (!done) done = doKeyEvent(KeyEvent.KEYCODE_ENTER).optBoolean("success", false)
        return if (done) ok() else err("enter failed")
    }

    // ── swipe / scroll ──────────────────────────────────────────
    private fun doSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): JSONObject {
        // Dismiss the soft keyboard first — a swipe that begins over the IME
        // injects the highlighted suggestion into the focused field.
        dismissKeyboard()
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong().coerceIn(50, 3000)))
            .build()
        return if (service.dispatchGesture(g, null, null)) ok() else err("swipe failed")
    }

    private fun doScrollDir(direction: String): JSONObject {
        dismissKeyboard()
        val root = service.rootInActiveWindow ?: return err("no active window")
        val b = Rect().also { root.getBoundsInScreen(it) }
        try { root.recycle() } catch (_: Exception) {}
        val cx = b.centerX()
        val h  = b.height()
        val (x1, y1, x2, y2) = when (direction) {
            "up"    -> listOf(cx, b.top + h / 4, cx, b.top + h * 3 / 4)
            "left"  -> listOf(b.right - 40, b.centerY(), b.left + 40, b.centerY())
            "right" -> listOf(b.left + 40, b.centerY(), b.right - 40, b.centerY())
            else    -> listOf(cx, b.top + h * 3 / 4, cx, b.top + h / 4) // down
        }
        return doSwipe(x1, y1, x2, y2, 300)
    }

    // ── global actions ──────────────────────────────────────────
    private fun doGlobal(action: Int): JSONObject =
        if (service.performGlobalAction(action)) ok() else err("global action $action failed")

    // ── launch / switch_app / open_url ──────────────────────────
    private fun doLaunch(cmd: JSONObject): JSONObject {
        val pkg = cmd.optString("packageName").ifEmpty { cmd.optString("package") }
        val uri = cmd.optString("intentUri").ifEmpty { cmd.optString("uri") }
        val extras = cmd.optJSONObject("intentExtras")
        if (uri.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pkg.isNotEmpty()) setPackage(pkg)
                putJsonExtras(this, extras)
            }
            return try { service.startActivity(intent); ok() }
            catch (e: Exception) { err("launch uri failed: ${e.message}") }
        }
        if (pkg.isEmpty()) return err("no package or uri provided")
        val intent = service.applicationContext.packageManager.getLaunchIntentForPackage(pkg)
            ?: return err("package not installed: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putJsonExtras(intent, extras)
        return try { service.startActivity(intent); ok() }
        catch (e: Exception) { err("launch failed: ${e.message}") }
    }

    private fun doOpenUrl(url: String): JSONObject {
        if (url.isEmpty()) return err("url required")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try { service.startActivity(intent); ok() }
        catch (e: Exception) { err("open_url failed: ${e.message}") }
    }

    // ── clipboard ───────────────────────────────────────────────
    private fun doClipboardSet(text: String): JSONObject {
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("warmer", text))
        return ok()
    }
    private fun doClipboardGet(): JSONObject {
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return ok(cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "")
    }
    private fun doPaste(): JSONObject {
        val root = service.rootInActiveWindow ?: return err("no active window")
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditable(root) ?: return err("no field to paste into")
            val done = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            try { focused.recycle() } catch (_: Exception) {}
            return if (done) ok() else err("paste failed")
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    // ── keyevent ────────────────────────────────────────────────
    private fun doKeyEvent(code: Int): JSONObject = try {
        Runtime.getRuntime().exec(arrayOf("input", "keyevent", code.toString())).waitFor()
        ok()
    } catch (e: Exception) { err("keyevent failed: ${e.message}") }

    // ── open_settings ───────────────────────────────────────────
    private fun doOpenSettings(setting: String): JSONObject {
        val action = when (setting) {
            "wifi"          -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth"     -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display"       -> Settings.ACTION_DISPLAY_SETTINGS
            "sound"         -> Settings.ACTION_SOUND_SETTINGS
            "battery"       -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location"      -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps"          -> Settings.ACTION_APPLICATION_SETTINGS
            "date"          -> Settings.ACTION_DATE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "developer"     -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "dnd"           -> "android.settings.ZEN_MODE_SETTINGS"
            "network"       -> Settings.ACTION_WIRELESS_SETTINGS
            "storage"       -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "security"      -> Settings.ACTION_SECURITY_SETTINGS
            else            -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try { service.startActivity(intent); ok() }
        catch (e: Exception) { err("settings intent failed: ${e.message}") }
    }

    // ── wait ────────────────────────────────────────────────────
    private fun doWait(durationMs: Int): JSONObject {
        try { Thread.sleep(durationMs.toLong().coerceIn(0, 10_000)) } catch (_: InterruptedException) {}
        return ok()
    }

    // ── intent ──────────────────────────────────────────────────
    private fun doIntent(cmd: JSONObject): JSONObject {
        val intentAction = cmd.optString("intentAction").ifEmpty { return err("intentAction required") }
        val extras = cmd.optJSONObject("intentExtras")
        var parsedUri = cmd.optString("intentUri").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        val mimeType = cmd.optString("intentType").takeIf { it.isNotEmpty() }

        // mailto: encode subject/body into the URI — many email apps ignore extras
        if (parsedUri?.scheme == "mailto" && extras != null) {
            val subject = extras.optString("android.intent.extra.SUBJECT", "")
            val body    = extras.optString("android.intent.extra.TEXT", "")
            val baseEmail = parsedUri.schemeSpecificPart.split("?")[0]
            val params = mutableListOf<String>()
            if (subject.isNotEmpty()) params.add("subject=${Uri.encode(subject)}")
            if (body.isNotEmpty())    params.add("body=${Uri.encode(body)}")
            if (params.isNotEmpty()) parsedUri = Uri.parse("mailto:$baseEmail?${params.joinToString("&")}")
        }

        val intent = Intent(intentAction).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            when {
                parsedUri != null && mimeType != null -> setDataAndType(parsedUri, mimeType)
                parsedUri != null -> data = parsedUri
                mimeType != null  -> type = mimeType
            }
            cmd.optString("packageName").takeIf { it.isNotEmpty() }?.let { setPackage(it) }
            putJsonExtras(this, extras)
        }
        return try { service.startActivity(intent); ok() }
        catch (e: Exception) { err("intent failed: ${e.message}") }
    }

    private fun putJsonExtras(intent: Intent, extras: JSONObject?) {
        if (extras == null) return
        val keys = extras.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = extras.optString(k)
            val asInt  = v.toIntOrNull()
            val asLong = v.toLongOrNull()
            when {
                asInt  != null -> intent.putExtra(k, asInt)
                asLong != null -> intent.putExtra(k, asLong)
                else           -> intent.putExtra(k, v)
            }
        }
    }

    // ── screenshot (returns base64 JPEG in `data`) ──────────────
    private fun doScreenshot(): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return err("screenshot requires Android 11+")
        val latch = CountDownLatch(1)
        val exec  = Executors.newSingleThreadExecutor()
        var bitmap: Bitmap? = null
        var e: String? = null
        service.takeScreenshot(Display.DEFAULT_DISPLAY, exec,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        bitmap = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        try { hb.close() } catch (_: Exception) {}
                    } catch (ex: Exception) { e = "wrap failed: ${ex.message}" }
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) { e = "screenshot failed: $errorCode"; latch.countDown() }
            })
        latch.await(8, TimeUnit.SECONDS); exec.shutdown()
        val src = bitmap ?: return err(e ?: "no bitmap")
        return try {
            val maxDim = 1080
            val w = src.width; val h = src.height
            val scale = if (maxOf(w, h) > maxDim) maxDim.toFloat() / maxOf(w, h) else 1f
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
                src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true) else src
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            try { if (scaled !== src) scaled.recycle() } catch (_: Exception) {}
            try { src.recycle() } catch (_: Exception) {}
            ok(b64)
        } catch (ex: Exception) { err("encode failed: ${ex.message}") }
    }

    // ── helpers ─────────────────────────────────────────────────
    private fun tapGesture(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val durationMs = 90L + (Math.random() * 40).toLong()  // 90-130ms — human range; <80ms ignored by Chrome JS
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(g, null, null)
    }

    private fun dismissKeyboard() {
        try {
            val root = service.rootInActiveWindow ?: return
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                focused.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                try { focused.recycle() } catch (_: Exception) {}
                Thread.sleep(180)
            }
            try { root.recycle() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    // Find the deepest clickable node whose bounds contain (x,y). Falls back
    // to the deepest node containing the point if none are clickable.
    private fun findClickableAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (r.contains(x, y) && node.isClickable && node.isEnabled) {
                    val area = r.width().toLong() * r.height().toLong()
                    if (area < bestArea) { bestArea = area; best = node }
                }
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    visit(c)
                    if (c !== best) try { c.recycle() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        visit(root)
        return best
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = findFocusedEditable(c)
            if (found != null) { if (found !== c) try { c.recycle() } catch (_: Exception) {}; return found }
            try { c.recycle() } catch (_: Exception) {}
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = findEditable(c)
            if (found != null) { if (found !== c) try { c.recycle() } catch (_: Exception) {}; return found }
            try { c.recycle() } catch (_: Exception) {}
        }
        return null
    }
}
