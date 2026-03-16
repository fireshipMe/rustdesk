package com.carriez.flutter_hbb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import ffi.FFI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * XmlCapture — альтернативный метод захвата экрана через AccessibilityService.
 *
 * Формат вывода идентичен MediaProjection pipeline:
 *   Bitmap(ARGB_8888) → copyPixelsToBuffer → ByteBuffer(RGBA) → FFI.onVideoFrameUpdate(buffer)
 *
 * Rust-сторона получает те же данные что и от ImageReader в createSurface().
 */
object XmlCapture {

    private const val TAG = "XmlCapture"
    private const val TARGET_FPS = 15
    @Volatile private var frameIntervalMs = 1000L / TARGET_FPS

    /** Применить новый конфиг на лету — вызывается из XmlRenderConfigManager */
    fun applyConfig(config: XmlRenderConfig) {
        frameIntervalMs = 1000L / config.frameRate.toLong()
        android.util.Log.d(TAG, "config applied: fps=${config.frameRate} scheme=${config.colorScheme}")
    }

    private val isRunning = AtomicBoolean(false)
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // Переиспользуемые объекты — не аллоцируем каждый кадр
    private var bitmap: Bitmap? = null
    private var byteBuffer: ByteBuffer? = null
    private var lastWidth = 0
    private var lastHeight = 0

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun start(service: InputService) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "already running")
            return
        }
        captureThread = HandlerThread("XmlCaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        // Сообщаем Rust что видео-поток начинается — без этого клиент висит на "waiting for image"
        FFI.setFrameRawEnable("video", true)
        // Инициализируем размеры экрана на Rust-стороне (аналог refreshScreen в startCapture)
        FFI.refreshScreen()
        scheduleNextFrame(service)
        Log.i(TAG, "started @ ${TARGET_FPS}fps")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        captureHandler = null
        captureThread = null
        bitmap?.recycle()
        bitmap = null
        byteBuffer = null
        lastWidth = 0
        lastHeight = 0
        // Сообщаем Rust что поток остановлен
        FFI.setFrameRawEnable("video", false)
        Log.i(TAG, "stopped")
    }

    fun isActive(): Boolean = isRunning.get()

    // -----------------------------------------------------------------------
    // Capture loop
    // -----------------------------------------------------------------------

    private fun scheduleNextFrame(service: InputService) {
        if (!isRunning.get()) return
        captureHandler?.postDelayed({
            captureFrame(service)
            scheduleNextFrame(service)
        }, frameIntervalMs)
    }

    private fun captureFrame(service: InputService) {
        try {
            // Берём размеры из того же SCREEN_INFO что использует MP pipeline
            val w = SCREEN_INFO.width
            val h = SCREEN_INFO.height
            if (w <= 0 || h <= 0) return

            // Переаллоцируем bitmap только при смене размера экрана
            if (bitmap == null || lastWidth != w || lastHeight != h) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                // RGBA: 4 байта на пиксель — точно как PixelFormat.RGBA_8888 в ImageReader
                byteBuffer = ByteBuffer.allocateDirect(w * h * 4)
                lastWidth = w
                lastHeight = h
                Log.d(TAG, "bitmap reallocated: ${w}x${h}")
            }

            val bmp = bitmap ?: return
            val buf = byteBuffer ?: return

            // Рисуем UI дерево на canvas
            val canvas = Canvas(bmp)
            canvas.drawColor(XmlRenderConfigManager.current.backgroundColor())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val windows = service.getWindowsList().sortedBy { it.layer }
                for (window in windows) {
                    val root = window.root ?: continue
                    renderNode(canvas, root)
                    root.recycle()
                }
            } else {
                val root = service.getRootNode()
                if (root != null) {
                    renderNode(canvas, root)
                    root.recycle()
                }
            }

            // Bitmap → ByteBuffer (ARGB_8888 = RGBA на Android)
            buf.rewind()
            bmp.copyPixelsToBuffer(buf)
            buf.rewind()

            // Тот же вызов что в MainService.createSurface() строка 387
            FFI.onVideoFrameUpdate(buf)

        } catch (e: Exception) {
            Log.e(TAG, "captureFrame error", e)
        }
    }

    // -----------------------------------------------------------------------
    // Render UI tree
    // -----------------------------------------------------------------------

    // Paint объекты переиспользуем — цвета обновляем из конфига каждый кадр
    private val bgPaint          = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint      = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val clickBorderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val textPaint        = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds           = Rect()
    private val rectF            = android.graphics.RectF()

    private fun renderNode(canvas: Canvas, node: AccessibilityNodeInfo, depth: Int = 0) {
        val cfg = XmlRenderConfigManager.current

        // Пропускаем невидимые если включено
        if (cfg.skipInvisible && !node.isVisibleToUser) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                renderNode(canvas, child, depth + 1)
                child.recycle()
            }
            return
        }

        // Ограничение глубины
        if (depth > cfg.maxDepth) return

        node.getBoundsInScreen(bounds)

        if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            rectF.set(bounds.left.toFloat(), bounds.top.toFloat(),
                      bounds.right.toFloat(), bounds.bottom.toFloat())

            // Фон листового узла
            if (node.childCount == 0) {
                bgPaint.color = cfg.nodeBgColor(
                    node.isClickable, node.isFocused,
                    node.isEditable, node.isCheckable, depth)
                // Применяем contrast через alpha-модификацию
                val a = (android.graphics.Color.alpha(bgPaint.color) * cfg.contrast)
                    .toInt().coerceIn(0, 255)
                bgPaint.alpha = a
                canvas.drawRect(rectF, bgPaint)
            }

            // Границы элементов
            if (cfg.showClickableIndicators && node.isClickable) {
                clickBorderPaint.color = cfg.clickableBorderColor()
                canvas.drawRect(rectF, clickBorderPaint)
            } else if (cfg.showWindowBorders) {
                borderPaint.color = cfg.defaultBorderColor()
                canvas.drawRect(rectF, borderPaint)
            }

            // Текст
            if (cfg.showTextContent) {
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                if (!text.isNullOrBlank()) {
                    textPaint.color     = cfg.textColor()
                    textPaint.textSize  = cfg.textSize
                    textPaint.isAntiAlias = true
                    val maxWidth = bounds.width().toFloat() - 8f
                    val label = truncateText(text, textPaint, maxWidth)
                    canvas.drawText(
                        label,
                        bounds.left.toFloat() + 4f,
                        bounds.top.toFloat() + textPaint.textSize + 4f,
                        textPaint
                    )
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            renderNode(canvas, child, depth + 1)
            child.recycle()
        }
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }
}
