package com.sydeny.wmcamera.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** 相册里的一张照片。 */
data class GalleryPhoto(
    val uri: Uri,
    val displayName: String,
    val timestampMillis: Long,
    val sizeBytes: Long,
)

/**
 * 读取本 App 拍的照片。
 *
 * 只读 `Pictures/WatermarkCamera/`，不碰用户相册里的其他照片——
 * 这是个水印相机，相册页就是它自己的拍摄记录。
 */
class GalleryRepository(private val context: Context) {

    /**
     * @throws java.io.IOException MediaStore 查询失败时抛出，由调用方转成提示文案。
     */
    fun loadPhotos(): List<GalleryPhoto> {
        val resolver = context.contentResolver
        val selection = GalleryQuery.selectionFor(Build.VERSION.SDK_INT)
        val args = GalleryQuery.selectionArgsFor(Build.VERSION.SDK_INT)

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val photos = mutableListOf<GalleryPhoto>()

        resolver.query(
            collection,
            GalleryQuery.PROJECTION,
            selection,
            args,
            GalleryQuery.SORT_ORDER,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn).orEmpty()
                // date_taken 在个别设备/被别的应用改写后会为空，
                // 这时退回去从文件名解析，总比按 1970 排要合理。
                val taken = cursor.getLong(dateColumn)
                    .takeIf { it > 0L }
                    ?: GalleryQuery.parseTimestamp(name)
                photos += GalleryPhoto(
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = name,
                    timestampMillis = taken,
                    sizeBytes = cursor.getLong(sizeColumn),
                )
            }
        } ?: throw java.io.IOException("系统相册不可用")

        return photos
    }
}
