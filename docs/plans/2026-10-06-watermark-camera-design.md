# 水印相机（Android）设计文档

日期：2026-10-06
状态：已确认

---

## 1. 目标与范围

做一个 Android 原生水印相机，拍下的照片在画面上永久叠加时间与 GPS 坐标水印，并支持用户自定义文字与 Logo 图片、调整样式。

### 做

| 能力 | 说明 |
|---|---|
| 实时预览水印 | 相机预览上直接看到水印，所见即所得 |
| 日期 + 时间 + 星期 | `2026-10-06 15:04:22 星期二` |
| GPS 经纬度 | 保留 6 位小数（约 0.11 米），带 N/S/E/W 半球后缀 |
| 自定义文字 | 用户可添加任意多行文字 |
| Logo 图片 | 一张 PNG/JPG，显示在水印块左侧 |
| 样式可调 | 字号、文字颜色、背景色、背景透明度、所在角、边距 |
| 保存到相册 | 存到 `Pictures/WatermarkCamera/` |

### 不做（YAGNI）

天气、地址解析、EXIF 写入、防盗图平铺水印、马赛克涂抹、相册选图加水印、视频录制。前端一律不预留这些的抽象口子。

---

## 2. 技术栈与版本

依据 2026-08 的 AndroidX 稳定版本：

| 组件 | 版本 | 理由 |
|---|---|---|
| Gradle | 8.11.1 | AGP 8.7+ 兼容 |
| Android Gradle Plugin | 8.7.3 | 稳定；不追 9.x，1.7.0-alpha03 要求 AGP 9.2 过于超前 |
| Kotlin | 2.0.21 | 配 Compose Compiler Gradle 插件 |
| Compose BOM | 2024.10.01 | |
| Material3 | 1.3.1 | |
| CameraX | **1.6.2** | 最新稳定版。`camera-effects` 含 `OverlayEffect` |
| androidx.exifinterface | 1.3.7 | 保存时保留原 EXIF |
| compileSdk / targetSdk | **36** | Google Play 自 2026-08-31 起要求 API 36 |
| minSdk | 24 | Android 7.0；`< 29` 走 `WRITE_EXTERNAL_STORAGE` 分支 |
| JDK | 17 | AGP 8.x 要求 |

**不用 Hilt。** 单 Activity + 两个 ViewModel，手写 `ServiceLocator` 足够，避免 kapt/KSP 的构建复杂度。

**定位用 `android.location.LocationManager`，不用 Play Services `FusedLocationProvider`。** 国产 ROM 常无 GMS，用 Framework API 才能在目标设备上真跑起来。

---

## 3. 架构与模块

单 Activity + Compose，依赖单向流动，自下而上：

```
ui/          CameraScreen · SettingsScreen
                 ↓ StateFlow / collectAsStateWithLifecycle
viewmodel/   CameraViewModel · SettingsViewModel
                 ↓
domain/      WatermarkConfig（持久化配置）· WatermarkData（单次拍摄快照）
                 ↓
render/      WatermarkPainter（纯函数绘制）· WatermarkCompositor（接口）
             LocationProvider · MediaStoreSaver
```

### 3.1 核心决策：绘制与合成解耦

整个设计围绕一句话：**「水印怎么画」和「水印画到哪」是两件事，必须分开。**

`WatermarkPainter` 是纯函数，不引用任何 CameraX 类型：

```kotlin
object WatermarkPainter {
    fun draw(
        canvas: android.graphics.Canvas,
        widthPx: Int,
        heightPx: Int,
        config: WatermarkConfig,
        data: WatermarkData,
    )
}
```

它内部**所有尺寸都从 `widthPx` 按比例推导**，不含任何绝对 dp/sp：

| 量 | 公式 |
|---|---|
| 基准字号 | `widthPx * 0.030f * config.textScale` |
| 外边距 | `widthPx * config.marginRatio` |
| 行高 | `基准字号 * config.lineSpacing` |
| 内边距 | `基准字号 * 0.55f` |

于是同一份代码画在 1080px 的预览缓冲和 12000px 的成片上，**视觉比例完全一致**。这正是下面两种合成器能互换的前提。

### 3.2 合成器接口

```kotlin
interface WatermarkCompositor {
    fun attach(preview: Preview, imageCapture: ImageCapture): UseCaseGroup.Builder
    fun onRelease()
    val modeName: String
}
```

两个实现：

- **`OverlayEffectCompositor`**（首选）— CameraX `camera-effects` 的 `OverlayEffect`，OpenGL 合成，同时作用于 `PREVIEW or IMAGE_CAPTURE`。预览与成片像素级一致，零跳位，无 Bitmap 解码开销。
- **`BitmapCompositor`**（兜底）— `takePicture(executor, OnImageCapturedCallback)` 拿 `ImageProxy`，解码 Bitmap，在 `android.graphics.Canvas` 上调**同一个** `WatermarkPainter`，再编码回 JPEG。

切换成本 = 改 `ServiceLocator` 里的一行。运行期也能切：`OverlayEffect` 的 `errorListener` 触发时自动降级到 `BitmapCompositor` 并 Toast 告知。

---

## 4. 水印渲染层

### 4.1 数据模型

```kotlin
data class WatermarkConfig(
    val enabled: Boolean = true,
    val showDateTime: Boolean = true,
    val showCoordinate: Boolean = true,
    val customLines: List<String> = emptyList(),
    val logoFileName: String? = null,   // filesDir 下的副本，不用 content://
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val textScale: Float = 1.0f,         // 0.7 .. 1.6
    val backgroundColor: Int = 0xFF000000.toInt(),
    val backgroundAlpha: Float = 0.35f,  // 0.0 .. 0.85
    val corner: WatermarkCorner = BOTTOM_LEFT,
    val marginRatio: Float = 0.04f,
    val lineSpacing: Float = 1.28f,
) {
    val fieldsEnabled get() = showDateTime || showCoordinate || customLines.isNotEmpty() || logoFileName != null
}

data class WatermarkData(
    val timestampMillis: Long,
    val latitude: Double?,      // null = 尚无定位
    val longitude: Double?,
    val accuracyMeters: Float?,
)
```

`WatermarkData` 在**按下快门的那一刻**由 `CameraViewModel` 快照生成（`System.currentTimeMillis()` + 当前定位状态），之后即使定位回调或设置变更也不影响已拍这张照片的水印内容。

### 4.2 行内容生成

`WatermarkPainter` 内部 `buildLines()` 产出 `List<RenderedLine>`：

```
[logo?]                 ← 独立绘制，不占文本行
"2026-10-06 15:04:22 星期二"    ← showDateTime
"39.904212°N  116.407394°E"     ← showCoordinate，精度 ±2 位，accuracy>50m 时降为 4 位
"定位中…" / "定位未授权" / "无定位信号"   ← latitude==null 时替代坐标行
customLines[0]
customLines[1]
...
```

坐标格式化统一用 `Locale.US` 避免小数点变逗号；半球后缀按符号分别取 N/S、E/W。精度降级逻辑：GPS 民用定位在开阔地约 5m，印 6 位小数是虚假精度，故 `accuracy > 50m` 时降到 4 位（约 11m），让照片上的数字和真实可信度匹配。

### 4.3 布局算法

```
1. lines = buildLines(config, data)
2. baseText = widthPx * 0.030f * config.textScale
3. 行高 lineH = baseText * config.lineSpacing
4. 文本宽 textW = max(lines.map { paint.measureText(it) })      ← 带 1 条记录的缓存
5. logoSide = 若有 logo: lineH * lines.size * 0.9  否则 0
6. innerW = logoSide + (若有 logo 则 gap) + textW
   innerH = lineH * lines.size
   blockW = innerW + padding * 2 ;  blockH = innerH + padding * 2
7. 按 config.corner + margin = widthPx * config.marginRatio 定位 block 的左上角
8. 绘制顺序：圆角矩形背景 → logo → 各行文本
```

**缓存**：`WatermarkPainter` 持有一个 `LayoutCache`，key = `(widthPx, config.hashCode(), data.hashCode())`。命中则跳过 `measureText`。预览每秒 30 帧时不重排。

### 4.4 圆角与对齐

背景圆角半径 = `padding`，`Paint` 设 `AntiAlias`。文本用 `Typeface.create(Typeface.DEFAULT, Typeface.BOLD)`——水印要压在任意背景上，Regular 太细。

---

## 5. 相机与拍摄流程

### 5.1 UseCase 绑定

```kotlin
val viewport = ViewPort.Builder(Rational(aspectRatio, 1)).setScaleType(FILL_CENTER).build()
val effect = OverlayEffect(CameraEffect.PREVIEW or CameraEffect.IMAGE_CAPTURE, 0, handler, errorListener)
effect.setOnDrawListener { frame ->
    WatermarkPainter.draw(frame.overlayCanvas, frame.size.width, frame.size.height, config, liveData)
    true
}

val group = UseCaseGroup.Builder()
    .addUseCase(preview)
    .addUseCase(imageCapture)
    .setViewport(viewport)     // 关键：Preview 与 ImageCapture 共用同一裁剪矩形
    .addEffect(effect)
    .build()
cameraProvider.bindToLifecycle(lifecycleOwner, DEFAULT_BACK_CAMERA, group)
```

**`setViewport` 是这个方案最容易踩的坑。** CameraX 社区已知问题：不设 Viewport 或两个 UseCase 宽高比不一致时，成片上的水印会被裁掉一部分（预览里看着正常）。因此：

- `preview` 和 `imageCapture` 都用 `ResolutionSelector` 固定 `AspectRatio.RATIO_4_3`
- 二者都调 `setTargetRotation(display.rotation)`
- 绑定的 `Camera` 上记 `CameraInfo.getSensorRotationDegrees()`，供绘制层做横竖屏补偿

`queueDepth = 0`——静态水印不需要延迟释放帧，少一次纹理拷贝。

### 5.2 拍照流程

```
用户点快门
  → 拍摄期间禁用快门（防连点），快门图标做缩放反馈
  → snapshot = WatermarkData(now, 当前定位)      ← 快照
  → 立即在预览上把 liveData 替换为 snapshot，锁住水印内容
  → imageCapture.takePicture(outputOptions, executor, callback)
      ├─ OverlayEffect 路径：CameraX 已把水印烧进 JPEG，直接写盘
      └─ Bitmap 路径：OnImageCaptured → BitmapCompositor.composite(proxy, config, snapshot) → 写盘
  → 保存成功后 400ms 内自动弹回 liveData（预览继续走实时数据）
```

拍摄耗时期间在预览上覆盖一层「正在处理」的轻微变暗，避免用户误以为没按到。

### 5.3 保存

`MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 插入，`DISPLAY_NAME = WMCAM_yyyyMMdd_HHmmss.jpg`，`RELATIVE_PATH = Pictures/WatermarkCamera`（API 29+）。API 24–28 走 `WRITE_EXTERNAL_STORAGE` + 传统路径。

用 `ImageCapture.OutputFileOptions.Builder(resolver, uri, metadata)` 直接写入 MediaStore URI，省掉一次「先存临时文件再搬运」。`BitmapCompositor` 路径则先 encode 到 `ByteArrayOutputStream` 再写进 URI 的 `OutputStream`。

---

## 6. 设置与持久化

- **DataStore Preferences** 存 `WatermarkConfig` 的各字段（枚举存 name，Color 存 ARGB Long）。
- **Logo 图片**：用户选图后用 `ContentResolver` 读入，解码并等比缩放到最长边 512px，PNG 无损存到 `filesDir/logo.png`。只存文件名。
  - 为什么不直接存 `content://` URI：需要持久化读权限，Android 13+ 的 `READ_MEDIA_IMAGES` 对普通应用不开放，重启后容易失效。拷进私有目录是一劳永逸的。
- `SettingsViewModel` 用 `MutableStateFlow<WatermarkConfig>`，任何修改 `copy()` 后写回 DataStore，写入用 `launch` + `viewModelScope`，防抖 300ms。

---

## 7. 权限、错误处理与降级

| 情况 | 行为 |
|---|---|
| 相机权限未授予 | 引导页 + 「去设置」按钮，预览不启动 |
| 定位权限未授予 | App 正常可用，水印坐标行显示「定位未授权」 |
| 已有权限但无定位信号 | 坐标行显示「定位中…」，拿到第一个点后自动刷新 |
| 定位数据过旧（> 60s） | 标注为「定位中…」，不拿旧点充数 |
| `OverlayEffect` GL 初始化/运行失败 | `errorListener` 捕获 → 运行期切 `BitmapCompositor` + Toast，一次性，之后不再尝试 |
| `bindToLifecycle` 失败（相机被占用等） | 全屏错误态 + 重试按钮 |
| 存储写入失败 | Toast 报错 + 释放快门，不崩溃 |
| 拍照中 Activity 被销毁 | `ImageCapture` 回调检测 lifecycle 状态，安全丢弃 |

权限请求顺序：进入 App 先要相机权限（不给就完全没法用），拿到后再要定位权限（不给也能用，理由充分）。两者分别 `rememberLauncherForActivityResult` 独立处理。

---

## 8. 测试与验证

### 单元测试（JVM，`app/src/test`）

1. `WatermarkPainterTest`
   - 给定固定 `widthPx`，断言算出的背景块 rect 的宽高与所在角位置
   - 有 logo / 无 logo 两种布局的 rect 差异
   - 背景透明度为 0 时不绘制背景（用记录型 `Canvas` 桩断言 `drawRoundRect` 未被调用）
   - 布局缓存：同参数连续两次 `draw`，第二次不触发 `measureText`
2. `WatermarkDataTest`（坐标格式化）
   - 北纬东经 / 南纬西经 / 赤道 / 本初子午线 的半球后缀
   - `latitude == null` 时的降级文案
   - `accuracy > 50` 时小数位数从 6 降到 4
   - `Locale.CHINA` 与 `Locale.GERMANY` 下小数点均为 `.`
3. `WatermarkConfigTest` — `fieldsEnabled` 的各种组合；`textScale` 钳制边界
4. `FakeCanvas` — 记录所有 `drawXxx` 调用，用于断言绘制序列

### 集成验证（adb）

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n <pkg>/.MainActivity
adb shell screencap -p /sdcard/preview.png && adb pull /sdcard/preview.png   # 验证实时预览水印
adb shell ls /sdcard/Pictures/WatermarkCamera/                                  # 验证成片落盘
adb pull <成片> && 用 ExifTool/脚本确认水印像素已烧进 JPEG
```

关键验收点：预览截图里的水印比例，与拉下来的成片里的水印比例，肉眼一致。

---

## 9. 已知风险

| 风险 | 缓解 |
|---|---|
| `OverlayEffect` 在部分国产 ROM 上 GL 合成异常 | `BitmapCompositor` 兜底，且绘制层共享 |
| Preview/ImageCapture 宽高比不一致导致水印被裁 | 共用 `Viewport` + 统一 `ResolutionSelector`；真机验收 |
| 无真机可测 | 已确认用户接入 USB 调试，可直接装机验证 |
| 全分辨率 Bitmap 内存峰值（12MP ARGB_8888 ≈ 48MB） | 仅兜底路径触发；兜底路径下按 `maxMemory/4` 降采样 |
