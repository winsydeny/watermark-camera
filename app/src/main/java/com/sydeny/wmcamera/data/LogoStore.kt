package com.sydeny.wmcamera.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import com.sydeny.wmcamera.render.LogoImage
import java.io.File
import java.io.FileOutputStream

/**
 * 水印 Logo 的存取。
 *
 * 用户选中的图片会被复制进私有目录，只在配置里存文件名。
 * 为什么不直接存 `content://` URI：那需要持久化读权限，而 Android 13+ 上
 * 普通应用拿不到 `READ_MEDIA_IMAGES`，重启后 URI 很容易就失效了。
 */
class LogoStore(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    fun save(source: Uri): String? = runCatching {
        val bitmap = decodeScaled(source) ?: error("无法解码所选图片")
        try {
            val target = File(directory, FILE_NAME)
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FILE_NAME
        } finally {
            bitmap.recycle()
        }
    }.onFailure { Log.e(TAG, "保存 logo 失败", it) }.getOrNull()

    fun load(fileName: String): LogoImage? {
        val file = File(directory, fileName)
        if (!file.exists()) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .onFailure { Log.e(TAG, "读取 logo 失败", it) }
            .getOrNull() ?: return null
        return BitmapLogo(bitmap)
    }

    fun delete(fileName: String?) {
        fileName ?: return
        runCatching { File(directory, fileName).delete() }
    }

    /**
     * 先按目标尺寸解一遍，只为读出尺寸，再按比例降采样解码，
     * 避免用户选了一张 50MB 的原图直接把内存打爆。
     */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        if (decoded.width <= MAX_EDGE_PX && decoded.height <= MAX_EDGE_PX) return decoded

        val scale = MAX_EDGE_PX.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_EDGE_PX && height / (sample * 2) >= MAX_EDGE_PX) {
            sample *= 2
        }
        return sample
    }

    /** 把手上的 Bitmap 包成 painter 能用的形式，并保证用完就回收。 */
    private class BitmapLogo(private val bitmap: Bitmap) : LogoImage {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
        override val recycled: Boolean get() = bitmap.isRecycled

        override fun draw(canvas: Canvas, left: Int, top: Int, right: Int, bottom: Int) {
            if (bitmap.isRecycled) return
            canvas.drawBitmap(bitmap, null, Rect(left, top, right, bottom), null)
        }
    }

    private companion object {
        const val TAG = "LogoStore"
        const val DIRECTORY = "watermark_assets"
        const val FILE_NAME = "logo.png"

        /** 水印里 logo 显示边长不超过文字块高度，512px 绰绰有余。 */
        const val MAX_EDGE_PX = 512
    }
}
