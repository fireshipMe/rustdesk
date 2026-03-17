package com.carriez.flutter_hbb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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

    private val bgPaint          = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint      = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val clickBorderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    // TextPaint нужен для StaticLayout
    private val textPaint        = TextPaint(Paint.ANTI_ALIAS_FLAG)
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

        if (depth > cfg.maxDepth) return

        node.getBoundsInScreen(bounds)

        if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            val scale = SCREEN_INFO.scale.toFloat()
            rectF.set(
                bounds.left / scale,
                bounds.top / scale,
                bounds.right / scale,
                bounds.bottom / scale
            )

            // Фон листового узла
            if (node.childCount == 0) {
                bgPaint.color = cfg.nodeBgColor(
                    node.isClickable, node.isFocused,
                    node.isEditable, node.isCheckable, depth)
                val a = (Color.alpha(bgPaint.color) * cfg.contrast).toInt().coerceIn(0, 255)
                bgPaint.alpha = a
                canvas.drawRect(rectF, bgPaint)
            }

            // Границы
            if (cfg.showClickableIndicators && node.isClickable) {
                clickBorderPaint.color = cfg.clickableBorderColor()
                canvas.drawRect(rectF, clickBorderPaint)
            } else if (cfg.showWindowBorders) {
                borderPaint.color = cfg.defaultBorderColor()
                canvas.drawRect(rectF, borderPaint)
            }

            // Текст — только в листовых нодах со своими bounds
            if (cfg.showTextContent && node.childCount == 0) {
                val text = node.text?.toString()?.trim()
                    ?: node.contentDescription?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    drawNodeText(canvas, text, rectF, cfg.textSize / scale, cfg.textColor())
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            renderNode(canvas, child, depth + 1)
            child.recycle()
        }
    }

    /**
     * Рисует текст внутри bounds ноды с правильным переносом строк и вертикальным центрированием.
     * Использует StaticLayout для многострочности.
     */
    private fun drawNodeText(
        canvas: Canvas,
        text: String,
        nodeBounds: android.graphics.RectF,
        textSize: Float,
        textColor: Int
    ) {
        val padding = textSize * 0.2f  // отступ пропорционален размеру текста
        val availableWidth = (nodeBounds.width() - padding * 2).toInt()
        val availableHeight = nodeBounds.height() - padding * 2

        if (availableWidth <= 0 || availableHeight <= 0) return

        textPaint.color    = textColor
        textPaint.textSize = textSize
        textPaint.isAntiAlias = true

        // StaticLayout — правильный перенос строк с соблюдением ширины
        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.1f)   // небольшой межстрочный интервал
                .setIncludePad(false)
                .setMaxLines(Int.MAX_VALUE)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text, textPaint, availableWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1.1f, 0f, false
            )
        }

        val textHeight = layout.height.toFloat()

        // Вертикальное центрирование — если текст влезает
        val topOffset = if (textHeight <= availableHeight) {
            padding + (availableHeight - textHeight) / 2f
        } else {
            padding  // текст больше bounds — рисуем с отступа, StaticLayout сам обрежет
        }

        canvas.save()
        // Clip чтобы текст не вылезал за bounds ноды
        canvas.clipRect(nodeBounds)
        canvas.translate(nodeBounds.left + padding, nodeBounds.top + topOffset)
        layout.draw(canvas)
        canvas.restore()
    }
}
