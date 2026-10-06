package com.sydeny.wmcamera.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class LocationStatus {
    /** 已授权，正在等第一个定位结果。 */
    LOCATING,

    /** 用户拒绝了定位权限。 */
    NO_PERMISSION,

    /** 有权限但收不到信号（室内、无 GPS 模块）。 */
    NO_SIGNAL,

    /** 已有可用定位。 */
    FIXED,
}

/**
 * 一次拍摄的水印数据快照。
 *
 * 在按下快门的那一刻生成，之后不再变化——即便定位回调继续来、用户继续改设置，
 * 已经拍下的那张照片上的水印内容也不会被改写。
 */
data class WatermarkData(
    val timestampMillis: Long,
    val locationStatus: LocationStatus,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    /** 逆地理编码得到的地址文本，如"北京市朝阳区建国路88号"。 */
    val address: String? = null,
) {
    companion object {
        fun of(timestampMillis: Long, latitude: Double?, longitude: Double?, accuracy: Float?) =
            WatermarkData(
                timestampMillis = timestampMillis,
                locationStatus = if (latitude != null && longitude != null) {
                    LocationStatus.FIXED
                } else {
                    LocationStatus.LOCATING
                },
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracy,
            )
    }
}

private val DATE_TIME_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
private val WEEKDAY_PATTERN = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)

/**
 * 经纬度格式化。
 *
 * 两个刻意的取舍：
 * 1. 一律用 [Locale.US]，避免某些 Locale 下小数点变成逗号（"39,904212"）。
 * 2. 精度跟随定位质量。GPS 民用定位在开阔地约 5m 误差，印 6 位小数（约 0.11m）
 *    是虚假精度；误差大于 50m 时降到 4 位，让照片上的数字和它的可信度匹配。
 */
object CoordinateFormatter {

    const val HIGH_PRECISION_DECIMALS = 6
    const val LOW_PRECISION_DECIMALS = 4
    const val LOW_PRECISION_ACCURACY_THRESHOLD_M = 50f

    /**
     * 没拿到精度数据时按低精度处理：宁可信得少一点，也不要在照片上印
     * 一串看起来很准、实际可能是几分钟前的位置。
     */
    fun decimalsFor(accuracyMeters: Float?): Int =
        if (accuracyMeters != null && accuracyMeters <= LOW_PRECISION_ACCURACY_THRESHOLD_M) {
            HIGH_PRECISION_DECIMALS
        } else {
            LOW_PRECISION_DECIMALS
        }

    fun formatLatitude(value: Double, decimals: Int = HIGH_PRECISION_DECIMALS): String {
        val hemisphere = if (value < 0) "S" else "N"
        return String.format(Locale.US, "%.${decimals}f°%s", kotlin.math.abs(value), hemisphere)
    }

    fun formatLongitude(value: Double, decimals: Int = HIGH_PRECISION_DECIMALS): String {
        val hemisphere = if (value < 0) "W" else "E"
        return String.format(Locale.US, "%.${decimals}f°%s", kotlin.math.abs(value), hemisphere)
    }

    /** 例：`39.904212°N  116.407394°E` */
    fun format(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
    ): String {
        val decimals = decimalsFor(accuracyMeters)
        return "${formatLatitude(latitude, decimals)}  ${formatLongitude(longitude, decimals)}"
    }
}

/** 例：`2026-10-06 15:04:22 星期二` */
fun formatTimestampLine(timestampMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val zoned = Instant.ofEpochMilli(timestampMillis).atZone(zoneId)
    return "${DATE_TIME_PATTERN.format(zoned)} ${WEEKDAY_PATTERN.format(zoned)}"
}
