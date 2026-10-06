package com.sydeny.wmcamera.render

import android.view.Surface

/** 相机旋转相关的小工具。 */
object BitmapRotation {

    /**
     * 把 `Surface.ROTATION_*` 常量换算成旋转角度。
     *
     * 这套 App 锁竖屏，传感器横置（`SENSOR_ORIENTATION` 通常是 90），
     * 所以正常路径就是 1 → 90。
     */
    fun degreesFor(surfaceRotation: Int): Int = when (surfaceRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
