package com.sydeny.wmcamera.data

import android.provider.MediaStore
import java.util.Calendar
import java.util.TimeZone

/**
 * 相册页读 MediaStore 用的查询条件。
 *
 * 抽成纯对象是为了能上 JVM 单测：字段名写错在真机上表现为
 * `IllegalArgumentException` 或者干脆查不到任何东西，看不出是哪里错了。
 */
object GalleryQuery {

    /**
     * 和 [com.sydeny.wmcamera.render.MediaStoreSaver] 当前写入的目录一致。
     * DCIM 是所有厂商系统相册默认索引的根目录，比 Pictures 更容易在
     * 华为/荣耀这类图库时间线里出现。
     */
    const val RELATIVE_PATH = "DCIM/WatermarkCamera"

    /**
     * 历史版本用过的路径。老手机里已经保存的照片还在这条路径下，
     * 查询必须同时覆盖 [RELATIVE_PATH] 和这里，不然升级后旧照片凭空消失。
     */
    const val LEGACY_RELATIVE_PATH = "Pictures/WatermarkCamera"

    // 列名一律引用 MediaStore 常量，不手打字符串。
    // 手打过一次：把 DATE_TAKEN 写成 "date_taken"，真机直接抛
    // Invalid column date_taken。真实列名是 "datetaken"，没有下划线。
    val PROJECTION = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.SIZE,
    )

    /**
     * 最新的在前。
     *
     * `DATE_TAKEN` 单独排不稳：文件名的精度只到秒，同一秒内连拍两张的话
     * 顺序是任意的，看起来就像照片在网格里跳来跳去。加 `_id DESC` 兜底，
     * 同一秒内就按插入顺序倒着排，稳定。
     */
    val SORT_ORDER = "${MediaStore.Images.Media.DATE_TAKEN} DESC, " +
        "${MediaStore.Images.Media._ID} DESC"

    /**
     * 按 API 级别挑过滤字段。**同时匹配当前路径和历史路径**，
     * 用户在旧版本拍的照片不能因为升级而看不到。
     *
     * Android 10 起 `DATA`（绝对路径）被禁用了，还在用它会抛
     * `IllegalArgumentException`；必须换成 `RELATIVE_PATH`。
     * Android 9 及更早根本没有 `RELATIVE_PATH` 这一列，只能用 `DATA`。
     */
    @Suppress("DEPRECATION")
    fun selectionFor(sdkInt: Int): String? {
        val col = columnFor(sdkInt)
        return "($col LIKE ? OR $col LIKE ?)"
    }

    /**
     * Android 10 起 [MediaStore.Images.Media.RELATIVE_PATH] 只能用 LIKE，不能用 `=`。
     *
     * MediaStore 存这一列时会**自己补一个尾斜杠**：插进去的是
     * `DCIM/WatermarkCamera`，查出来的是 `DCIM/WatermarkCamera/`。
     * 所以 `= 'DCIM/WatermarkCamera'` 一行都匹配不到，表现为相册永远是空的。
     *
     * `%` 前面那个 `/` 不能省：省了的话 `WatermarkCameraBackup/`
     * 之类的兄弟目录也会被匹配上。
     *
     * API 29 之前 [MediaStore.Images.Media.DATA] 存的是完整绝对路径
     * （例：`/storage/emulated/0/DCIM/WatermarkCamera/xxx.jpg`），
     * 不是以 `/DCIM/...` 打头——必须前置 `%` 通配挂载点。
     * 华为/小米等 ROM 上多用户挂载点还会是 `/storage/emulated/123/...`，
     * 只有通配才能全部命中。
     */
    fun selectionArgsFor(sdkInt: Int): Array<String> {
        val current = if (sdkInt >= 29) "$RELATIVE_PATH/%" else "%/$RELATIVE_PATH/%"
        val legacy = if (sdkInt >= 29) "$LEGACY_RELATIVE_PATH/%" else "%/$LEGACY_RELATIVE_PATH/%"
        return arrayOf(current, legacy)
    }

    @Suppress("DEPRECATION")
    private fun columnFor(sdkInt: Int): String = if (sdkInt >= 29) {
        MediaStore.Images.Media.RELATIVE_PATH
    } else {
        MediaStore.Images.Media.DATA
    }

    /**
     * 从文件名里还原拍摄时刻（毫秒），失败返回 0。
     *
     * 只在 `DATE_TAKEN` 缺失时兜底——有些设备刷完机、或者照片被别的应用
     * 改写之后 `DATE_TAKEN` 会是空。返回 0 表示"不知道"，由调用方决定
     * 是排到最后还是显示成未知时间。
     */
    fun parseTimestamp(
        displayName: String,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val matched = FILE_NAME_PATTERN.matchEntire(displayName) ?: return 0L
        val (date, time) = matched.destructured
        val calendar = Calendar.getInstance(timeZone)
        // 默认是 lenient 的，月份填 13 会悄悄滚成明年 1 月而不是报错，
        // 结果就是给一张名字损坏的照片编出一个看似合理的时间。
        calendar.isLenient = false
        calendar.clear()
        return try {
            calendar.set(
                date.substring(0, 4).toInt(),
                date.substring(4, 6).toInt() - 1,
                date.substring(6, 8).toInt(),
                time.substring(0, 2).toInt(),
                time.substring(2, 4).toInt(),
                time.substring(4, 6).toInt(),
            )
            calendar.timeInMillis
        } catch (e: NumberFormatException) {
            0L
        } catch (e: IllegalArgumentException) {
            0L
        }
    }

    private val FILE_NAME_PATTERN = Regex("""WMCAM_(\d{8})_(\d{6})\.jpg""")
}
