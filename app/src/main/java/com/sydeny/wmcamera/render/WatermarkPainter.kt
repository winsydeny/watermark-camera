package com.sydeny.wmcamera.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.sydeny.wmcamera.domain.CoordinateFormatter
import com.sydeny.wmcamera.domain.LocationStatus
import com.sydeny.wmcamera.domain.WatermarkConfig
import com.sydeny.wmcamera.domain.WatermarkCorner
import com.sydeny.wmcamera.domain.WatermarkData
import com.sydeny.wmcamera.domain.formatTimestampLine

/**
 * 计算好的水印布局。
 *
 * 刻意只用裸 float 而不是 `RectF`：android.jar 在 JVM 单元测试里是空实现，
 * `RectF.set()` 什么都不做，字段恒为 0，任何基于它的几何断言都是假的。
 * 纯数值才能在 JVM 上真跑出结果。
 *
 * 坐标约定：
 * - [left]/[top]/[right]/[bottom] 是水印背景块在整张图里的绝对位置
 * - [textOriginX]、[logoOffsetX/Y] 是相对背景块左上角的偏移
 * - [lineBaselines] 是相对背景块顶部的各行基线位置
 * - [logoSide] 为 0 表示没有 logo
 */
data class WatermarkLayout(
    val lines: List<String>,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val textSizePx: Float,
    val lineHeightPx: Float,
    val textOriginX: Float,
    val lineBaselines: FloatArray,
    val logoSide: Float = 0f,
    val logoOffsetX: Float = 0f,
    val logoOffsetY: Float = 0f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val hasLogo: Boolean get() = logoSide > 0f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WatermarkLayout) return false
        return left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom &&
            textSizePx == other.textSizePx && lines == other.lines
    }

    override fun hashCode(): Int {
        var result = left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        result = 31 * result + textSizePx.hashCode()
        result = 31 * result + lines.hashCode()
        return result
    }
}

/**
 * 水印绘制器。
 *
 * 这是整个 App 唯一的绘制入口，**不引用任何 CameraX 类型**，只往传入的
 * [Canvas] 上画。因此同一份代码既能画进 CameraX `OverlayEffect` 提供的
 * OpenGL Canvas，也能画进 [Bitmap] 的 Canvas。
 *
 * 所有尺寸都从 `widthPx` 按比例推导，不含任何绝对值——这样画在 1080px 的
 * 预览缓冲和 12000px 的成片上，视觉比例完全一致。
 */
class WatermarkPainter(
    private val logoProvider: (String) -> LogoImage? = { null },
    private val textMeasurer: ((text: String, textSize: Float) -> Float)? = null,
    private val timestampFormatter: (Long) -> String = ::formatTimestampLine,
    private val statusTextProvider: (LocationStatus) -> String,
) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        // 高分辨率成片（3000x4000）+ 大字号（~90px）时，默认的字形 hinting
        // 会把轮廓"贴齐像素网格"，边缘就出现肉眼可见的锯齿/毛刺；不同
        // ROM 的 hinting 强度还不一样，这就是"有些机型明显更糊/更糙"的根因。
        // 关掉 hinting、开启 subpixel 定位 + linear text，能让字形按浮点位置
        // 平滑渲染，边缘稳定。
        isSubpixelText = true
        isLinearText = true
        isDither = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            hinting = Paint.HINTING_OFF
        }
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val layoutCache = LayoutCache()

    private fun measureText(text: String, textSize: Float): Float {
        textMeasurer?.let { return it(text, textSize) }
        textPaint.textSize = textSize
        return textPaint.measureText(text)
    }

    /** 决定要画哪几行文字。不含 logo，logo 单独画。 */
    fun buildLines(config: WatermarkConfig, data: WatermarkData): List<String> {
        if (!config.enabled) return emptyList()
        val lines = ArrayList<String>(config.enabledLines)
        if (config.showDateTime) {
            lines += timestampFormatter(data.timestampMillis)
        }
        if (config.showCoordinate) {
            lines += when (data.locationStatus) {
                LocationStatus.FIXED -> CoordinateFormatter.format(
                    latitude = requireNotNull(data.latitude),
                    longitude = requireNotNull(data.longitude),
                    accuracyMeters = data.accuracyMeters,
                )

                else -> statusTextProvider(data.locationStatus)
            }
        }
        if (config.showAddress) {
            val addr = data.address
            if (addr != null && addr.isNotBlank()) {
                lines += addr
            } else if (data.locationStatus != LocationStatus.FIXED) {
                lines += statusTextProvider(data.locationStatus)
            }
        }
        lines += config.visibleCustomLines
        return lines
    }

    fun measure(
        widthPx: Int,
        heightPx: Int,
        config: WatermarkConfig,
        data: WatermarkData,
        hasLogo: Boolean,
    ): WatermarkLayout? {
        val lines = buildLines(config, data)
        if (lines.isEmpty() && !hasLogo) return null

        val textSize = widthPx * BASE_TEXT_SIZE_RATIO * config.textScale
        val lineHeight = textSize * config.lineSpacing
        val padding = textSize * PADDING_RATIO
        val lineCount = maxOf(lines.size, 1)

        // 上面的文字要压在任何背景上，背景透明度低时补一层偏移阴影保证可读
        textPaint.textSize = textSize
        // 真机上 fontMetrics 从不为 null；留个兜底是为了能跑 JVM 单元测试
        val ascent = textPaint.fontMetrics?.let { -it.ascent } ?: textSize

        val textWidth = lines.maxOfOrNull { measureText(it, textSize) } ?: 0f
        val logoSide = if (hasLogo) lineHeight * lineCount * LOGO_HEIGHT_RATIO else 0f
        val logoGap = if (hasLogo) textSize * LOGO_GAP_RATIO else 0f

        val blockWidth = logoSide + logoGap + textWidth + padding * 2
        val blockHeight = lineHeight * lineCount + padding * 2

        val margin = widthPx * config.marginRatio
        val left = when (config.corner) {
            WatermarkCorner.TOP_LEFT, WatermarkCorner.BOTTOM_LEFT -> margin
            WatermarkCorner.TOP_RIGHT, WatermarkCorner.BOTTOM_RIGHT ->
                widthPx - margin - blockWidth
            // 居中：水平方向以图片中心对齐；块宽超过"图片宽 - 2*margin"时
            // 退化为贴边，避免溢出到画面外
            WatermarkCorner.TOP_CENTER, WatermarkCorner.BOTTOM_CENTER ->
                ((widthPx - blockWidth) / 2f).coerceAtLeast(margin)
        }
        val top = when (config.corner) {
            WatermarkCorner.TOP_LEFT, WatermarkCorner.TOP_CENTER, WatermarkCorner.TOP_RIGHT ->
                margin
            WatermarkCorner.BOTTOM_LEFT, WatermarkCorner.BOTTOM_CENTER, WatermarkCorner.BOTTOM_RIGHT ->
                heightPx - margin - blockHeight
        }

        val baselines = FloatArray(lineCount) { index ->
            padding + ascent + lineHeight * index
        }

        val logoOffsetY = if (hasLogo) (blockHeight - logoSide) / 2f else 0f

        return WatermarkLayout(
            lines = lines,
            left = left,
            top = top,
            right = left + blockWidth,
            bottom = top + blockHeight,
            textSizePx = textSize,
            lineHeightPx = lineHeight,
            textOriginX = padding + logoSide + logoGap,
            lineBaselines = baselines,
            logoSide = if (hasLogo) logoSide else 0f,
            logoOffsetX = padding,
            logoOffsetY = logoOffsetY,
        )
    }

    /**
     * 把水印画到 [canvas] 上。
     *
     * @param clearFirst 是否先擦一遍画布。OverlayEffect 每帧复用同一块纹理，
     *   不清空会残留上一帧的笔画，所以合成器路径下必须为 true；画进 Bitmap 时为 false。
     *   它是调用方的契约，因此即使没有可见内容也会执行——用户关掉水印后，
     *   屏幕上不能还留着上一帧的字。
     */
    fun draw(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        config: WatermarkConfig,
        data: WatermarkData,
        clearFirst: Boolean = false,
    ) {
        if (clearFirst) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
        if (widthPx <= 0 || heightPx <= 0 || !config.enabled) return

        val logo = config.logoFileName?.let(logoProvider)
        val layout = layoutCache.get(widthPx, config, data) {
            measure(widthPx, heightPx, config, data, hasLogo = logo != null)
        } ?: return

        drawBackground(canvas, layout, config)
        drawLogo(canvas, layout, logo)
        drawText(canvas, layout, config)
    }

    private fun drawBackground(
        canvas: Canvas,
        layout: WatermarkLayout,
        config: WatermarkConfig,
    ) {
        if (config.backgroundAlpha <= ALPHA_SKIP_THRESHOLD) return
        val alpha = (config.backgroundAlpha * 255f).toInt().coerceIn(0, 255)
        backgroundPaint.color = (config.backgroundColor and 0x00FFFFFF) or (alpha shl 24)
        val radius = layout.textSizePx * PADDING_RATIO
        canvas.drawRoundRect(layout.left, layout.top, layout.right, layout.bottom, radius, radius, backgroundPaint)
    }

    private fun drawLogo(canvas: Canvas, layout: WatermarkLayout, logo: LogoImage?) {
        if (!layout.hasLogo) return
        if (logo == null || logo.recycled) return
        logo.draw(
            canvas = canvas,
            left = (layout.left + layout.logoOffsetX).toInt(),
            top = (layout.top + layout.logoOffsetY).toInt(),
            right = (layout.left + layout.logoOffsetX + layout.logoSide).toInt(),
            bottom = (layout.top + layout.logoOffsetY + layout.logoSide).toInt(),
        )
    }

    private fun drawText(canvas: Canvas, layout: WatermarkLayout, config: WatermarkConfig) {
        if (layout.lines.isEmpty()) return
        val x = layout.left + layout.textOriginX

        // 背景几乎透明时，靠一层偏移的黑色副本保证文字在任何画面上都读得出来
        if (config.backgroundAlpha <= SHADOW_ALPHA_THRESHOLD) {
            textPaint.color = (0xFF000000.toInt() and 0x00FFFFFF) or (0.4f * 255).toInt() shl 24
            layout.lines.forEachIndexed { index, line ->
                canvas.drawText(
                    line,
                    x + layout.textSizePx * SHADOW_OFFSET_RATIO,
                    layout.top + layout.lineBaselines[index] +
                        layout.textSizePx * SHADOW_OFFSET_RATIO,
                    textPaint,
                )
            }
        }

        textPaint.color = config.textColor
        textPaint.textSize = layout.textSizePx
        layout.lines.forEachIndexed { index, line ->
            canvas.drawText(
                line,
                x,
                layout.top + layout.lineBaselines[index],
                textPaint,
            )
        }
    }

    /**
     * 预览是 30fps，同一份配置+数据会被反复 measure。缓存命中后直接跳过排版。
     * 单槽位即可：预览的时钟每秒只变一次，任何时候只有最新一组参数有意义。
     */
    private class LayoutCache {
        private var key: Key? = null
        private var value: WatermarkLayout? = null

        fun get(
            widthPx: Int,
            config: WatermarkConfig,
            data: WatermarkData,
            compute: () -> WatermarkLayout?,
        ): WatermarkLayout? {
            val current = Key(widthPx, config, data)
            if (current == key) return value
            val computed = compute()
            key = current
            value = computed
            return computed
        }

        private data class Key(
            val widthPx: Int,
            val config: WatermarkConfig,
            val data: WatermarkData,
        )
    }

    private companion object {
        /** 基准字号占图片宽度的比例。1080px 宽时约 32px，和常见水印相机观感一致。 */
        const val BASE_TEXT_SIZE_RATIO = 0.030f
        const val PADDING_RATIO = 0.55f
        const val LOGO_HEIGHT_RATIO = 0.85f
        const val LOGO_GAP_RATIO = 0.60f
        const val SHADOW_OFFSET_RATIO = 0.06f
        const val ALPHA_SKIP_THRESHOLD = 0.02f
        const val SHADOW_ALPHA_THRESHOLD = 0.15f
    }
}
