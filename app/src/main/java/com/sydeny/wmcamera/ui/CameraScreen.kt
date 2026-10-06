package com.sydeny.wmcamera.ui

import android.hardware.camera2.CameraCharacteristics
import android.graphics.Bitmap
import android.view.Surface
import android.util.Log
import android.util.Rational
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sydeny.wmcamera.R
import com.sydeny.wmcamera.render.PoiOption
import java.util.Locale
import java.util.concurrent.Executor

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val config by viewModel.config.collectAsStateWithLifecycle()
    val capturePhase by viewModel.capturePhase.collectAsStateWithLifecycle()
    val cameraError by viewModel.cameraError.collectAsStateWithLifecycle()
    val rebindToken by viewModel.rebindToken.collectAsStateWithLifecycle()
    val poiCandidates by viewModel.poiCandidates.collectAsStateWithLifecycle()
    val lensFacing by viewModel.lensFacing.collectAsStateWithLifecycle()
    val flashMode by viewModel.flashMode.collectAsStateWithLifecycle()

    // 读一下就够：它的作用是每秒触发一次重组，让水印上的时钟走字
    val tick by viewModel.previewTick.collectAsStateWithLifecycle()

    // 打开"选择水印地址"底部面板
    var showAddressPicker by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val mainExecutor = remember<Executor> { androidx.core.content.ContextCompat.getMainExecutor(context) }

    // 用预览区的实际宽高比来定 ViewPort，这样 PreviewView 里可见的区域
    // 和 ImageCapture 裁出来的区域完全重合，水印位置才对得上。
    var previewAspect by remember { mutableStateOf(3f / 4f) }

    // 冻结帧：按下快门时抓取当前预览画面，保存到相册期间显示此帧
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }

    // capturePhase 回到 IDLE 时释放冻结帧，恢复实时预览
    LaunchedEffect(capturePhase) {
        if (capturePhase == CapturePhase.IDLE) {
            frozenFrame?.recycle()
            frozenFrame = null
        }
    }

    // 页面离开时回收
    DisposableEffect(Unit) {
        onDispose {
            frozenFrame?.recycle()
        }
    }


    LaunchedEffect(rebindToken, previewAspect, lensFacing) {
        val provider = try {
            cameraProviderFuture.get()
        } catch (e: Exception) {
            viewModel.reportCameraError(e)
            return@LaunchedEffect
        }

        // 4:3。预览区固定成 3:4（竖屏），传感器横置的 4:3 流竖起来正好是
        // 3000:4000 = 3:4，和预览区逐像素对齐，CameraX 一格都不用裁。
        // 成片因此也是标准 3:4（3000x4000），不是 9:20 那种细长条。
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(portraitTargetRotation(provider, lensFacing))
            .build()

        // ViewPort 的宽高比一律按**横屏**语义解释，1.6.2 的 Builder 也没有
        // orientation 参数可设（只有 Builder(Rational, alignment)，
        // setScaleType/setLayoutDirection），所以竖屏比例必须取倒数再传。
        //
        // 这里原来直接把竖屏量出来的 0.56 塞进去，横屏语义下就成了
        // "从横置传感器裁一条 562:1000 的细长条"，CameraX 只能靠大幅裁切填满，
        // 一打开就是 5x。取倒数后是 16:9，恰好等于 16:9 传感器流本身，一格不裁。
        val viewport = androidx.camera.core.ViewPort.Builder(
            android.util.Rational(
                1000,
                (previewAspect * 1000).toInt().coerceAtLeast(1),
            ),
            androidx.camera.core.ViewPort.FILL_CENTER,
        ).build()

        viewModel.createUseCaseGroup(preview, imageCapture, viewport)
            .let { group ->
                runCatching {
                    provider.unbindAll()
                    val selector = when (lensFacing) {
                        LensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                        LensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        group,
                    )
                    // 诊断：确认视野是被裁掉的，而不是 CameraX 加了数字变焦。
                    camera.cameraInfo.zoomState.value?.let { z ->
                        Log.d(
                            "wm-capture",
                            "zoom ratio=${z.zoomRatio} linear=${z.linearZoom} " +
                                "range=[${z.minZoomRatio}, ${z.maxZoomRatio}] " +
                                "viewportAspect=$previewAspect lens=$lensFacing " +
                                "hasFlashUnit=${camera.cameraInfo.hasFlashUnit()}",
                        )
                    }
                }.onFailure { viewModel.reportCameraError(it) }
            }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.unbindCamera() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 预览区固定 3:4，垂直居中对齐，视觉重心稳定。
        //
        // 必须让预览区的比例和 ViewPort 一致，否则成片里会多出预览上没见过的
        // 边缘，水印落在那儿就对不上位置。3:4 同时是常规竖幅照片的比例。
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .onSizeChanged { size ->
                    if (size.height > 0) {
                        previewAspect = size.width.toFloat() / size.height.toFloat()
                    }
                },
        ) {
            AndroidViewHolder(previewView)

            // tick 每秒变化一次，重组后重画水印，时钟就走起来了
            if (config.hasVisibleContent && tick > 0L) {
                Canvas(Modifier.fillMaxSize()) {
                    // 尺寸比例和成片完全一致：Painter 所有尺寸都由 widthPx 推导
                    viewModel.painter.draw(
                        canvas = drawContext.canvas.nativeCanvas,
                        widthPx = size.width.toInt(),
                        heightPx = size.height.toInt(),
                        config = config,
                        data = viewModel.liveData(),
                    )
                }
            }

            // 拍摄处理中显示冻结帧（模拟快门锁帧效果）
            if (capturePhase == CapturePhase.PROCESSING) {
                val frame = frozenFrame
                if (frame != null && !frame.isRecycled) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    // 兜底：如果抓取 bitmap 失败，仍然显示加载指示器
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            cameraError?.let { message ->
                CameraErrorOverlay(
                    message = message,
                    onRetry = viewModel::retryCamera,
                )
            }
        }

        TopControls(
            flashMode = flashMode,
            onToggleLens = viewModel::toggleLens,
            onToggleFlash = viewModel::toggleFlash,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Controls(
            capturePhase = capturePhase,
            onShutter = {
                // 抓取当前预览帧作为冻结画面
                frozenFrame = previewView.bitmap
                viewModel.capture()
            },
            onOpenGallery = onOpenGallery,
            onPickLocation = { showAddressPicker = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showAddressPicker) {
        AddressPickerSheet(
            candidates = poiCandidates,
            currentAddress = viewModel.liveData().address,
            onSelect = { option -> viewModel.selectManualPick(option) },
            onResetAuto = { viewModel.selectManualPick(null) },
            onDismiss = { showAddressPicker = false },
        )
    }
}

@Composable
private fun AndroidViewHolder(previewView: PreviewView) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 顶部工具条：左侧一排"翻转镜头 + 闪光灯模式"，右侧设置入口。
 * 加了 [statusBarsPadding] 避让刘海/状态栏。
 */
@Composable
private fun TopControls(
    flashMode: FlashMode,
    onToggleLens: () -> Unit,
    onToggleFlash: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧工具组
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TopIconChip(
                imageVector = Icons.Default.FlipCameraAndroid,
                contentDescription = stringResource(R.string.cd_switch_camera),
                onClick = onToggleLens,
            )
            TopIconChip(
                imageVector = flashMode.icon(),
                contentDescription = stringResource(R.string.cd_flash),
                onClick = onToggleFlash,
            )
        }
        // 右侧：设置
        TopIconChip(
            imageVector = Icons.Default.Settings,
            contentDescription = stringResource(R.string.cd_settings),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun TopIconChip(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    overlayText: String? = null,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = imageVector, contentDescription = contentDescription, tint = Color.White)
        if (overlayText != null) {
            Text(
                text = overlayText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 2.dp),
            )
        }
    }
}

private fun FlashMode.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    FlashMode.OFF -> Icons.Default.FlashOff
    FlashMode.ON -> Icons.Default.FlashOn
}

@Composable
private fun Controls(
    capturePhase: CapturePhase,
    onShutter: () -> Unit,
    onOpenGallery: () -> Unit,
    onPickLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：相册
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable(onClick = onOpenGallery),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = stringResource(R.string.cd_gallery),
                    tint = Color.White,
                )
            }

            // 中间：快门
            val pressed = capturePhase == CapturePhase.PROCESSING
            Box(
                Modifier
                    .size(76.dp)
                    .scale(if (pressed) 0.86f else 1f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (pressed) 0.5f else 1f))
                    .clickable(enabled = !pressed, onClick = onShutter),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3A3A3C)),
                )
            }

            // 右侧：定位地址选择
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable(onClick = onPickLocation),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = stringResource(R.string.cd_pick_location),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * 底部弹出的地址选择面板。
 * 列表首项即系统当前自动匹配的值（推荐），
 * 用户点其他项会把手选地址写入 [CameraViewModel.selectManualPick]，
 * 点"恢复自动匹配"清空手选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressPickerSheet(
    candidates: List<PoiOption>,
    currentAddress: String?,
    onSelect: (PoiOption) -> Unit,
    onResetAuto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1C1C1E),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.pick_location_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.pick_location_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(12.dp))
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.pick_location_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    items(candidates, key = { it.display }) { option ->
                        val selected = option.display == currentAddress
                        val isDefault = option == candidates.first()
                        AddressRow(
                            option = option,
                            selected = selected,
                            isDefault = isDefault,
                            onClick = { onSelect(option) },
                        )
                    }
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                text = stringResource(R.string.pick_location_auto),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF8EB4FF),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onResetAuto)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AddressRow(
    option: PoiOption,
    selected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isDefault) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.pick_location_badge_default),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFC961),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFFC961).copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            val distance = formatDistance(option.distanceMeters)
            Text(
                text = if (option.category.isBlank()) distance else "$distance · ${option.category}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF8EB4FF),
            )
        }
    }
}

private fun formatDistance(meters: Double): String = when {
    meters.isInfinite() || meters <= 0.0 -> "0 m"
    meters < 1000.0 -> String.format(Locale.US, "%.0f m", meters)
    else -> String.format(Locale.US, "%.2f km", meters / 1000.0)
}

@Composable
private fun CameraErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = stringResource(R.string.camera_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/**
 * 竖屏拍摄时 `ImageCapture` 的目标旋转（一个 `Surface.ROTATION_*` 常量）。
 *
 * 这里踩了两次坑，两个方向都错过，值得写清楚：
 *
 * 1. `setTargetRotation` 收的是 `Surface.ROTATION_*`（0..3 的**常量**），不是度数。
 *    传 90 会在拍照瞬间抛
 *    `IllegalArgumentException: Unsupported surface rotation: 90`
 *    （`CameraOrientationUtil.surfaceRotationToDegrees`）。
 * 2. 但也**不能**直接用 `display.rotation`。App 在 manifest 里锁了
 *    `screenOrientation="portrait"`，此时 display 的 rotation 恒为 `ROTATION_0`。
 *    而最初那版用的是 `previewView.display?.rotation ?: ROTATION_0`——
 *    类型对，但这个 LaunchedEffect 跑在首次组合期，PreviewView 还没 attach 到窗口，
 *    `display` 是 null，于是回退成 `ROTATION_0`，
 *    传感器横置的缓冲原样落盘：3000x1350 的横图、EXIF Orientation 写成无效的 0。
 *
 * 可靠来源是相机自己报的传感器朝向 `SENSOR_ORIENTATION`（单位是度）。
 * App 锁竖屏，所以把它换算成等价的旋转常量即可，不猜、不硬编码。
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun portraitTargetRotation(
    provider: ProcessCameraProvider,
    lens: LensFacing,
): Int {
    val selector = when (lens) {
        LensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        LensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }
    val info = provider.availableCameraInfos
        .firstOrNull { selector.filter(listOf(it)).isNotEmpty() }
    val sensorOrientation = (info as? Camera2CameraInfo)
        ?.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION)
        ?: DEFAULT_SENSOR_ORIENTATION_DEGREES
    return when (sensorOrientation) {
        90 -> Surface.ROTATION_90
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_270
        else -> Surface.ROTATION_0
    }
}

private const val DEFAULT_SENSOR_ORIENTATION_DEGREES = 90
