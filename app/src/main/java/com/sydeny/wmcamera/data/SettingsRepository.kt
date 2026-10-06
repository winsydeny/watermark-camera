package com.sydeny.wmcamera.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sydeny.wmcamera.BuildConfig
import com.sydeny.wmcamera.domain.WatermarkConfig
import com.sydeny.wmcamera.domain.WatermarkCorner
import com.sydeny.wmcamera.domain.AddressProvider
import com.sydeny.wmcamera.domain.clamped
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val config: Flow<WatermarkConfig> = dataStore.data
        .catch { error ->
            // 磁盘损坏或读取失败时退回默认值，别让整个 App 起不来
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { it.toConfig() }

    suspend fun save(config: WatermarkConfig) {
        dataStore.edit { prefs ->
            prefs[Keys.ENABLED] = config.enabled
            prefs[Keys.SHOW_DATE_TIME] = config.showDateTime
            prefs[Keys.SHOW_COORDINATE] = config.showCoordinate
            prefs[Keys.SHOW_ADDRESS] = config.showAddress
            // 自定义文字：直接存 raw list（可能含空串）。用 "count\n" 前缀
            // 消除"空列表"vs"单个空串"的歧义：
            //   listOf<String>()   → "0\n"
            //   listOf("")         → "1\n"
            //   listOf("a", "b")   → "2\na\nb"
            // 老版本用 visibleCustomLines（trim + filter 空串）来存，用户
            // 点"添加一行"产生的空行会立刻在 save 时被丢弃，看起来像"点了没反应"。
            prefs[Keys.CUSTOM_LINES] = encodeCustomLines(config.customLines)
            prefs[Keys.LOGO_FILE] = config.logoFileName.orEmpty()
            prefs[Keys.TEXT_COLOR] = config.textColor.toLong() and 0xFFFFFFFFL
            prefs[Keys.TEXT_SCALE] = config.textScale
            prefs[Keys.BACKGROUND_COLOR] = config.backgroundColor.toLong() and 0xFFFFFFFFL
            prefs[Keys.BACKGROUND_ALPHA] = config.backgroundAlpha
            prefs[Keys.CORNER] = config.corner.name
            prefs[Keys.MARGIN_RATIO] = config.marginRatio
            prefs[Keys.LINE_SPACING] = config.lineSpacing
            prefs[Keys.AMAP_API_KEY] = config.amapApiKey
            prefs[Keys.ADDRESS_PROVIDER] = config.addressProvider.name
            prefs[Keys.TENCENT_API_KEY] = config.tencentApiKey
            prefs[Keys.TENCENT_SECRET_KEY] = config.tencentSecretKey
            prefs[Keys.HAPTICS_ENABLED] = config.hapticsEnabled
        }
    }

    private fun Preferences.toConfig(): WatermarkConfig {
        val lines = decodeCustomLines(this[Keys.CUSTOM_LINES])
        return WatermarkConfig(
            enabled = this[Keys.ENABLED] ?: true,
            showDateTime = this[Keys.SHOW_DATE_TIME] ?: true,
            showCoordinate = this[Keys.SHOW_COORDINATE] ?: true,
            showAddress = this[Keys.SHOW_ADDRESS] ?: false,
            customLines = lines,
            logoFileName = this[Keys.LOGO_FILE]?.takeIf { it.isNotEmpty() },
            textColor = (this[Keys.TEXT_COLOR] ?: 0xFFFFFFFFL).toInt(),
            textScale = this[Keys.TEXT_SCALE] ?: 1.0f,
            backgroundColor = (this[Keys.BACKGROUND_COLOR] ?: 0xFF000000L).toInt(),
            backgroundAlpha = this[Keys.BACKGROUND_ALPHA] ?: 0.35f,
            corner = runCatching {
                WatermarkCorner.valueOf(this[Keys.CORNER] ?: WatermarkCorner.BOTTOM_LEFT.name)
            }.getOrDefault(WatermarkCorner.BOTTOM_LEFT),
            marginRatio = this[Keys.MARGIN_RATIO] ?: 0.04f,
            lineSpacing = this[Keys.LINE_SPACING] ?: 1.28f,
            amapApiKey = this[Keys.AMAP_API_KEY] ?: BuildConfig.AMAP_API_KEY,
            addressProvider = runCatching {
                AddressProvider.valueOf(this[Keys.ADDRESS_PROVIDER] ?: AddressProvider.TENCENT.name)
            }.getOrDefault(AddressProvider.TENCENT),
            // 首次启动时用户还没在设置里填过 Key，回退到 BuildConfig 注入的值
            // （BuildConfig 又从 local.properties 读）；源码里不含明文。
            tencentApiKey = this[Keys.TENCENT_API_KEY] ?: BuildConfig.TENCENT_API_KEY,
            tencentSecretKey = this[Keys.TENCENT_SECRET_KEY] ?: BuildConfig.TENCENT_SECRET_KEY,
            hapticsEnabled = this[Keys.HAPTICS_ENABLED] ?: true,
        ).clamped()
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val SHOW_DATE_TIME = booleanPreferencesKey("show_date_time")
        val SHOW_COORDINATE = booleanPreferencesKey("show_coordinate")
        val SHOW_ADDRESS = booleanPreferencesKey("show_address")
        val CUSTOM_LINES = stringPreferencesKey("custom_lines")
        val LOGO_FILE = stringPreferencesKey("logo_file")
        val TEXT_COLOR = longPreferencesKey("text_color")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val BACKGROUND_COLOR = longPreferencesKey("background_color")
        val BACKGROUND_ALPHA = floatPreferencesKey("background_alpha")
        val CORNER = stringPreferencesKey("corner")
        val MARGIN_RATIO = floatPreferencesKey("margin_ratio")
        val LINE_SPACING = floatPreferencesKey("line_spacing")
        val AMAP_API_KEY = stringPreferencesKey("amap_api_key")
        val ADDRESS_PROVIDER = stringPreferencesKey("address_provider")
        val TENCENT_API_KEY = stringPreferencesKey("tencent_api_key")
        val TENCENT_SECRET_KEY = stringPreferencesKey("tencent_secret_key")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    }

    private companion object {
        /**
         * 自定义文字用换行拼成一个字符串存，而不是 stringSet——
         * stringSet 不保证顺序，而水印行的先后顺序必须是确定的。
         * 因此 [sanitizeLine] 会先把用户输入里的换行洗掉。
         *
         * 存储格式：`"N\nline1\nline2..."`。前缀 N 消除"空列表"与"1 个空串"
         * 的歧义（两者裸 join 都是 ""）；也保证中间/结尾的空行能被原样还原。
         */
        const val LINE_SEPARATOR = "\n"

        fun encodeCustomLines(lines: List<String>): String =
            if (lines.isEmpty()) "0$LINE_SEPARATOR"
            else lines.size.toString() + LINE_SEPARATOR + lines.joinToString(LINE_SEPARATOR)

        fun decodeCustomLines(raw: String?): List<String> {
            if (raw.isNullOrEmpty()) return emptyList()
            val nl = raw.indexOf('\n')
            // 老格式（无前缀）：整个 raw 视为 joinToString 结果，向后兼容。
            // 判断依据：首段是不是纯数字。
            if (nl < 0) {
                return if (raw.isBlank()) emptyList() else listOf(raw)
            }
            val head = raw.substring(0, nl)
            val count = head.toIntOrNull()
            if (count == null) {
                // 老格式：直接 split
                return raw.split(LINE_SEPARATOR).filter { it.isNotEmpty() }
            }
            if (count == 0) return emptyList()
            val body = raw.substring(nl + 1)
            // 用 limit = count 保留中间/末尾的空串
            return body.split(LINE_SEPARATOR, limit = count)
        }
    }
}

/**
 * 水印行里的换行会破坏存储格式，也没法在单行里显示，统一洗成空格。
 *
 * **不 trim**：输入框里用户敲了个尾随空格马上被吞掉会导致光标抖动、
 * 输入体验非常差。空格在渲染阶段由 `visibleCustomLines` 的 `trim` 处理。
 */
fun sanitizeLine(raw: String): String =
    raw.replace(Regex("[\\r\\n\\u2028\\u2029]"), " ")
