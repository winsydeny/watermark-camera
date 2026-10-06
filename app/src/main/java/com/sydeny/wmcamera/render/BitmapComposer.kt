package com.sydeny.wmcamera.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.sydeny.wmcamera.domain.WatermarkConfig
import com.sydeny.wmcamera.domain.WatermarkData
import java.io.ByteArrayOutputStream

internal object BitmapComposer {

    private const val JPEG_QUALITY = 95

    /**
     * 把拍摄结果解码、按目标旋转摆正、画上水印，返回 ARGB_8888 位图。
     *
     * ## 旋转这一步不能省，而且角度不能问 CameraX
     *
     * `OnImageCapturedCallback` 交回来的 [ImageProxy] 是**传感器原始朝向**的 JPEG。
     * 手机传感器物理上横置，竖着按快门时，那条 4000px 的长边在设备上是竖的，
     * 但缓冲区仍然按传感器原生方向存成 `4000x3000`。所以必须转 90° 才是正的。
     *
     * 角度**不能**取 `proxy.imageInfo.rotationDegrees`：本机
     * （小米 M2104K10AC / CameraX 1.6.2 / `setTargetRotation(Surface.ROTATION_90)`）
     * 它恒为 0，照它转等于不转，产出的是一张横躺的照片——
     * 之前 GPU 路径产出 3000x1350 横图、EXIF Orientation 写成无效的 0，
     * 就是这么来的。
     *
     * 改用 `ImageCapture.targetRotation`（我们自己设的）换算角度，
     * 见 [BitmapRotation.degreesFor]。
     *
     * 水印必须在摆正之后画，否则文字方向也是错的。
     *
     * @param rotationDegrees 摆正所需的角度，来自 [BitmapRotation.degreesFor]。
     */
    fun paint(
        proxy: ImageProxy,
        painter: WatermarkPainter,
        config: WatermarkConfig,
        data: WatermarkData,
        rotationDegrees: Int,
    ): Bitmap {
        val bytes = proxy.planes.firstOrNull()?.let { plane ->
            val copy = plane.buffer.duplicate()
            ByteArray(copy.remaining()).also { copy.get(it) }
        } ?: error("ImageProxy 没有可读的数据平面")

        // inMutable 必须为 true：JPEG 解码出来的是不可变位图，
        // 拿不可变位图去 new Canvas 会抛
        // IllegalStateException: Immutable bitmap passed to Canvas constructor。
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: error("无法解码拍摄结果")

        val upright = applyRotation(decoded, rotationDegrees)
        if (upright != decoded) decoded.recycle()

        if (config.hasVisibleContent) {
            painter.draw(
                canvas = Canvas(upright),
                widthPx = upright.width,
                heightPx = upright.height,
                config = config,
                data = data,
            )
        }
        return upright
    }

    private fun applyRotation(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun encodeToJpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        stream.toByteArray()
    }
}
