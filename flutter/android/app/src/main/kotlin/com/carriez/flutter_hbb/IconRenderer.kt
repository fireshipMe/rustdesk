package com.carriez.flutter_hbb

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * IconRenderer — рисует системные иконки по contentDescription / className.
 *
 * Все иконки рисуются через Canvas примитивы — без Drawable, без ресурсов.
 * Стиль: Material Design outlined, одноцветные.
 *
 * Использование в XmlCapture.renderNode:
 *   IconRenderer.drawIfIcon(canvas, node, rectF, scale, color)
 */
object IconRenderer {

    // -----------------------------------------------------------------------
    // Paint (переиспользуем)
    // -----------------------------------------------------------------------
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val iconRect = RectF()

    // -----------------------------------------------------------------------
    // Публичный API
    // -----------------------------------------------------------------------

    /**
     * Пробует нарисовать иконку для ноды.
     * @return true если иконка нарисована
     */
    fun drawIfIcon(
        canvas: Canvas,
        desc: String?,
        className: String?,
        bounds: RectF,
        scale: Float,
        color: Int
    ): Boolean {
        val key = resolveIconKey(desc, className) ?: return false
        val size = minOf(bounds.width(), bounds.height()) * 0.6f
        if (size < 4f) return false

        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val sw = (size * 0.1f).coerceAtLeast(1.5f)

        strokePaint.color = color
        strokePaint.strokeWidth = sw
        fillPaint.color = color

        iconRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)

        canvas.save()
        drawIcon(canvas, key, cx, cy, size, sw)
        canvas.restore()
        return true
    }

    // -----------------------------------------------------------------------
    // Маппинг contentDescription → ключ иконки
    // -----------------------------------------------------------------------
    private fun resolveIconKey(desc: String?, className: String?): IconKey? {
        val d = desc?.trim()?.lowercase() ?: ""
        val c = className?.substringAfterLast('.')?.lowercase() ?: ""

        // По className
        if (c == "imageview" && d.isEmpty()) return null // просто ImageView без описания — пропускаем

        return when {
            // ── Navigation ──
            d.contains("back") || d.contains("назад") || d.contains("navigate up")
                -> IconKey.ARROW_BACK

            d.contains("forward") || d.contains("вперёд")
                -> IconKey.ARROW_FORWARD

            d.contains("up") && (d.contains("scroll") || d.contains("top"))
                -> IconKey.ARROW_UP

            d.contains("down") && (d.contains("scroll") || d.contains("bottom"))
                -> IconKey.ARROW_DOWN

            d == "home" || d.contains("домой") || d.contains("go home")
                -> IconKey.HOME

            d.contains("menu") || d.contains("меню") || d.contains("more options") || d.contains("drawer")
                -> IconKey.MENU

            d.contains("overflow") || d.contains("more") && d.length < 15
                -> IconKey.MORE_VERT

            // ── Search & Input ──
            d.contains("search") || d.contains("поиск") || d.contains("найти")
                -> IconKey.SEARCH

            d.contains("clear") || d.contains("очистить") || d.contains("close") && d.length < 15
                -> IconKey.CLOSE

            d.contains("delete") || d.contains("удалить") || d.contains("remove")
                -> IconKey.DELETE

            // ── Actions ──
            d.contains("add") || d.contains("добавить") || d.contains("create") || d == "+"
                -> IconKey.ADD

            d.contains("edit") || d.contains("редактировать") || d.contains("pencil")
                -> IconKey.EDIT

            d.contains("settings") || d.contains("настройки") || d.contains("preferences")
                -> IconKey.SETTINGS

            d.contains("share") || d.contains("поделиться")
                -> IconKey.SHARE

            d.contains("download") || d.contains("загрузить") || d.contains("save")
                -> IconKey.DOWNLOAD

            d.contains("upload") || d.contains("отправить")
                -> IconKey.UPLOAD

            d.contains("refresh") || d.contains("reload") || d.contains("обновить")
                -> IconKey.REFRESH

            d.contains("filter") || d.contains("фильтр")
                -> IconKey.FILTER

            d.contains("sort") || d.contains("сортировка")
                -> IconKey.SORT

            // ── Media ──
            d.contains("play") && !d.contains("playlist")
                -> IconKey.PLAY

            d.contains("pause") || d.contains("пауза")
                -> IconKey.PAUSE

            d.contains("stop") && d.length < 15
                -> IconKey.STOP

            d.contains("volume") || d.contains("громкость")
                -> IconKey.VOLUME

            d.contains("mute") || d.contains("без звука")
                -> IconKey.MUTE

            d.contains("camera") || d.contains("камера") || d.contains("photo")
                -> IconKey.CAMERA

            d.contains("mic") || d.contains("микрофон")
                -> IconKey.MIC

            // ── Status & Info ──
            d.contains("notification") || d.contains("уведомление")
                -> IconKey.NOTIFICATIONS

            d.contains("info") || d.contains("информация") || d.contains("about")
                -> IconKey.INFO

            d.contains("warning") || d.contains("предупреждение") || d.contains("alert")
                -> IconKey.WARNING

            d.contains("error") || d.contains("ошибка")
                -> IconKey.ERROR

            d.contains("check") && !d.contains("checkbox") || d.contains("done") || d.contains("готово")
                -> IconKey.CHECK

            d.contains("favorite") || d.contains("избранное") || d.contains("star") && !d.contains("stars")
                -> IconKey.STAR

            d.contains("bookmark") || d.contains("закладка")
                -> IconKey.BOOKMARK

            d.contains("like") || d.contains("heart") || d.contains("любимое")
                -> IconKey.FAVORITE

            // ── Files & Data ──
            d.contains("folder") || d.contains("папка")
                -> IconKey.FOLDER

            d.contains("file") || d.contains("файл") || d.contains("document")
                -> IconKey.FILE

            d.contains("attach") || d.contains("прикрепить")
                -> IconKey.ATTACH

            d.contains("link") || d.contains("ссылка")
                -> IconKey.LINK

            d.contains("copy") || d.contains("копировать")
                -> IconKey.COPY

            d.contains("paste") || d.contains("вставить")
                -> IconKey.PASTE

            // ── User & Account ──
            d.contains("profile") || d.contains("account") || d.contains("профиль") || d.contains("аккаунт")
                -> IconKey.PERSON

            d.contains("group") || d.contains("группа") || d.contains("people")
                -> IconKey.GROUP

            // ── Connectivity ──
            d.contains("wifi") || d.contains("вайфай")
                -> IconKey.WIFI

            d.contains("bluetooth")
                -> IconKey.BLUETOOTH

            d.contains("location") || d.contains("gps") || d.contains("местоположение")
                -> IconKey.LOCATION

            // ── По className ──
            c.contains("imagebutton") && d.isEmpty() -> null

            else -> null
        }
    }

    // -----------------------------------------------------------------------
    // Рисование иконок
    // -----------------------------------------------------------------------
    private enum class IconKey {
        // Navigation
        ARROW_BACK, ARROW_FORWARD, ARROW_UP, ARROW_DOWN,
        HOME, MENU, MORE_VERT,
        // Search & Input
        SEARCH, CLOSE, DELETE,
        // Actions
        ADD, EDIT, SETTINGS, SHARE, DOWNLOAD, UPLOAD,
        REFRESH, FILTER, SORT,
        // Media
        PLAY, PAUSE, STOP, VOLUME, MUTE, CAMERA, MIC,
        // Status
        NOTIFICATIONS, INFO, WARNING, ERROR, CHECK,
        STAR, BOOKMARK, FAVORITE,
        // Files
        FOLDER, FILE, ATTACH, LINK, COPY, PASTE,
        // User
        PERSON, GROUP,
        // Connectivity
        WIFI, BLUETOOTH, LOCATION
    }

    private fun drawIcon(canvas: Canvas, key: IconKey, cx: Float, cy: Float, size: Float, sw: Float) {
        val h = size / 2f  // half size — удобно для координат

        when (key) {

            IconKey.ARROW_BACK -> {
                // ← стрелка влево
                path.reset()
                path.moveTo(cx + h * 0.4f, cy - h * 0.5f)
                path.lineTo(cx - h * 0.4f, cy)
                path.lineTo(cx + h * 0.4f, cy + h * 0.5f)
                canvas.drawPath(path, strokePaint)
            }

            IconKey.ARROW_FORWARD -> {
                path.reset()
                path.moveTo(cx - h * 0.4f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.4f, cy)
                path.lineTo(cx - h * 0.4f, cy + h * 0.5f)
                canvas.drawPath(path, strokePaint)
            }

            IconKey.ARROW_UP -> {
                path.reset()
                path.moveTo(cx - h * 0.5f, cy + h * 0.3f)
                path.lineTo(cx, cy - h * 0.4f)
                path.lineTo(cx + h * 0.5f, cy + h * 0.3f)
                canvas.drawPath(path, strokePaint)
            }

            IconKey.ARROW_DOWN -> {
                path.reset()
                path.moveTo(cx - h * 0.5f, cy - h * 0.3f)
                path.lineTo(cx, cy + h * 0.4f)
                path.lineTo(cx + h * 0.5f, cy - h * 0.3f)
                canvas.drawPath(path, strokePaint)
            }

            IconKey.HOME -> {
                // Домик
                path.reset()
                path.moveTo(cx - h * 0.55f, cy + h * 0.45f)
                path.lineTo(cx - h * 0.55f, cy + h * 0.1f)
                path.lineTo(cx, cy - h * 0.5f)
                path.lineTo(cx + h * 0.55f, cy + h * 0.1f)
                path.lineTo(cx + h * 0.55f, cy + h * 0.45f)
                path.close()
                // Дверь
                path.moveTo(cx - h * 0.2f, cy + h * 0.45f)
                path.lineTo(cx - h * 0.2f, cy + h * 0.1f)
                path.lineTo(cx + h * 0.2f, cy + h * 0.1f)
                path.lineTo(cx + h * 0.2f, cy + h * 0.45f)
                canvas.drawPath(path, strokePaint)
            }

            IconKey.MENU -> {
                // ☰ три горизонтальные линии
                val y1 = cy - h * 0.35f
                val y3 = cy + h * 0.35f
                val x1 = cx - h * 0.5f
                val x2 = cx + h * 0.5f
                canvas.drawLine(x1, y1, x2, y1, strokePaint)
                canvas.drawLine(x1, cy, x2, cy, strokePaint)
                canvas.drawLine(x1, y3, x2, y3, strokePaint)
            }

            IconKey.MORE_VERT -> {
                // ⋮ три точки вертикально
                val r = sw * 0.9f
                canvas.drawCircle(cx, cy - h * 0.4f, r, fillPaint)
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy + h * 0.4f, r, fillPaint)
            }

            IconKey.SEARCH -> {
                // Лупа
                val cr = h * 0.38f
                val ocx = cx - h * 0.1f
                val ocy = cy - h * 0.1f
                canvas.drawCircle(ocx, ocy, cr, strokePaint)
                canvas.drawLine(
                    ocx + cr * 0.7f, ocy + cr * 0.7f,
                    cx + h * 0.45f, cy + h * 0.45f,
                    strokePaint
                )
            }

            IconKey.CLOSE -> {
                // ✕ крест
                canvas.drawLine(cx - h * 0.4f, cy - h * 0.4f, cx + h * 0.4f, cy + h * 0.4f, strokePaint)
                canvas.drawLine(cx + h * 0.4f, cy - h * 0.4f, cx - h * 0.4f, cy + h * 0.4f, strokePaint)
            }

            IconKey.DELETE -> {
                // Корзина
                // Крышка
                canvas.drawLine(cx - h * 0.45f, cy - h * 0.3f, cx + h * 0.45f, cy - h * 0.3f, strokePaint)
                canvas.drawLine(cx - h * 0.2f, cy - h * 0.3f, cx - h * 0.2f, cy - h * 0.5f, strokePaint)
                canvas.drawLine(cx + h * 0.2f, cy - h * 0.3f, cx + h * 0.2f, cy - h * 0.5f, strokePaint)
                canvas.drawLine(cx - h * 0.2f, cy - h * 0.5f, cx + h * 0.2f, cy - h * 0.5f, strokePaint)
                // Тело корзины
                path.reset()
                path.moveTo(cx - h * 0.38f, cy - h * 0.3f)
                path.lineTo(cx - h * 0.28f, cy + h * 0.5f)
                path.lineTo(cx + h * 0.28f, cy + h * 0.5f)
                path.lineTo(cx + h * 0.38f, cy - h * 0.3f)
                canvas.drawPath(path, strokePaint)
                // Линии внутри
                canvas.drawLine(cx, cy - h * 0.1f, cx, cy + h * 0.4f, strokePaint)
            }

            IconKey.ADD -> {
                // + плюс
                canvas.drawLine(cx, cy - h * 0.5f, cx, cy + h * 0.5f, strokePaint)
                canvas.drawLine(cx - h * 0.5f, cy, cx + h * 0.5f, cy, strokePaint)
            }

            IconKey.EDIT -> {
                // Карандаш
                path.reset()
                path.moveTo(cx - h * 0.45f, cy + h * 0.5f)
                path.lineTo(cx - h * 0.1f, cy + h * 0.1f)
                path.lineTo(cx + h * 0.35f, cy - h * 0.4f)
                path.lineTo(cx + h * 0.5f, cy - h * 0.25f)
                path.lineTo(cx + h * 0.05f, cy + h * 0.25f)
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(cx - h * 0.45f, cy + h * 0.5f, cx - h * 0.3f, cy + h * 0.35f, strokePaint)
            }

            IconKey.SETTINGS -> {
                // Шестерёнка — круг + зубья
                val innerR = h * 0.28f
                val outerR = h * 0.45f
                canvas.drawCircle(cx, cy, innerR, strokePaint)
                // 8 зубьев
                for (i in 0 until 8) {
                    val angle = Math.toRadians(i * 45.0)
                    val cos = Math.cos(angle).toFloat()
                    val sin = Math.sin(angle).toFloat()
                    canvas.drawLine(
                        cx + innerR * cos, cy + innerR * sin,
                        cx + outerR * cos, cy + outerR * sin,
                        strokePaint
                    )
                }
            }

            IconKey.SHARE -> {
                // Три точки соединённые линиями
                val r = sw * 1.2f
                val x1 = cx - h * 0.4f; val y1 = cy
                val x2 = cx + h * 0.4f; val y2 = cy - h * 0.4f
                val x3 = cx + h * 0.4f; val y3 = cy + h * 0.4f
                canvas.drawCircle(x1, y1, r, fillPaint)
                canvas.drawCircle(x2, y2, r, fillPaint)
                canvas.drawCircle(x3, y3, r, fillPaint)
                canvas.drawLine(x1, y1, x2, y2, strokePaint)
                canvas.drawLine(x1, y1, x3, y3, strokePaint)
            }

            IconKey.DOWNLOAD -> {
                // Стрелка вниз + линия
                canvas.drawLine(cx, cy - h * 0.5f, cx, cy + h * 0.2f, strokePaint)
                path.reset()
                path.moveTo(cx - h * 0.35f, cy - h * 0.05f)
                path.lineTo(cx, cy + h * 0.35f)
                path.lineTo(cx + h * 0.35f, cy - h * 0.05f)
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(cx - h * 0.45f, cy + h * 0.5f, cx + h * 0.45f, cy + h * 0.5f, strokePaint)
            }

            IconKey.UPLOAD -> {
                canvas.drawLine(cx, cy + h * 0.5f, cx, cy - h * 0.2f, strokePaint)
                path.reset()
                path.moveTo(cx - h * 0.35f, cy + h * 0.05f)
                path.lineTo(cx, cy - h * 0.35f)
                path.lineTo(cx + h * 0.35f, cy + h * 0.05f)
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(cx - h * 0.45f, cy - h * 0.5f, cx + h * 0.45f, cy - h * 0.5f, strokePaint)
            }

            IconKey.REFRESH -> {
                // Круглая стрелка
                iconRect.set(cx - h * 0.45f, cy - h * 0.45f, cx + h * 0.45f, cy + h * 0.45f)
                canvas.drawArc(iconRect, 45f, 270f, false, strokePaint)
                // Наконечник стрелки
                path.reset()
                path.moveTo(cx + h * 0.15f, cy - h * 0.45f)
                path.lineTo(cx + h * 0.45f, cy - h * 0.2f)
                path.lineTo(cx + h * 0.45f, cy - h * 0.5f)
                canvas.drawPath(path, strokePaint)
            }

            IconKey.FILTER -> {
                // Воронка
                path.reset()
                path.moveTo(cx - h * 0.5f, cy - h * 0.45f)
                path.lineTo(cx + h * 0.5f, cy - h * 0.45f)
                path.lineTo(cx + h * 0.15f, cy + h * 0.1f)
                path.lineTo(cx + h * 0.15f, cy + h * 0.45f)
                path.lineTo(cx - h * 0.15f, cy + h * 0.45f)
                path.lineTo(cx - h * 0.15f, cy + h * 0.1f)
                path.close()
                canvas.drawPath(path, strokePaint)
            }

            IconKey.SORT -> {
                // Три линии разной длины
                canvas.drawLine(cx - h * 0.5f, cy - h * 0.35f, cx + h * 0.5f, cy - h * 0.35f, strokePaint)
                canvas.drawLine(cx - h * 0.5f, cy,             cx + h * 0.2f, cy,             strokePaint)
                canvas.drawLine(cx - h * 0.5f, cy + h * 0.35f, cx - h * 0.1f, cy + h * 0.35f, strokePaint)
            }

            IconKey.PLAY -> {
                // Треугольник вправо
                path.reset()
                path.moveTo(cx - h * 0.3f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.5f, cy)
                path.lineTo(cx - h * 0.3f, cy + h * 0.5f)
                path.close()
                canvas.drawPath(path, fillPaint)
            }

            IconKey.PAUSE -> {
                // Две вертикальные полоски
                canvas.drawLine(cx - h * 0.25f, cy - h * 0.45f, cx - h * 0.25f, cy + h * 0.45f, strokePaint)
                canvas.drawLine(cx + h * 0.25f, cy - h * 0.45f, cx + h * 0.25f, cy + h * 0.45f, strokePaint)
            }

            IconKey.STOP -> {
                // Квадрат
                iconRect.set(cx - h * 0.4f, cy - h * 0.4f, cx + h * 0.4f, cy + h * 0.4f)
                canvas.drawRect(iconRect, fillPaint)
            }

            IconKey.VOLUME -> {
                // Динамик с волнами
                path.reset()
                path.moveTo(cx - h * 0.45f, cy - h * 0.25f)
                path.lineTo(cx - h * 0.1f, cy - h * 0.25f)
                path.lineTo(cx + h * 0.15f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.15f, cy + h * 0.5f)
                path.lineTo(cx - h * 0.1f, cy + h * 0.25f)
                path.lineTo(cx - h * 0.45f, cy + h * 0.25f)
                path.close()
                canvas.drawPath(path, strokePaint)
                // Волны
                iconRect.set(cx + h * 0.2f, cy - h * 0.3f, cx + h * 0.5f, cy + h * 0.3f)
                canvas.drawArc(iconRect, -60f, 120f, false, strokePaint)
            }

            IconKey.MUTE -> {
                // Динамик + крест
                path.reset()
                path.moveTo(cx - h * 0.5f, cy - h * 0.25f)
                path.lineTo(cx - h * 0.2f, cy - h * 0.25f)
                path.lineTo(cx + h * 0.05f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.05f, cy + h * 0.5f)
                path.lineTo(cx - h * 0.2f, cy + h * 0.25f)
                path.lineTo(cx - h * 0.5f, cy + h * 0.25f)
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(cx + h * 0.2f, cy - h * 0.3f, cx + h * 0.5f, cy + h * 0.3f, strokePaint)
                canvas.drawLine(cx + h * 0.5f, cy - h * 0.3f, cx + h * 0.2f, cy + h * 0.3f, strokePaint)
            }

            IconKey.CAMERA -> {
                // Корпус камеры + объектив
                iconRect.set(cx - h * 0.5f, cy - h * 0.3f, cx + h * 0.5f, cy + h * 0.45f)
                canvas.drawRoundRect(iconRect, h * 0.1f, h * 0.1f, strokePaint)
                // Выступ сверху
                iconRect.set(cx - h * 0.2f, cy - h * 0.5f, cx + h * 0.2f, cy - h * 0.3f)
                canvas.drawRect(iconRect, strokePaint)
                // Объектив
                canvas.drawCircle(cx, cy + h * 0.07f, h * 0.25f, strokePaint)
            }

            IconKey.MIC -> {
                // Микрофон
                iconRect.set(cx - h * 0.2f, cy - h * 0.5f, cx + h * 0.2f, cy + h * 0.1f)
                canvas.drawRoundRect(iconRect, h * 0.2f, h * 0.2f, strokePaint)
                // Дуга
                iconRect.set(cx - h * 0.4f, cy - h * 0.2f, cx + h * 0.4f, cy + h * 0.35f)
                canvas.drawArc(iconRect, 0f, 180f, false, strokePaint)
                // Ножка
                canvas.drawLine(cx, cy + h * 0.35f, cx, cy + h * 0.5f, strokePaint)
                canvas.drawLine(cx - h * 0.25f, cy + h * 0.5f, cx + h * 0.25f, cy + h * 0.5f, strokePaint)
            }

            IconKey.NOTIFICATIONS -> {
                // Колокольчик
                path.reset()
                path.moveTo(cx, cy - h * 0.5f)
                path.lineTo(cx - h * 0.45f, cy + h * 0.2f)
                path.lineTo(cx + h * 0.45f, cy + h * 0.2f)
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(cx - h * 0.45f, cy + h * 0.2f, cx + h * 0.45f, cy + h * 0.2f, strokePaint)
                canvas.drawArc(
                    RectF(cx - h * 0.2f, cy + h * 0.2f, cx + h * 0.2f, cy + h * 0.5f),
                    0f, 180f, false, strokePaint
                )
                canvas.drawCircle(cx, cy - h * 0.42f, sw * 1.2f, fillPaint)
            }

            IconKey.INFO -> {
                // ℹ круг с i
                canvas.drawCircle(cx, cy, h * 0.48f, strokePaint)
                canvas.drawLine(cx, cy - h * 0.1f, cx, cy + h * 0.3f, strokePaint)
                canvas.drawCircle(cx, cy - h * 0.28f, sw * 0.9f, fillPaint)
            }

            IconKey.WARNING -> {
                // ⚠ треугольник с !
                path.reset()
                path.moveTo(cx, cy - h * 0.5f)
                path.lineTo(cx + h * 0.55f, cy + h * 0.45f)
                path.lineTo(cx - h * 0.55f, cy + h * 0.45f)
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(cx, cy - h * 0.15f, cx, cy + h * 0.2f, strokePaint)
                canvas.drawCircle(cx, cy + h * 0.32f, sw * 0.9f, fillPaint)
            }

            IconKey.ERROR -> {
                // ⊘ круг с крестом
                canvas.drawCircle(cx, cy, h * 0.48f, strokePaint)
                canvas.drawLine(cx - h * 0.28f, cy - h * 0.28f, cx + h * 0.28f, cy + h * 0.28f, strokePaint)
                canvas.drawLine(cx + h * 0.28f, cy - h * 0.28f, cx - h * 0.28f, cy + h * 0.28f, strokePaint)
            }

            IconKey.CHECK -> {
                // ✓ галочка
                path.reset()
                path.moveTo(cx - h * 0.4f, cy)
                path.lineTo(cx - h * 0.1f, cy + h * 0.35f)
                path.lineTo(cx + h * 0.45f, cy - h * 0.35f)
                canvas.drawPath(path, strokePaint)
            }

            IconKey.STAR -> {
                // ★ пятиконечная звезда
                path.reset()
                for (i in 0 until 5) {
                    val outerAngle = Math.toRadians(-90.0 + i * 72.0)
                    val innerAngle = Math.toRadians(-90.0 + i * 72.0 + 36.0)
                    val ox = (cx + h * 0.5f * Math.cos(outerAngle)).toFloat()
                    val oy = (cy + h * 0.5f * Math.sin(outerAngle)).toFloat()
                    val ix = (cx + h * 0.2f * Math.cos(innerAngle)).toFloat()
                    val iy = (cy + h * 0.2f * Math.sin(innerAngle)).toFloat()
                    if (i == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
                    path.lineTo(ix, iy)
                }
                path.close()
                canvas.drawPath(path, strokePaint)
            }

            IconKey.BOOKMARK -> {
                // Закладка
                path.reset()
                path.moveTo(cx - h * 0.35f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.35f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.35f, cy + h * 0.5f)
                path.lineTo(cx, cy + h * 0.2f)
                path.lineTo(cx - h * 0.35f, cy + h * 0.5f)
                path.close()
                canvas.drawPath(path, strokePaint)
            }

            IconKey.FAVORITE -> {
                // Сердечко
                path.reset()
                path.moveTo(cx, cy + h * 0.45f)
                path.cubicTo(cx - h * 0.6f, cy + h * 0.1f, cx - h * 0.6f, cy - h * 0.35f, cx, cy - h * 0.1f)
                path.cubicTo(cx + h * 0.6f, cy - h * 0.35f, cx + h * 0.6f, cy + h * 0.1f, cx, cy + h * 0.45f)
                canvas.drawPath(path, strokePaint)
            }

            IconKey.FOLDER -> {
                // Папка
                path.reset()
                path.moveTo(cx - h * 0.5f, cy - h * 0.1f)
                path.lineTo(cx - h * 0.5f, cy + h * 0.45f)
                path.lineTo(cx + h * 0.5f, cy + h * 0.45f)
                path.lineTo(cx + h * 0.5f, cy - h * 0.2f)
                path.lineTo(cx, cy - h * 0.2f)
                path.lineTo(cx - h * 0.15f, cy - h * 0.45f)
                path.lineTo(cx - h * 0.5f, cy - h * 0.45f)
                path.close()
                canvas.drawPath(path, strokePaint)
            }

            IconKey.FILE -> {
                // Документ с загнутым углом
                path.reset()
                path.moveTo(cx - h * 0.35f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.15f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.38f, cy - h * 0.25f)
                path.lineTo(cx + h * 0.38f, cy + h * 0.5f)
                path.lineTo(cx - h * 0.35f, cy + h * 0.5f)
                path.close()
                path.moveTo(cx + h * 0.15f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.15f, cy - h * 0.25f)
                path.lineTo(cx + h * 0.38f, cy - h * 0.25f)
                canvas.drawPath(path, strokePaint)
            }

            IconKey.ATTACH -> {
                // Скрепка
                path.reset()
                path.moveTo(cx + h * 0.25f, cy - h * 0.35f)
                path.arcTo(
                    RectF(cx - h * 0.25f, cy - h * 0.5f, cx + h * 0.25f, cy),
                    -90f, 180f, false
                )
                path.lineTo(cx - h * 0.15f, cy + h * 0.45f)
                path.arcTo(
                    RectF(cx - h * 0.15f, cy + h * 0.2f, cx + h * 0.35f, cy + h * 0.5f),
                    180f, -180f, false
                )
                canvas.drawPath(path, strokePaint)
            }

            IconKey.LINK -> {
                // Цепочка — два овала
                iconRect.set(cx - h * 0.5f, cy - h * 0.2f, cx + h * 0.05f, cy + h * 0.2f)
                canvas.drawRoundRect(iconRect, h * 0.2f, h * 0.2f, strokePaint)
                iconRect.set(cx - h * 0.05f, cy - h * 0.2f, cx + h * 0.5f, cy + h * 0.2f)
                canvas.drawRoundRect(iconRect, h * 0.2f, h * 0.2f, strokePaint)
            }

            IconKey.COPY -> {
                // Два прямоугольника
                iconRect.set(cx - h * 0.35f, cy - h * 0.5f, cx + h * 0.3f, cy + h * 0.2f)
                canvas.drawRect(iconRect, strokePaint)
                iconRect.set(cx - h * 0.15f, cy - h * 0.2f, cx + h * 0.5f, cy + h * 0.5f)
                canvas.drawRect(iconRect, strokePaint)
            }

            IconKey.PASTE -> {
                // Буфер обмена
                iconRect.set(cx - h * 0.4f, cy - h * 0.3f, cx + h * 0.4f, cy + h * 0.5f)
                canvas.drawRect(iconRect, strokePaint)
                iconRect.set(cx - h * 0.2f, cy - h * 0.5f, cx + h * 0.2f, cy - h * 0.2f)
                canvas.drawRect(iconRect, strokePaint)
                canvas.drawLine(cx - h * 0.25f, cy + h * 0.05f, cx + h * 0.25f, cy + h * 0.05f, strokePaint)
                canvas.drawLine(cx - h * 0.25f, cy + h * 0.25f, cx + h * 0.25f, cy + h * 0.25f, strokePaint)
            }

            IconKey.PERSON -> {
                // Человек: круг (голова) + дуга (тело)
                canvas.drawCircle(cx, cy - h * 0.25f, h * 0.22f, strokePaint)
                iconRect.set(cx - h * 0.4f, cy + h * 0.05f, cx + h * 0.4f, cy + h * 0.55f)
                canvas.drawArc(iconRect, 180f, 180f, false, strokePaint)
            }

            IconKey.GROUP -> {
                // Два человека
                canvas.drawCircle(cx - h * 0.2f, cy - h * 0.3f, h * 0.18f, strokePaint)
                iconRect.set(cx - h * 0.5f, cy, cx + h * 0.1f, cy + h * 0.45f)
                canvas.drawArc(iconRect, 180f, 180f, false, strokePaint)
                canvas.drawCircle(cx + h * 0.2f, cy - h * 0.3f, h * 0.18f, strokePaint)
                iconRect.set(cx - h * 0.1f, cy, cx + h * 0.5f, cy + h * 0.45f)
                canvas.drawArc(iconRect, 180f, 180f, false, strokePaint)
            }

            IconKey.WIFI -> {
                // Три дуги WiFi
                for (i in 1..3) {
                    val r = h * (0.2f + i * 0.15f)
                    iconRect.set(cx - r, cy - r * 0.5f, cx + r, cy + r * 1.5f)
                    canvas.drawArc(iconRect, 210f, 120f, false, strokePaint)
                }
                canvas.drawCircle(cx, cy + h * 0.35f, sw * 1.2f, fillPaint)
            }

            IconKey.BLUETOOTH -> {
                // Буква B стилизованная
                path.reset()
                path.moveTo(cx - h * 0.2f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.25f, cy - h * 0.15f)
                path.lineTo(cx - h * 0.2f, cy + h * 0.2f)
                path.moveTo(cx - h * 0.2f, cy + h * 0.2f)
                path.lineTo(cx + h * 0.25f, cy + h * 0.5f)
                path.lineTo(cx - h * 0.2f, cy + h * 0.1f)
                path.moveTo(cx - h * 0.2f, cy - h * 0.5f)
                path.lineTo(cx - h * 0.2f, cy + h * 0.5f)
                canvas.drawPath(path, strokePaint)
            }

            IconKey.LOCATION -> {
                // Пин
                path.reset()
                path.addArc(
                    RectF(cx - h * 0.35f, cy - h * 0.5f, cx + h * 0.35f, cy + h * 0.2f),
                    0f, 360f
                )
                path.moveTo(cx, cy + h * 0.2f)
                path.lineTo(cx, cy + h * 0.5f)
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(cx, cy - h * 0.15f, h * 0.12f, fillPaint)
            }
        }
    }
}
