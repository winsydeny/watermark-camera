package com.sydeny.wmcamera.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.sydeny.wmcamera.domain.LocationStatus
import com.sydeny.wmcamera.domain.WatermarkConfig
import com.sydeny.wmcamera.domain.WatermarkCorner
import com.sydeny.wmcamera.domain.WatermarkData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 记录自己被画到哪里的假 logo，用来断言"有没有画、画在哪个矩形"。 */
private class FakeLogo(
    override val width: Int = 4,
    override val height: Int = 4,
    override val recycled: Boolean = false,
    private val trace: MutableList<String>? = null,
) : LogoImage {
    /** 用裸 int 记录，不用 android.graphics.Rect——它在 android.jar 桩里是空实现。 */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    var draws = 0
    var lastBounds: Bounds? = null

    override fun draw(canvas: Canvas, left: Int, top: Int, right: Int, bottom: Int) {
        draws++
        lastBounds = Bounds(left, top, right, bottom)
        trace?.add("logo")
    }
}

/** 记录绘制调用的 Canvas 桩，用来断言"画了什么"，而不是去比对像素。 */
private class RecordingCanvas : Canvas() {
    var roundRectCount = 0
    val roundRects = mutableListOf<RectF>()
    val texts = mutableListOf<String>()

    override fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rx: Float,
        ry: Float,
        paint: Paint,
    ) {
        roundRectCount++
        roundRects += RectF(left, top, right, bottom)
        super.drawRoundRect(left, top, right, bottom, rx, ry, paint)
    }

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        texts += text
        super.drawText(text, x, y, paint)
    }
}

class WatermarkPainterTest {

    private lateinit var canvas: RecordingCanvas

    /** 每个字符宽度固定为字号的 0.6 倍，几何量因此完全可预测。 */
    private var measureCalls = 0

    private fun painter(hasLogo: Boolean = false) = WatermarkPainter(
        logoProvider = { _ -> if (hasLogo) FakeLogo() else null },
        textMeasurer = { text, textSize ->
            measureCalls++
            text.length * textSize * 0.6f
        },
        timestampFormatter = { "DT" },
        statusTextProvider = { status ->
            when (status) {
                LocationStatus.NO_PERMISSION -> "NOPERM"
                LocationStatus.NO_SIGNAL -> "NOSIG"
                else -> "LOC"
            }
        },
    )

    private val data = WatermarkData(
        timestampMillis = 1_791_270_262_000L,
        locationStatus = LocationStatus.NO_SIGNAL,
    )

    private val config = WatermarkConfig(
        showDateTime = true,
        showCoordinate = true,
        textScale = 1f,
        backgroundAlpha = 0.35f,
        corner = WatermarkCorner.BOTTOM_LEFT,
        marginRatio = 0.04f,
        lineSpacing = 1.28f,
    )

    @Before
    fun setup() {
        canvas = RecordingCanvas()
        measureCalls = 0
    }

    @Test
    fun `基准尺寸全部由图宽推导`() {
        val layout = painter().measure(1080, 1440, config, data, hasLogo = false)!!
        val expectedTextSize = 1080f * 0.030f
        assertEquals(expectedTextSize, layout.textSizePx, 1e-3f)
        assertEquals(expectedTextSize * 1.28f, layout.lineHeightPx, 1e-3f)
    }

    @Test
    fun `左下角时左边距等于 margin 底边等于 高减 margin`() {
        val layout = painter().measure(1080, 1440, config, data, hasLogo = false)!!
        assertEquals(1080f * 0.04f, layout.left, 1e-3f)
        assertEquals(1440f - 1080f * 0.04f, layout.bottom, 1e-3f)
    }

    @Test
    fun `四个角的对齐关系都正确`() {
        val p = painter()
        val margin = 1080f * 0.04f

        val topLeft = p.measure(1080, 1440, config.copy(corner = WatermarkCorner.TOP_LEFT), data, false)!!
        assertEquals(margin, topLeft.top, 1e-3f)
        assertEquals(margin, topLeft.left, 1e-3f)

        val topRight = p.measure(1080, 1440, config.copy(corner = WatermarkCorner.TOP_RIGHT), data, false)!!
        assertEquals(margin, topRight.top, 1e-3f)
        assertEquals(1080f - margin, topRight.right, 1e-3f)

        val bottomRight = p.measure(1080, 1440, config.copy(corner = WatermarkCorner.BOTTOM_RIGHT), data, false)!!
        assertEquals(1440f - margin, bottomRight.bottom, 1e-3f)
        assertEquals(1080f - margin, bottomRight.right, 1e-3f)
    }

    /**
     * 这条是这个 App 最核心的设计断言：
     * 同一份配置画在预览缓冲和全分辨率成片上，所有几何量必须严格等比。
     * 预览和成片的水印对不上，整套"所见即所得"就是空的。
     */
    @Test
    fun `宽度翻十倍时水印块等比放大`() {
        val p = painter()
        val small = p.measure(1080, 1440, config, data, hasLogo = false)!!
        val large = p.measure(10800, 14400, config, data, hasLogo = false)!!

        assertEquals(small.width * 10f, large.width, 1e-2f)
        assertEquals(small.height * 10f, large.height, 1e-2f)
        assertEquals(small.textSizePx * 10f, large.textSizePx, 1e-2f)
        assertEquals(small.lineHeightPx * 10f, large.lineHeightPx, 1e-2f)
        // 左上角对齐时，左边距和上边距也等比
        val corner = config.copy(corner = WatermarkCorner.TOP_LEFT)
        val smallCorner = p.measure(1080, 1440, corner, data, false)!!
        val largeCorner = p.measure(10800, 14400, corner, data, false)!!
        assertEquals(smallCorner.left * 10f, largeCorner.left, 1e-2f)
        assertEquals(smallCorner.top * 10f, largeCorner.top, 1e-2f)
    }

    @Test
    fun `有 logo 时文字整体右移且水印块变宽`() {
        val p = painter(hasLogo = true)
        val withoutLogo = p.measure(1080, 1440, config, data, hasLogo = false)!!
        val withLogo = p.measure(1080, 1440, config, data, hasLogo = true)!!

        assertFalse(withoutLogo.hasLogo)
        assertTrue(withLogo.hasLogo)
        assertTrue(
            "有 logo 后块宽应该更大",
            withLogo.width > withoutLogo.width,
        )
        assertTrue(
            "文字起点应该右移",
            withLogo.textOriginX > withoutLogo.textOriginX,
        )
    }

    @Test
    fun `行内容按 时间 坐标 自定义文字 的顺序排列`() {
        val lines = painter().buildLines(
            config.copy(customLines = listOf("甲", "乙")),
            data,
        )
        assertEquals(listOf("DT", "NOSIG", "甲", "乙"), lines)
    }

    @Test
    fun `没有可用坐标时用状态文案替代`() {
        val lines = painter().buildLines(
            config.copy(showDateTime = false),
            WatermarkData(0L, LocationStatus.NO_PERMISSION),
        )
        assertEquals(listOf("NOPERM"), lines)
    }

    @Test
    fun `背景透明度为 0 时不画背景`() {
        painter().draw(canvas, 1080, 1440, config.copy(backgroundAlpha = 0f), data)
        assertEquals(0, canvas.roundRectCount)
    }

    @Test
    fun `背景透明度大于 0 时画一次背景`() {
        painter().draw(canvas, 1080, 1440, config, data)
        assertEquals(1, canvas.roundRectCount)
    }

    @Test
    fun `背景透明时用偏移阴影保证文字可读 画出两次文字`() {
        painter().draw(canvas, 1080, 1440, config.copy(backgroundAlpha = 0f), data)
        // 两行文字各画两遍：阴影一遍 + 正常一遍
        assertEquals(4, canvas.texts.size)
    }

    @Test
    fun `背景不透明时不画阴影`() {
        painter().draw(canvas, 1080, 1440, config.copy(backgroundAlpha = 0.5f), data)
        assertEquals(2, canvas.texts.size)
    }

    @Test
    fun `停用水印时什么都不画`() {
        painter().draw(canvas, 1080, 1440, config.copy(enabled = false), data)
        assertEquals(0, canvas.roundRectCount)
        assertTrue(canvas.texts.isEmpty())
    }

    @Test
    fun `没有可见内容时不返回布局`() {
        val nothing = config.copy(showDateTime = false, showCoordinate = false)
        assertNull(painter().measure(1080, 1440, nothing, data, hasLogo = false))
    }

    @Test
    fun `尺寸非法时不崩也不画`() {
        painter().draw(canvas, 0, 0, config, data)
        assertEquals(0, canvas.roundRectCount)
        assertTrue(canvas.texts.isEmpty())
    }

    @Test
    fun `相同参数连续绘制会命中布局缓存 不重复排版`() {
        val p = painter()
        p.draw(canvas, 1080, 1440, config, data)
        val callsAfterFirst = measureCalls
        assertTrue("首次绘制应该测量过", callsAfterFirst > 0)

        repeat(30) { p.draw(canvas, 1080, 1440, config, data) }
        assertEquals(
            "30 次重绘不应该再触发任何 measureText",
            callsAfterFirst,
            measureCalls,
        )
    }

    @Test
    fun `配置变化会让缓存失效`() {
        val p = painter()
        p.draw(canvas, 1080, 1440, config, data)
        val callsAfterFirst = measureCalls

        p.draw(canvas, 1080, 1440, config.copy(textScale = 1.4f), data)
        assertTrue("改了字号应该重新排版", measureCalls > callsAfterFirst)
    }

    @Test
    fun `时钟推进一秒会让缓存失效`() {
        val p = painter()
        p.draw(canvas, 1080, 1440, config, data)
        val callsAfterFirst = measureCalls

        p.draw(canvas, 1080, 1440, config, data.copy(timestampMillis = data.timestampMillis + 1_000))
        assertTrue("时间变了应该重新排版", measureCalls > callsAfterFirst)
    }

    @Test
    fun `图宽变化会让缓存失效`() {
        val p = painter()
        p.draw(canvas, 1080, 1440, config, data)
        val callsAfterFirst = measureCalls

        p.draw(canvas, 2160, 1440, config, data)
        assertTrue("分辨率切换应该重新排版", measureCalls > callsAfterFirst)
    }

    @Test
    fun `绘制顺序是 背景 logo 文字`() {
        val order = mutableListOf<String>()
        val logo = FakeLogo(trace = order)
        val p = WatermarkPainter(
            logoProvider = { _ -> logo },
            textMeasurer = { text, textSize -> text.length * textSize * 0.6f },
            timestampFormatter = { "DT" },
            statusTextProvider = { "LOC" },
        )
        val canvas = object : Canvas() {
            override fun drawRoundRect(
                left: Float,
                top: Float,
                right: Float,
                bottom: Float,
                rx: Float,
                ry: Float,
                paint: Paint,
            ) {
                order += "background"
                super.drawRoundRect(left, top, right, bottom, rx, ry, paint)
            }

            override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                if (order.isEmpty() || order.last() != "text") order += "text"
                super.drawText(text, x, y, paint)
            }
        }

        p.draw(canvas, 1080, 1440, config.copy(logoFileName = "logo.png"), data, clearFirst = false)
        assertEquals(listOf("background", "logo", "text"), order)
        assertEquals(1, logo.draws)
    }

    @Test
    fun `logo 被画进水印块内部`() {
        val logo = FakeLogo()
        val p = WatermarkPainter(
            logoProvider = { _ -> logo },
            textMeasurer = { text, textSize -> text.length * textSize * 0.6f },
            timestampFormatter = { "DT" },
            statusTextProvider = { "LOC" },
        )
        val layout = p.measure(1080, 1440, config.copy(logoFileName = "logo.png"), data, hasLogo = true)!!
        p.draw(canvas, 1080, 1440, config.copy(logoFileName = "logo.png"), data)

        val b = logo.lastBounds!!
        assertTrue("logo 不能跑到水印块外面", b.left >= layout.left.toInt())
        assertTrue(b.right <= layout.right.toInt())
        assertTrue(b.top >= layout.top.toInt())
        assertTrue(b.bottom <= layout.bottom.toInt())
        assertEquals("logo 应该是个正方形", b.right - b.left, b.bottom - b.top)
        assertEquals(
            "logo 边长应该等于布局算出的 logoSide",
            layout.logoSide.toInt(),
            b.right - b.left,
        )
    }

    @Test
    fun `没有配置 logo 文件名时完全不碰 logoProvider`() {
        val logo = FakeLogo()
        var asked = false
        val p = WatermarkPainter(
            logoProvider = { _ -> asked = true; logo },
            textMeasurer = { text, textSize -> text.length * textSize * 0.6f },
            timestampFormatter = { "DT" },
            statusTextProvider = { "LOC" },
        )
        p.draw(canvas, 1080, 1440, config, data)
        assertFalse(asked)
        assertEquals(0, logo.draws)
    }

    @Test
    fun `logo 已被回收时跳过绘制但不崩`() {
        val logo = FakeLogo(recycled = true)
        val p = WatermarkPainter(
            logoProvider = { _ -> logo },
            textMeasurer = { text, textSize -> text.length * textSize * 0.6f },
            timestampFormatter = { "DT" },
            statusTextProvider = { "LOC" },
        )
        p.draw(canvas, 1080, 1440, config.copy(logoFileName = "logo.png"), data)
        assertEquals(0, logo.draws)
        // 文字还是要画
        assertEquals(2, canvas.texts.size)
    }

    @Test
    fun `clearFirst 为 true 时会先清空画布`() {
        var cleared = false
        val p = WatermarkPainter(
            textMeasurer = { text, textSize -> text.length * textSize * 0.6f },
            timestampFormatter = { "DT" },
            statusTextProvider = { "LOC" },
        )
        val clearing = object : Canvas() {
            override fun drawColor(color: Int, mode: android.graphics.PorterDuff.Mode) {
                cleared = true
                super.drawColor(color, mode)
            }
        }
        p.draw(clearing, 1080, 1440, config, data, clearFirst = true)
        assertTrue("OverlayEffect 路径必须清空，否则会残留上一帧笔画", cleared)
    }

    @Test
    fun `水印关闭时仍然要擦画布`() {
        // OverlayEffect 复用同一块纹理，不擦就会把上一帧的水印留在屏幕上
        var cleared = false
        val nothing = config.copy(enabled = false)
        val clearing = object : Canvas() {
            override fun drawColor(color: Int, mode: android.graphics.PorterDuff.Mode) {
                cleared = true
                super.drawColor(color, mode)
            }
        }
        painter().draw(clearing, 1080, 1440, nothing, data, clearFirst = true)
        assertTrue("停用水印后必须擦掉上一帧的残留", cleared)
    }

    @Test
    fun `clearFirst 无论有没有内容都会擦画布`() {
        var cleared = 0
        val clearing = object : Canvas() {
            override fun drawColor(color: Int, mode: android.graphics.PorterDuff.Mode) {
                cleared++
                super.drawColor(color, mode)
            }
        }
        val p = painter()
        p.draw(clearing, 1080, 1440, config, data, clearFirst = true)
        p.draw(clearing, 1080, 1440, config.copy(enabled = false), data, clearFirst = true)
        p.draw(clearing, 0, 0, config, data, clearFirst = true)
        assertEquals("clearFirst 是调用方契约，不该被内容或尺寸影响", 3, cleared)
    }

    @Test
    fun `clearFirst 为 false 时不擦画布`() {
        var cleared = 0
        val plain = object : Canvas() {
            override fun drawColor(color: Int, mode: android.graphics.PorterDuff.Mode) {
                cleared++
                super.drawColor(color, mode)
            }
        }
        painter().draw(plain, 1080, 1440, config, data, clearFirst = false)
        assertEquals(0, cleared)
    }
}
