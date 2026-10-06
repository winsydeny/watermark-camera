package com.sydeny.wmcamera.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class CoordinateFormatterTest {

    private lateinit var originalLocale: Locale

    @Before
    fun rememberLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `北纬东经用 N 和 E 后缀`() {
        assertEquals("39.904212°N", CoordinateFormatter.formatLatitude(39.904212))
        assertEquals("116.407394°E", CoordinateFormatter.formatLongitude(116.407394))
    }

    @Test
    fun `南纬西经用 S 和 W 后缀且取绝对值`() {
        assertEquals("33.868800°S", CoordinateFormatter.formatLatitude(-33.8688))
        assertEquals("151.209300°W", CoordinateFormatter.formatLongitude(-151.2093))
    }

    @Test
    fun `赤道和本初子午线算东半球`() {
        assertEquals("0.000000°N", CoordinateFormatter.formatLatitude(0.0))
        assertEquals("0.000000°E", CoordinateFormatter.formatLongitude(0.0))
    }

    @Test
    fun `负零不能被格式化成 S 或 W`() {
        // -0.0 < 0 在 Kotlin 里是 false，必须确认半球判断走的是这个语义
        assertTrue(CoordinateFormatter.formatLatitude(-0.0).endsWith("N"))
        assertTrue(CoordinateFormatter.formatLongitude(-0.0).endsWith("E"))
    }

    @Test
    fun `精度差超过 50 米时降到 4 位小数`() {
        assertEquals(
            CoordinateFormatter.HIGH_PRECISION_DECIMALS,
            CoordinateFormatter.decimalsFor(5f),
        )
        assertEquals(
            CoordinateFormatter.HIGH_PRECISION_DECIMALS,
            CoordinateFormatter.decimalsFor(50f),
        )
        assertEquals(
            CoordinateFormatter.LOW_PRECISION_DECIMALS,
            CoordinateFormatter.decimalsFor(50.1f),
        )
        assertEquals(
            CoordinateFormatter.LOW_PRECISION_DECIMALS,
            CoordinateFormatter.decimalsFor(null),
        )
    }

    @Test
    fun `经纬度用两空格分隔`() {
        val formatted = CoordinateFormatter.format(39.904212, 116.407394, accuracyMeters = 5f)
        assertEquals("39.904212°N  116.407394°E", formatted)
    }

    @Test
    fun `低精度时两边的经纬度位数一致`() {
        val formatted = CoordinateFormatter.format(39.904212, 116.407394, accuracyMeters = 200f)
        assertEquals("39.9042°N  116.4074°E", formatted)
    }

    @Test
    fun `德语区域设置下小数点仍然是句点`() {
        // 德语用逗号作小数分隔符，如果哪里漏了 Locale.US 就会看到 "39,904212"
        Locale.setDefault(Locale.GERMANY)
        val formatted = CoordinateFormatter.format(39.904212, 116.407394, accuracyMeters = 5f)
        assertTrue(formatted, formatted.contains("39.904212"))
        assertFalse(formatted, formatted.contains("39,904212"))
    }
}

class TimestampFormatTest {

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @Test
    fun `时间戳按 本地时区 渲染成日期时间加星期`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        // 2026-10-06 15:04:22 +08:00
        val millis = 1_791_270_262_000L
        assertEquals("2026-10-06 15:04:22 星期二", formatTimestampLine(millis))
    }
}

class WatermarkConfigTest {

    @Test
    fun `默认配置下时间与坐标都开启且有可见内容`() {
        val config = WatermarkConfig()
        assertTrue(config.hasVisibleContent)
        assertEquals(2, config.enabledLines)
    }

    @Test
    fun `全部开关关掉后没有可见内容`() {
        val config = WatermarkConfig(showDateTime = false, showCoordinate = false)
        assertFalse(config.hasVisibleContent)
        assertEquals(0, config.enabledLines)
    }

    @Test
    fun `停用水印后即使有内容也不可见`() {
        val config = WatermarkConfig(enabled = false)
        assertFalse(config.hasVisibleContent)
    }

    @Test
    fun `只有 logo 时也算有可见内容`() {
        val config = WatermarkConfig(
            showDateTime = false,
            showCoordinate = false,
            logoFileName = "logo.png",
        )
        assertTrue(config.hasVisibleContent)
        // enabledLines 说的是"文字行数"，logo 不算文字行；
        // 高度由 WatermarkPainter 用 maxOf(lines.size, 1) 保证。
        assertEquals(0, config.enabledLines)
    }

    @Test
    fun `空白自定义行不计入`() {
        val config = WatermarkConfig(
            showDateTime = false,
            showCoordinate = false,
            customLines = listOf("有内容", "  ", "", "\t"),
        )
        assertEquals(listOf("有内容"), config.visibleCustomLines)
        assertEquals(1, config.enabledLines)
    }

    @Test
    fun `自定义行保留输入顺序`() {
        val config = WatermarkConfig(customLines = listOf("第三", "第一", "第二"))
        assertEquals(listOf("第三", "第一", "第二"), config.visibleCustomLines)
    }

    @Test
    fun `clamped 把越界值拉回合法区间`() {
        val wild = WatermarkConfig(
            textScale = 99f,
            backgroundAlpha = 1f,
            marginRatio = -5f,
            lineSpacing = 0.1f,
        ).clamped()

        assertEquals(WatermarkConfig.MAX_TEXT_SCALE, wild.textScale, 1e-6f)
        assertEquals(WatermarkConfig.MAX_BACKGROUND_ALPHA, wild.backgroundAlpha, 1e-6f)
        assertEquals(WatermarkConfig.MIN_MARGIN_RATIO, wild.marginRatio, 1e-6f)
        assertEquals(WatermarkConfig.MIN_LINE_SPACING, wild.lineSpacing, 1e-6f)
    }

    @Test
    fun `clamped 保留区间内的值`() {
        val ok = WatermarkConfig(
            textScale = 1.2f,
            backgroundAlpha = 0.5f,
            marginRatio = 0.08f,
            lineSpacing = 1.4f,
        ).clamped()

        assertEquals(1.2f, ok.textScale, 1e-6f)
        assertEquals(0.5f, ok.backgroundAlpha, 1e-6f)
        assertEquals(0.08f, ok.marginRatio, 1e-6f)
        assertEquals(1.4f, ok.lineSpacing, 1e-6f)
    }
}
