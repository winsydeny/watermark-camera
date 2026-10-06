package com.sydeny.wmcamera.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sydeny.wmcamera.R
import com.sydeny.wmcamera.domain.WatermarkConfig
import com.sydeny.wmcamera.domain.WatermarkCorner
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: CameraViewModel,
    onClose: () -> Unit,
) {
    val config by viewModel.config.collectAsStateWithLifecycle()

    val logoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::setLogo) }

    // 二级页开关：水印设置里的"地址解析服务商"点击后进入
    var showAddressPage by remember { mutableStateOf(false) }

    if (showAddressPage) {
        AddressProviderSettingsPage(
            config = config,
            onBack = { showAddressPage = false },
            updateConfig = viewModel::updateConfig,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_remove),
                        tint = Color.White,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(24.dp)
                            .clickable(onClick = onClose),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader(stringResource(R.string.section_content)) }

            item {
                SwitchRow(
                    label = stringResource(R.string.label_enabled),
                    checked = config.enabled,
                    onChange = { viewModel.updateConfig { c -> c.copy(enabled = it) } },
                )
            }
            item {
                SwitchRow(
                    label = stringResource(R.string.label_datetime),
                    checked = config.showDateTime,
                    enabled = config.enabled,
                    onChange = { viewModel.updateConfig { c -> c.copy(showDateTime = it) } },
                )
            }
            item {
                SwitchRow(
                    label = stringResource(R.string.label_coordinate),
                    checked = config.showCoordinate,
                    enabled = config.enabled,
                    onChange = { viewModel.updateConfig { c -> c.copy(showCoordinate = it) } },
                )
            }
            item {
                SwitchRow(
                    label = stringResource(R.string.label_address),
                    checked = config.showAddress,
                    enabled = config.enabled,
                    onChange = { viewModel.updateConfig { c -> c.copy(showAddress = it) } },
                )
            }
            // 服务商 + API Key 配置收进二级菜单，一级页只保留一个入口行
            if (config.showAddress) {
                item {
                    NavigationRow(
                        title = stringResource(R.string.label_address_provider),
                        summary = stringResource(config.addressProvider.labelRes()),
                        onClick = { showAddressPage = true },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.section_content) + " · " + stringResource(R.string.label_custom_text)) }

            itemsIndexed(config.customLines) { index, line ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = line,
                        onValueChange = { viewModel.updateCustomLine(index, it) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.hint_line)) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_remove),
                        tint = Color.Gray,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { viewModel.removeCustomLine(index) },
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = { viewModel.addCustomLine("") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.action_add_line))
                }
            }

            item { SectionHeader(stringResource(R.string.label_logo)) }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            logoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_pick_logo)) }

                    if (config.logoFileName != null) {
                        OutlinedButton(
                            onClick = viewModel::clearLogo,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_clear_logo)) }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.section_style)) }

            item {
                LabeledSlider(
                    label = stringResource(R.string.label_text_size),
                    value = config.textScale,
                    range = WatermarkConfig.MIN_TEXT_SCALE..WatermarkConfig.MAX_TEXT_SCALE,
                    valueText = "${(config.textScale * 100).roundToInt()}%",
                    onChange = { viewModel.updateConfig { c -> c.copy(textScale = it) } },
                )
            }
            item {
                LabeledSlider(
                    label = stringResource(R.string.label_background_alpha),
                    value = config.backgroundAlpha,
                    range = 0f..WatermarkConfig.MAX_BACKGROUND_ALPHA,
                    valueText = "${(config.backgroundAlpha * 100).roundToInt()}%",
                    onChange = { viewModel.updateConfig { c -> c.copy(backgroundAlpha = it) } },
                )
            }
            item {
                ColorRow(
                    label = stringResource(R.string.label_text_color),
                    colors = TEXT_COLOR_PRESETS,
                    selected = config.textColor,
                    onSelect = { viewModel.updateConfig { c -> c.copy(textColor = it) } },
                )
            }
            item {
                ColorRow(
                    label = stringResource(R.string.label_background_color),
                    colors = BACKGROUND_COLOR_PRESETS,
                    selected = config.backgroundColor,
                    onSelect = { viewModel.updateConfig { c -> c.copy(backgroundColor = it) } },
                )
            }

            item { SectionHeader(stringResource(R.string.section_position)) }
            item {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    WatermarkCorner.entries.forEach { corner ->
                        FilterChip(
                            selected = config.corner == corner,
                            onClick = { viewModel.updateConfig { c -> c.copy(corner = corner) } },
                            label = { Text(stringResource(corner.labelRes())) },
                        )
                    }
                }
            }

            item {
                LabeledSlider(
                    label = stringResource(R.string.label_position),
                    value = config.marginRatio,
                    range = WatermarkConfig.MIN_MARGIN_RATIO..WatermarkConfig.MAX_MARGIN_RATIO,
                    valueText = "${(config.marginRatio * 100).roundToInt()}%",
                    onChange = { viewModel.updateConfig { c -> c.copy(marginRatio = it) } },
                )
            }

            item { SectionHeader(stringResource(R.string.section_capture)) }
            item {
                SwitchRow(
                    label = stringResource(R.string.label_haptics),
                    checked = config.hapticsEnabled,
                    onChange = { viewModel.updateConfig { c -> c.copy(hapticsEnabled = it) } },
                )
            }
        }
    }
}

private val TEXT_COLOR_PRESETS = listOf(
    0xFFFFFFFF.toInt(),
    0xFF000000.toInt(),
    0xFFFFEB3B.toInt(),
    0xFF4FC3F7.toInt(),
    0xFFFF7043.toInt(),
)

private val BACKGROUND_COLOR_PRESETS = listOf(
    0xFF000000.toInt(),
    0xFFFFFFFF.toInt(),
    0xFF1565C0.toInt(),
    0xFF2E7D32.toInt(),
    0xFF6A1B9A.toInt(),
)

private fun WatermarkCorner.labelRes(): Int = when (this) {
    WatermarkCorner.TOP_LEFT -> R.string.corner_top_left
    WatermarkCorner.TOP_CENTER -> R.string.corner_top_center
    WatermarkCorner.TOP_RIGHT -> R.string.corner_top_right
    WatermarkCorner.BOTTOM_LEFT -> R.string.corner_bottom_left
    WatermarkCorner.BOTTOM_CENTER -> R.string.corner_bottom_center
    WatermarkCorner.BOTTOM_RIGHT -> R.string.corner_bottom_right
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
private fun ColorRow(
    label: String,
    colors: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            colors.forEach { color ->
                val isSelected = color == selected
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(color))
                        .clickable { onSelect(color) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isDark(color)) Color.White else Color.Black,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** 选中标记画在色块上，得和色块本身对比才看得见。 */
private fun isDark(color: Int): Boolean {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return (0.299f * r + 0.587f * g + 0.114f * b) < 140f
}

@Composable
private fun ApiKeyRow(
    label: String,
    placeholder: String,
    description: String,
    value: String,
    onChange: (String) -> Unit,
    required: Boolean = true,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder) },
            // required=true 才把空值当错误高亮；SK 这类"可以留空"的字段
            // 不该给用户一个刺眼的红框。
            isError = required && value.isEmpty(),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一级页里的"入口行"：标题 + 当前值摘要 + 右侧箭头，点击跳转到二级页。
 * 视觉上和 iOS/MIUI 系统设置里的下级页保持一致，用户一眼就知道还能点进去。
 */
@Composable
private fun NavigationRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "地址解析服务商"的二级页面。把服务商切换 + API Key 输入这两件事
 * 从一级页里搬出来，一级页只保留开关和一个入口行，减少视觉负担。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressProviderSettingsPage(
    config: WatermarkConfig,
    onBack: () -> Unit,
    updateConfig: ((WatermarkConfig) -> WatermarkConfig) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_address_provider)) },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = Color.White,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(24.dp)
                            .clickable(onClick = onBack),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader(stringResource(R.string.label_address_provider)) }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    com.sydeny.wmcamera.domain.AddressProvider.entries.forEach { provider ->
                        FilterChip(
                            selected = config.addressProvider == provider,
                            onClick = { updateConfig { it.copy(addressProvider = provider) } },
                            label = { Text(stringResource(provider.labelRes())) },
                        )
                    }
                }
            }
            item {
                when (config.addressProvider) {
                    com.sydeny.wmcamera.domain.AddressProvider.AMAP -> ApiKeyRow(
                        label = stringResource(R.string.label_amap_key),
                        placeholder = stringResource(R.string.hint_amap_key),
                        description = stringResource(R.string.desc_amap_key),
                        value = config.amapApiKey,
                        onChange = { updateConfig { c -> c.copy(amapApiKey = it.trim()) } },
                    )
                    com.sydeny.wmcamera.domain.AddressProvider.TENCENT -> {
                        // 腾讯的 Key 是"两件套"：AK + 可选 SK。控制台开启"签名校验"
                        // 就必须两个都填，请求会附带 sig 参数；否则只填 AK 走 IP 白名单/无校验。
                        Column(Modifier.fillMaxWidth()) {
                            ApiKeyRow(
                                label = stringResource(R.string.label_tencent_key),
                                placeholder = stringResource(R.string.hint_tencent_key),
                                description = stringResource(R.string.desc_tencent_key),
                                value = config.tencentApiKey,
                                onChange = { updateConfig { c -> c.copy(tencentApiKey = it.trim()) } },
                            )
                            ApiKeyRow(
                                label = stringResource(R.string.label_tencent_sk),
                                placeholder = stringResource(R.string.hint_tencent_sk),
                                description = stringResource(R.string.desc_tencent_sk),
                                value = config.tencentSecretKey,
                                onChange = { updateConfig { c -> c.copy(tencentSecretKey = it.trim()) } },
                                required = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun com.sydeny.wmcamera.domain.AddressProvider.labelRes(): Int = when (this) {
    com.sydeny.wmcamera.domain.AddressProvider.AMAP -> R.string.provider_amap
    com.sydeny.wmcamera.domain.AddressProvider.TENCENT -> R.string.provider_tencent
}
