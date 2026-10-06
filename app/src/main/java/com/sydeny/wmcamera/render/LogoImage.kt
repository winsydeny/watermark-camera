package com.sydeny.wmcamera.render

import android.graphics.Canvas

/**
 * 水印 Logo 的最小抽象。
 *
 * 抽出来只有一个目的：让 [WatermarkPainter] 不必依赖 `android.graphics.Bitmap`。
 * `Bitmap` 在 android.jar 桩里是 final 且构造器包私有，既没法继承也没法在
 * JVM 单元测试里造出来——把 painter 和位图解耦之后，几何和绘制逻辑才真的能测。
 */
interface LogoImage {
    val width: Int
    val height: Int

    /** 位图被回收后必须返回 true，painter 会跳过绘制而不是崩掉。 */
    val recycled: Boolean

    /** 等比缩放铺满给定矩形。 */
    fun draw(canvas: Canvas, left: Int, top: Int, right: Int, bottom: Int)
}
