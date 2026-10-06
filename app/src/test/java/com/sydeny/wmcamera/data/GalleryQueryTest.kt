package com.sydeny.wmcamera.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import android.provider.MediaStore
import java.util.TimeZone
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相册页的 MediaStore 查询条件。
 *
 * 真正容易错的地方有两处：
 * 1) API 29 前后字段名换了：分区存储之后不能再用 `DATA`（绝对路径），只能用
 *    `RELATIVE_PATH`。写错了在 Android 10+ 上会直接抛 `IllegalArgumentException`，
 *    而且报错信息完全看不出是哪个字段的问题。
 * 2) 保存路径迁移后（Pictures → DCIM），查询必须同时匹配新旧两个路径，
 *    老照片在升级后不能凭空消失。
 */
class GalleryQueryTest {

    @Test
    fun `Android 10 及以上按 RELATIVE_PATH 过滤`() {
        val clause = GalleryQuery.selectionFor(sdkInt = 29)
        // 迁移到 DCIM 后需要"新路径 OR 老路径"两个 LIKE 联合匹配。
        assertEquals(
            "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)",
            clause,
        )
    }

    @Test
    fun `Android 10 及以上用 LIKE 而不是等于`() {
        // MediaStore 存 relative_path 时会自己补尾斜杠，插的是
        // DCIM/WatermarkCamera，查出来是 DCIM/WatermarkCamera/。
        // 用 `=` 精确匹配查不到任何一行，相册永远显示为空。
        val clause = GalleryQuery.selectionFor(sdkInt = 29)
        assertFalse("用 = 匹配会一行都查不到", clause!!.contains("="))
    }

    @Test
    fun `Android 10 及以上 args 覆盖新旧两个路径`() {
        val args = GalleryQuery.selectionArgsFor(29).toList()
        assertEquals(
            listOf("DCIM/WatermarkCamera/%", "Pictures/WatermarkCamera/%"),
            args,
        )
        args.forEach { arg ->
            assertTrue("尾斜杠不能省：$arg", arg.endsWith("/%"))
            assertFalse(
                "% 前面的 / 防止 WatermarkCameraBackup 被误伤：$arg",
                arg.removeSuffix("/%").endsWith("WatermarkCameraX"),
            )
        }
    }

    @Test
    fun `Android 10 以下 DATA 是绝对路径 必须前置通配`() {
        @Suppress("DEPRECATION")
        val clause = GalleryQuery.selectionFor(sdkInt = 28)
        assertEquals(
            "(${MediaStore.Images.Media.DATA} LIKE ? OR " +
                "${MediaStore.Images.Media.DATA} LIKE ?)",
            clause,
        )
        // API < 29 时 DATA 列存的是完整绝对路径，如
        //   /storage/emulated/0/DCIM/WatermarkCamera/xxx.jpg
        // 只写 "/DCIM/WatermarkCamera/%" 一条也匹配不上——必须前置 % 通配挂载点。
        assertEquals(
            listOf("%/DCIM/WatermarkCamera/%", "%/Pictures/WatermarkCamera/%"),
            GalleryQuery.selectionArgsFor(28).toList(),
        )
    }

    @Test
    fun `最新的照片排在最前`() {
        // 引用 MediaStore 常量而不是手打字符串：DATE_TAKEN 的真实列名是
        // "datetaken"，手打成 "date_taken" 真机直接抛 Invalid column。
        assertEquals(
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media._ID} DESC",
            GalleryQuery.SORT_ORDER,
        )
    }

    @Test
    fun `投影包含渲染列表和大图需要的全部字段`() {
        val projection = GalleryQuery.PROJECTION.toList()
        assertTrue(projection.contains(MediaStore.Images.Media._ID))
        assertTrue(projection.contains(MediaStore.Images.Media.DISPLAY_NAME))
        assertTrue(projection.contains(MediaStore.Images.Media.DATE_TAKEN))
        assertTrue(projection.contains(MediaStore.Images.Media.SIZE))
    }

    @Test
    fun `照片名的时间戳能还原出拍摄时刻`() {
        // 和 MediaStoreSaver.fileName 的格式一致：yyyyMMdd_HHmmss。
        // 固定 UTC，否则断言值会跟着跑测试的机器时区漂。
        val millis = GalleryQuery.parseTimestamp(
            displayName = "WMCAM_20261006_111153.jpg",
            timeZone = TimeZone.getTimeZone("UTC"),
        )
        assertEquals(1791285113000L, millis)
    }

    @Test
    fun `同一时刻在不同时区下解析结果不同`() {
        val utc = GalleryQuery.parseTimestamp(
            "WMCAM_20261006_111153.jpg",
            TimeZone.getTimeZone("UTC"),
        )
        val shanghai = GalleryQuery.parseTimestamp(
            "WMCAM_20261006_111153.jpg",
            TimeZone.getTimeZone("Asia/Shanghai"),
        )
        assertEquals(8 * 60 * 60 * 1000L, utc - shanghai)
    }
}
