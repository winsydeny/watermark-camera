package com.sydeny.wmcamera.domain

import androidx.annotation.ColorInt
import com.sydeny.wmcamera.BuildConfig

enum class WatermarkCorner {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
}

/** 逆地理编码服务商。两者国内覆盖都好，取其一即可。 */
enum class AddressProvider {
    AMAP,
    TENCENT,
}

/**
 * 用户可配置的水印样式与内容。全部字段都参与持久化。
 *
 * 注意：这里刻意不存任何像素或 sp 尺寸。所有渲染尺寸都由 [WatermarkPainter]
 * 从图片宽度按比例推导，这样同一份配置画在预览缓冲和全分辨率成片上才等比。
 */
data class WatermarkConfig(
    val enabled: Boolean = true,
    val showDateTime: Boolean = true,
    val showCoordinate: Boolean = true,
    val showAddress: Boolean = false,
    val customLines: List<String> = emptyList(),
    /** filesDir 下的 logo 文件名，不是 content:// uri。见 LogoStore。 */
    val logoFileName: String? = null,
    @ColorInt val textColor: Int = 0xFFFFFFFF.toInt(),
    val textScale: Float = 1.0f,
    @ColorInt val backgroundColor: Int = 0xFF000000.toInt(),
    /** 0f = 完全透明（不画背景），1f = 全不透明。 */
    val backgroundAlpha: Float = 0.35f,
    val corner: WatermarkCorner = WatermarkCorner.BOTTOM_LEFT,
    /** 外边距占图片宽度的比例。 */
    val marginRatio: Float = 0.04f,
    val lineSpacing: Float = 1.28f,
    /**
     * 高德 Web 服务 API Key，用于逆地理编码。空则回退系统 Geocoder。
     * 默认值来自 BuildConfig（构建时从 local.properties 注入），源码里不含明文。
     */
    val amapApiKey: String = BuildConfig.AMAP_API_KEY,
    /** 选用的逆地理编码服务商。 */
    val addressProvider: AddressProvider = AddressProvider.TENCENT,
    /** 腾讯位置服务 API Key。默认值来自 BuildConfig。 */
    val tencentApiKey: String = BuildConfig.TENCENT_API_KEY,
    /**
     * 腾讯 SK（签名校验密钥），空表示该 Key 未启用签名。
     * 默认值来自 BuildConfig。
     */
    val tencentSecretKey: String = BuildConfig.TENCENT_SECRET_KEY,
    /** 拍照时是否触发一次触觉反馈（短震动）。 */
    val hapticsEnabled: Boolean = true,
) {
    /** 去掉空白项后的自定义文字行。 */
    val visibleCustomLines: List<String>
        get() = customLines.map { it.trim() }.filter { it.isNotEmpty() }

    /** 是否至少有一项内容会被渲染。false 时相机直接显示干净画面。 */
    val hasVisibleContent: Boolean
        get() = enabled && (
            showDateTime || showCoordinate || showAddress ||
                visibleCustomLines.isNotEmpty() || logoFileName != null
            )

    /** 画在照片上的文字行数（不含 logo，logo 单独绘制）。 */
    val enabledLines: Int
        get() = (if (showDateTime) 1 else 0) +
            (if (showCoordinate) 1 else 0) +
            (if (showAddress) 1 else 0) +
            visibleCustomLines.size

    companion object {
        const val MIN_TEXT_SCALE = 0.7f
        const val MAX_TEXT_SCALE = 1.6f
        const val MIN_MARGIN_RATIO = 0.015f
        const val MAX_MARGIN_RATIO = 0.12f
        const val MIN_LINE_SPACING = 1.0f
        const val MAX_LINE_SPACING = 1.8f
        /** 超过这个背景不透明度就完全盖住画面，失去水印的意义。 */
        const val MAX_BACKGROUND_ALPHA = 0.85f
    }
}

fun WatermarkConfig.clamped(): WatermarkConfig = copy(
    textScale = textScale.coerceIn(
        WatermarkConfig.MIN_TEXT_SCALE,
        WatermarkConfig.MAX_TEXT_SCALE,
    ),
    backgroundAlpha = backgroundAlpha.coerceIn(0f, WatermarkConfig.MAX_BACKGROUND_ALPHA),
    marginRatio = marginRatio.coerceIn(
        WatermarkConfig.MIN_MARGIN_RATIO,
        WatermarkConfig.MAX_MARGIN_RATIO,
    ),
    lineSpacing = lineSpacing.coerceIn(
        WatermarkConfig.MIN_LINE_SPACING,
        WatermarkConfig.MAX_LINE_SPACING,
    ),
)
