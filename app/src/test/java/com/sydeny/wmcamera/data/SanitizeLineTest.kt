package com.sydeny.wmcamera.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizeLineTest {

    @Test
    fun `换行被洗成空格`() {
        assertEquals("a b", sanitizeLine("a\nb"))
        assertEquals("a b", sanitizeLine("a\rb"))
        assertEquals("a b", sanitizeLine("a\u2028b"))
    }

    /**
     * 关键回归测试：**不做 trim**。
     * 输入框里用户敲尾随空格马上被吞掉会导致光标抖动、输入体验非常差。
     * 空格在渲染时由 `WatermarkConfig.visibleCustomLines` 处理。
     */
    @Test
    fun `首尾空格被保留`() {
        assertEquals("  abc  ", sanitizeLine("  abc  "))
    }

    @Test
    fun `纯空白保留为空格`() {
        // \n 被换成空格，其他空白原样保留
        assertEquals("      ", sanitizeLine("   \n  "))
    }
}
