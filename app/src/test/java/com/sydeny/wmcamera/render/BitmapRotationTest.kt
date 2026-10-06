package com.sydeny.wmcamera.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Surface.ROTATION_*` 常量和"转多少度"的换算。
 *
 * 这两个不是一回事，是本项目里踩过坑的地方：
 * - `setTargetRotation` 只收 `Surface.ROTATION_*`（0..3 的常量）；
 * - `Matrix.postRotate` 只认角度。
 *
 * 而 `ImageProxy.imageInfo.rotationDegrees` 在本机（小米 M2104K10AC，
 * CameraX 1.6.2，`setTargetRotation(Surface.ROTATION_90)`）恒为 0，
 * 不能拿来当依据——照它转等于不转，出来的就是横图。
 */
class BitmapRotationTest {

    @Test
    fun `旋转常量换算成角度`() {
        assertEquals(0, BitmapRotation.degreesFor(0))
        assertEquals(90, BitmapRotation.degreesFor(1))
        assertEquals(180, BitmapRotation.degreesFor(2))
        assertEquals(270, BitmapRotation.degreesFor(3))
    }

    @Test
    fun `不认识的常量按 0 度处理而不是崩掉`() {
        assertEquals(0, BitmapRotation.degreesFor(-1))
        assertEquals(0, BitmapRotation.degreesFor(4))
    }
}
