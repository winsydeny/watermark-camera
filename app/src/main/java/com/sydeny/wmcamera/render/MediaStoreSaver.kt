package com.sydeny.wmcamera.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次待写入的相册条目。写失败时必须调 [abandon] 清理掉。 */
data class PendingEntry(val uri: Uri, val legacyFile: File?)

/**
 * 往系统相册写照片。
 *
 * API 29+ 用 MediaStore + `IS_PENDING`，不需要任何存储权限；
 * API 24–28 没有分区存储，只能落 `Environment` 的公共 Pictures 目录，
 * 需要 `WRITE_EXTERNAL_STORAGE`。
 *
 * 两条合成路径的写入方式不同，所以分成两组入口：
 *
 * - GPU 路径（[valuesForCameraX]）：条目由 CameraX 自己 insert。
 *   `OutputFileOptions.Builder(resolver, uri, values)` 的第二个参数是
 *   **collection** URI，CameraX 会拿着它和 values 自己建一条新记录，
 *   建完通过 `OutputFileResults.savedUri` 把它交回来。
 *   这条路径绝不能自己先 insert——那样相册里会多出一条空记录，
 *   而且回调里拿到的还是错的那条。
 * - Bitmap 路径（[createPendingEntry]）：自己先 insert 再写流，
 *   因为这时没有 CameraX 帮忙建条目。
 */
class MediaStoreSaver(private val context: Context) {

    fun buildValues(timestampMillis: Long): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName(timestampMillis))
        put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
        put(MediaStore.Images.Media.DATE_TAKEN, timestampMillis)
        put(MediaStore.Images.Media.DATE_MODIFIED, timestampMillis / 1000)
    }

    /**
     * 交给 CameraX 写时用的 values：只描述"要写成什么样"，
     * 不预先插入条目。返回的 values 里已经带好 pending 标记和目标目录。
     */
    fun valuesForCameraX(timestampMillis: Long): ContentValues = buildValues(timestampMillis).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            // 分区存储之前，CameraX 靠 DATA 里的绝对路径决定写到哪
            put(MediaStore.Images.Media.DATA, legacyFile(timestampMillis).absolutePath)
        }
    }

    /** 自己写入流时用：先建条目，再写内容。 */
    fun createPendingEntry(timestampMillis: Long): PendingEntry {
        val values = buildValues(timestampMillis)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: throw IOException("MediaStore 拒绝了新条目")
            return PendingEntry(uri = uri, legacyFile = null)
        }

        val file = legacyFile(timestampMillis)
        values.put(MediaStore.Images.Media.DATA, file.absolutePath)
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: throw IOException("MediaStore 拒绝了新条目")
        return PendingEntry(uri = uri, legacyFile = file)
    }

    fun writeBytes(entry: PendingEntry, bitmap: Bitmap) {
        val jpeg = BitmapComposer.encodeToJpeg(bitmap)
        context.contentResolver.openOutputStream(entry.uri)?.use { it.write(jpeg) }
            ?: throw IOException("无法打开 ${entry.uri} 的输出流")
    }

    /** 写入成功，撤掉 pending 让相册立刻能看到。 */
    fun finalize(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    fun finalize(entry: PendingEntry) {
        finalize(entry.uri)
        // API 29 之前 MediaProvider 的 insert 只是登记一行；部分厂商 ROM
        // （华为 EMUI 8 是典型）不会立刻把这条媒体推进 Gallery 的图库索引，
        // 得主动通知 MediaScanner 扫一次文件，系统相册才在时间线里显示。
        // API 29+ 分区存储下 MediaStore 自己会广播 CONTENT_CHANGE，
        // 不需要再手动 scan。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            entry.legacyFile?.let { file ->
                runCatching {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf(MIME_TYPE),
                        null,
                    )
                }
            }
        }
    }

    /** 写入失败，把半截条目清掉，不给用户相册里留垃圾。 */
    fun abandon(uri: Uri, legacyFile: File? = null) {
        runCatching { context.contentResolver.delete(uri, null, null) }
        legacyFile?.let { runCatching { it.delete() } }
    }

    fun abandon(entry: PendingEntry) = abandon(entry.uri, entry.legacyFile)

    private fun legacyFile(timestampMillis: Long): File {
        // RELATIVE_PATH 已经是 "DCIM/WatermarkCamera"，去掉前缀 "DCIM/"
        // 剩下 "WatermarkCamera" 挂到公共 DCIM 根目录下
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            RELATIVE_PATH.removePrefix("DCIM/"),
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建目录 ${directory.absolutePath}")
        }
        return File(directory, fileName(timestampMillis))
    }

    // SimpleDateFormat 不是线程安全的，而文件名可能在不同线程上生成，
    // 所以每次新建一个；一张照片一个，代价可以忽略。
    private fun fileName(timestampMillis: Long): String =
        "WMCAM_${SimpleDateFormat(FILE_NAME_PATTERN, Locale.US).format(Date(timestampMillis))}.jpg"

    private companion object {
        const val MIME_TYPE = "image/jpeg"
        /**
         * 保存目录。**用 DCIM/WatermarkCamera 而不是 Pictures/WatermarkCamera**：
         * - DCIM 是所有厂商系统相机/相册的默认索引根目录，华为 EMUI 8 之类
         *   的图库只在 DCIM 树下扫描时间线，Pictures 子目录会当作"其他文件夹"
         *   藏在相册页里、或者干脆不出现在时间线。
         * - 加子目录 WatermarkCamera 是为了和系统相机 DCIM/Camera 分开，
         *   不污染用户的"相机"分组。
         * - 老版本这里存的是 `Pictures/WatermarkCamera`，历史照片通过
         *   [com.sydeny.wmcamera.data.GalleryQuery] 里的双路径查询继续可见。
         */
        const val RELATIVE_PATH = "DCIM/WatermarkCamera"
        /** 老版本使用的路径，只用于历史照片查询兼容。 */
        const val LEGACY_RELATIVE_PATH = "Pictures/WatermarkCamera"
        const val FILE_NAME_PATTERN = "yyyyMMdd_HHmmss"
    }
}
