package com.sydeny.wmcamera.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sydeny.wmcamera.R
import com.sydeny.wmcamera.ServiceLocator
import com.sydeny.wmcamera.data.SettingsRepository
import com.sydeny.wmcamera.data.sanitizeLine
import com.sydeny.wmcamera.domain.WatermarkConfig
import com.sydeny.wmcamera.domain.WatermarkData
import com.sydeny.wmcamera.domain.clamped
import com.sydeny.wmcamera.render.BitmapCompositor
import com.sydeny.wmcamera.render.LocationProvider
import com.sydeny.wmcamera.render.PoiOption
import com.sydeny.wmcamera.render.WatermarkCompositor
import com.sydeny.wmcamera.render.WatermarkPainter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CapturePhase { IDLE, PROCESSING }

/** 相机镜头朝向。翻转按钮在 [LensFacing.BACK] 和 [LensFacing.FRONT] 之间切换。 */
enum class LensFacing { BACK, FRONT }

/**
 * 闪光灯模式。刻意只保留 ON/OFF 两挡：用户明确表达过想要"手动开启或关闭"，
 * AUTO 由系统按环境亮度决定，实际体验里"按了却没闪"会被误认为 bug。
 *
 * 用我们自己的枚举而不是 [androidx.camera.core.ImageCapture] 的 int 常量：
 * UI 层只关心"用户看到的挡位"，具体常量映射集中在 [toXFlashMode] 里，
 * CameraX 升级改了常量名也不会漏改到 UI。
 */
enum class FlashMode { OFF, ON }

/** 在两挡之间来回切。 */
fun FlashMode.next(): FlashMode = when (this) {
    FlashMode.OFF -> FlashMode.ON
    FlashMode.ON -> FlashMode.OFF
}

fun FlashMode.toXFlashMode(): Int = when (this) {
    FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
    FlashMode.ON -> ImageCapture.FLASH_MODE_ON
}

sealed interface CameraEvent {
    data object Saved : CameraEvent
    data class SaveFailed(val reason: String) : CameraEvent
}

/**
 * 取景页的状态持有者。
 *
 * 相机相关的对象（UseCase、合成器）都是「绑定时才存在」的资源，
 * 由 UI 层负责在 Composable 的生命周期里调 [bindCamera] / [unbindCamera]，
 * ViewModel 只负责状态和拍照逻辑。
 */
class CameraViewModel(
    private val settingsRepository: SettingsRepository,
    private val locationProvider: LocationProvider,
    private val appContext: Context,
) : ViewModel() {

    val painter: WatermarkPainter = ServiceLocator.painter

    val config: StateFlow<WatermarkConfig> = settingsRepository.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, ServiceLocator.defaultConfig)

    private val _capturePhase = MutableStateFlow(CapturePhase.IDLE)
    val capturePhase: StateFlow<CapturePhase> = _capturePhase.asStateFlow()


    /** 切换合成器后自增，UI 侧 LaunchedEffect 监听它来重新 bind UseCase。 */
    private val _rebindToken = MutableStateFlow(0)
    val rebindToken: StateFlow<Int> = _rebindToken.asStateFlow()

    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> = _cameraError.asStateFlow()

    /** 镜头朝向。切换后自增 [rebindToken]，UI 层的 LaunchedEffect 会重绑相机。 */
    private val _lensFacing = MutableStateFlow(LensFacing.BACK)
    val lensFacing: StateFlow<LensFacing> = _lensFacing.asStateFlow()

    /**
     * 闪光灯模式。切换后：
     * 1) 直接推给当前的 [imageCapture]（下一张生效）
     * 2) 同步 StateFlow 给 UI 展示图标
     * 前置摄像头通常没有闪光单元，set 之后不会报错但也没实际效果——交给 CameraX
     * 自己忽略，避免这里做设备能力探测的复杂逻辑。
     */
    private val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    private val _eventChannel = Channel<CameraEvent>(Channel.BUFFERED)
    val events = _eventChannel.receiveAsFlow()

    /**
     * 每秒递增一次，纯粹用来触发预览水印重绘。
     * [WatermarkPainter] 的时钟由 [nowMillis] 提供，这个 flow 只是 Compose 的重组信号。
     */
    private val _previewTick = MutableStateFlow(0L)
    val previewTick: StateFlow<Long> = _previewTick.asStateFlow()

    @Volatile
    private var nowMillis: Long = System.currentTimeMillis()

    private var imageCapture: ImageCapture? = null

    private val compositor: WatermarkCompositor = BitmapCompositor(painter)

    /** 上一次 OverlayEffect 出过错就只走兜底，不再重试，免得每帧都报错。 */

    /** 定位变了要让预览上的坐标跟着变。存成字段是为了 onCleared 时能摘掉。 */
    private val locationUpdateListener: () -> Unit = {
        _previewTick.value = nowMillis
    }

    /** key 变化时要让 geocode 立刻重跑一遍，不然新 key 得等下次定位回调才生效。 */
    private var lastAmapKey: String = ""
    private var lastTencentKey: String = ""
    private var lastTencentSk: String = ""
    private var lastProvider: com.sydeny.wmcamera.domain.AddressProvider? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                nowMillis = System.currentTimeMillis()
                _previewTick.value = nowMillis
                // 对齐到整秒边界，避免每秒多跳一帧
                delay(1_000 - (nowMillis % 1_000))
            }
        }
        viewModelScope.launch {
            config.collect { current ->
                var changed = false
                if (current.amapApiKey != lastAmapKey) {
                    lastAmapKey = current.amapApiKey
                    locationProvider.amapApiKey = current.amapApiKey
                    changed = true
                }
                if (current.tencentApiKey != lastTencentKey) {
                    lastTencentKey = current.tencentApiKey
                    locationProvider.tencentApiKey = current.tencentApiKey
                    changed = true
                }
                if (current.tencentSecretKey != lastTencentSk) {
                    lastTencentSk = current.tencentSecretKey
                    locationProvider.tencentSecretKey = current.tencentSecretKey
                    changed = true
                }
                if (current.addressProvider != lastProvider) {
                    lastProvider = current.addressProvider
                    locationProvider.addressProvider = current.addressProvider
                    changed = true
                }
                if (changed) locationProvider.rerunGeocode()
            }
        }
        locationProvider.observe(locationUpdateListener)
    }

    override fun onCleared() {
        locationProvider.removeObserver(locationUpdateListener)
        super.onCleared()
    }

    fun liveData(): WatermarkData = locationProvider.currentData(nowMillis)

    /** 底部"位置选择"面板用的候选列表 StateFlow；首项为系统默认。 */
    val poiCandidates: StateFlow<List<PoiOption>> = locationProvider.poiCandidates

    /** 手动选定水印地址；传 null 恢复自动匹配。 */
    fun selectManualPick(option: PoiOption?) {
        locationProvider.selectManualPick(option)
        // 触发一次 previewTick 让 Canvas 重绘，UI 立刻看到水印变化
        _previewTick.value = System.currentTimeMillis()
    }

    // ---------------- 配置 ----------------

    fun updateConfig(transform: (WatermarkConfig) -> WatermarkConfig) {
        val updated = transform(config.value).clamped()
        viewModelScope.launch { settingsRepository.save(updated) }
    }

    /**
     * 新增一行自定义文字。
     *
     * 允许传入空串：设置页的"添加一行"按钮会先插一个空行让用户点进去输入，
     * 如果这里把空串拒掉，按钮就永远没有反应。空行不会画到照片上
     * （见 [WatermarkConfig.visibleCustomLines]）。
     */
    fun addCustomLine(text: String) {
        val line = sanitizeLine(text)
        updateConfig { it.copy(customLines = it.customLines + line) }
    }

    fun updateCustomLine(index: Int, text: String) {
        updateConfig { current ->
            current.copy(
                customLines = current.customLines.mapIndexed { i, line ->
                    if (i == index) sanitizeLine(text) else line
                },
            )
        }
    }

    fun removeCustomLine(index: Int) {
        updateConfig { current ->
            current.copy(
                customLines = current.customLines.filterIndexed { i, _ -> i != index },
            )
        }
    }

    fun setLogo(uri: Uri) {
        val fileName = ServiceLocator.logoStore.save(uri) ?: return
        updateConfig { it.copy(logoFileName = fileName) }
    }

    fun clearLogo() {
        val old = config.value.logoFileName
        ServiceLocator.logoStore.delete(old)
        updateConfig { it.copy(logoFileName = null) }
    }

    // ---------------- 相机绑定 ----------------

    /**
     * 组好 UseCase 后交给 UI 去 `bindToLifecycle`。
     *
     * 刻意不在 ViewModel 里绑定：bindToLifecycle 需要 lifecycleOwner，
     * 那是 Composable 的上下文，ViewModel 不该持有。
     */
    fun createUseCaseGroup(
        preview: Preview,
        capture: ImageCapture,
        viewport: ViewPort?,
    ): UseCaseGroup {
        imageCapture = capture
        // 每次重建 UseCase 都要把当前的闪光灯挡位推给它，
        // 否则翻转镜头之后闪光设置会丢失（新的 ImageCapture 默认 FLASH_MODE_OFF）。
        capture.flashMode = _flashMode.value.toXFlashMode()
        return compositor.createUseCaseGroup(preview, capture, viewport)
    }

    /**
     * 翻转前后镜头。
     *
     * 单纯改 StateFlow 是不够的：`bindToLifecycle` 里的 `CameraSelector`
     * 只在 [rebindToken] 变化时被 UI 层重新读取。所以这里两件事一起做。
     */
    fun toggleLens() {
        _lensFacing.value = if (_lensFacing.value == LensFacing.BACK) {
            LensFacing.FRONT
        } else {
            LensFacing.BACK
        }
        _rebindToken.value++
    }

    /** 手动两挡切换：OFF ↔ ON。 */
    fun toggleFlash() {
        val next = _flashMode.value.next()
        _flashMode.value = next
        imageCapture?.flashMode = next.toXFlashMode()
    }

    fun unbindCamera() {
        compositor.onRelease()
        imageCapture = null
    }

    fun reportCameraError(throwable: Throwable) {
        _cameraError.value = throwable.message ?: throwable.javaClass.simpleName
    }

    /**
     * 相机出错后的"重试"。
     *
     * 单纯清掉错误状态是没用的：绑定是在 [bindCamera] 里建立的，
     * 失败之后 UseCase 组还停在相机不可用的状态，界面回到预览也只会是一片空白。
     * 所以必须先解绑，再把绑定令牌推高一格让 [CameraScreen] 的
     * `LaunchedEffect` 重新走一遍绑定流程。
     */
    fun retryCamera() {
        if (_cameraError.value == null) return
        _cameraError.value = null
        unbindCamera()
        _rebindToken.value++
    }

    fun onLocationPermissionResult(granted: Boolean) {
        locationProvider.onPermissionResult(granted)
    }

    fun restartLocation() {
        locationProvider.onPermissionResult(locationProvider.permissionGranted)
    }

    // ---------------- 拍照 ----------------

    fun capture() {
        if (_capturePhase.value == CapturePhase.PROCESSING) return
        val capture = imageCapture ?: return

        _capturePhase.value = CapturePhase.PROCESSING

        // 触觉反馈：放在快门锁死状态之后、真正提交给 CameraX 之前，
        // 让用户"按下即有感"，不必等到底层真的开始曝光。
        // 关闭选项在设置页里，读取当前 config 而不是缓存字段，避免配置
        // 变更后还要重进 ViewModel 才生效。
        Log.d(TAG_HAPTIC, "capture: hapticsEnabled=${config.value.hapticsEnabled}")
        if (config.value.hapticsEnabled) performShutterHaptic()

        // 这一刻把配置和数据都锁死，之后无论定位怎么变、设置怎么改，
        // 已经按下的这张照片上的水印内容都不会再变。
        val snapshotConfig = config.value
        val location = liveData()
        val snapshot = location.copy(timestampMillis = System.currentTimeMillis())

        compositor.takePicture(
            imageCapture = capture,
            context = appContext,
            config = snapshotConfig,
            snapshot = snapshot,
        ) { result ->
            viewModelScope.launch {
                _capturePhase.value = CapturePhase.IDLE
                result
                    .onSuccess { _eventChannel.send(CameraEvent.Saved) }
                    .onFailure {
                        _eventChannel.send(
                            CameraEvent.SaveFailed(
                                it.message ?: it.javaClass.simpleName,
                            ),
                        )
                    }
            }
        }
    }

    val errorText: String
        get() = appContext.getString(R.string.camera_error_body, _cameraError.value.orEmpty())

    /**
     * 快门震动反馈。
     *
     * 刻意用 [Vibrator] 而不是 [android.view.View.performHapticFeedback]：
     * 后者要挂在 View 上，ViewModel 层没有；且它会走系统"触摸反馈"总开关，
     * 用户关了系统触摸反馈就完全没手感——那是系统设置，不该影响拍照快门
     * 这种明确的"动作确认"。
     *
     * minSdk=24、`VibrationEffect` 从 API 26 开始有；24-25 用旧的
     * `vibrate(long)`（Deprecated 但可用）。整个调用包在 runCatching 里但
     * 会把错误打出来——之前发现过"静默失败"排查半天的情况。
     */
    private fun performShutterHaptic() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appContext.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            Log.d(
                TAG_HAPTIC,
                "vibrator=${vibrator?.javaClass?.simpleName} " +
                    "hasVibrator=${vibrator?.hasVibrator()} " +
                    "sdk=${Build.VERSION.SDK_INT}",
            )
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG_HAPTIC, "no vibrator hardware, skip")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(
                    SHUTTER_HAPTIC_MS,
                    SHUTTER_HAPTIC_AMPLITUDE,
                )
                vibrator.vibrate(effect)
                Log.d(TAG_HAPTIC, "vibrate oneShot ${SHUTTER_HAPTIC_MS}ms amp=$SHUTTER_HAPTIC_AMPLITUDE")
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(SHUTTER_HAPTIC_MS)
                Log.d(TAG_HAPTIC, "vibrate legacy ${SHUTTER_HAPTIC_MS}ms")
            }
        }.onFailure {
            Log.w(TAG_HAPTIC, "haptic failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }


    companion object {
        /**
         * 快门震动时长（毫秒）。60ms 是"清晰能感到但没有拖泥带水"的下限；
         * 30ms 在很多 X 轴线性马达上过于短促，几乎感觉不到。
         */
        private const val SHUTTER_HAPTIC_MS = 60L

        /**
         * 震动幅度。VibrationEffect 里 1-255 有效，DEFAULT_AMPLITUDE 就是 -1
         * 让系统按默认策略走，但小米/OPPO 某些 ROM 上 DEFAULT_AMPLITUDE 会被
         * 折算成很弱的震动。这里显式给 200 让手感和"咔"的快门语义匹配。
         */
        private const val SHUTTER_HAPTIC_AMPLITUDE = 200

        private const val TAG_HAPTIC = "wm-haptic"

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val app = ServiceLocator.applicationContext
                return CameraViewModel(
                    settingsRepository = ServiceLocator.settingsRepository,
                    locationProvider = ServiceLocator.locationProvider,
                    appContext = app,
                ) as T
            }
        }
    }
}
