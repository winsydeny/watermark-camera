# 水印相机 · WatermarkCamera

一款 Android 原生水印相机。拍下的照片会把 **日期时间、GPS 经纬度、地址、自定义文字、Logo** 永久烧进画面，所见即所得——预览里看到的水印就是成片上的水印。

单 Activity + Jetpack Compose，CameraX 驱动，无第三方 DI 框架。

---

## ✨ 功能特性

| 能力 | 说明 |
|---|---|
| 实时预览水印 | 相机预览上直接叠加水印，预览与成片像素级一致 |
| 日期 + 时间 + 星期 | 例如 `2026-10-06 15:04:22 星期二` |
| GPS 经纬度 | 默认 6 位小数（约 0.11 m），带 N/S/E/W 半球后缀；精度差时自动降位 |
| 具体地址 | 逆地理编码到小区/大楼级，支持高德 / 腾讯两种服务商 |
| 自定义文字 | 可添加任意多行文字 |
| Logo 图片 | 一张 PNG/JPG，等比缩放后显示在水印块左侧 |
| 样式可调 | 字号、文字颜色、背景色、背景透明度、所在角、边距、行距 |
| 内置相册 | 浏览已拍摄的带水印照片，可用其他应用打开 |
| 快门震动反馈 | 可选的触觉反馈 |
| 保存到相册 | 存到 `Pictures/WatermarkCamera/`，文件名 `WMCAM_yyyyMMdd_HHmmss.jpg` |

### 刻意不做（YAGNI）

天气水印、防盗图平铺水印、马赛克涂抹、相册选图二次加水印、视频录制。

---

## 🧱 技术栈

| 组件 | 版本 |
|---|---|
| Gradle | 8.11.1 |
| Android Gradle Plugin | 8.9.1 |
| Kotlin | 2.0.21 |
| Jetpack Compose BOM | 2024.10.01 |
| CameraX | 1.6.2 |
| DataStore Preferences | 1.1.1 |
| 腾讯定位 SDK | 7.2.6 |
| compileSdk / targetSdk | 36 |
| minSdk | 24（Android 7.0） |
| JDK | 17 |

**架构分层**（依赖单向流动，自上而下）：

```
ui/        CameraScreen · SettingsScreen · GalleryScreen（Compose）
   ↓ StateFlow
viewmodel/ CameraViewModel · SettingsViewModel · GalleryViewModel
   ↓
domain/    WatermarkConfig（持久化配置） · WatermarkData（单次拍摄快照）
   ↓
render/    WatermarkPainter（纯函数绘制） · WatermarkCompositor（合成器接口）
           LocationProvider · ReGeocoder · MediaStoreSaver
   ↓
data/      SettingsRepository（DataStore） · GalleryRepository · LogoStore
```

### 核心设计：绘制与合成解耦

> **「水印怎么画」和「水印画到哪」是两件事，必须分开。**

`WatermarkPainter` 是不引用任何 CameraX 类型的纯函数，所有渲染尺寸都从图片宽度按比例推导——同一份代码画在预览缓冲和全分辨率成片上，视觉比例完全一致。

`WatermarkCompositor` 提供两种实现，可互换、可运行期降级：

- **`OverlayEffectCompositor`（首选）**——CameraX `camera-effects` 的 `OverlayEffect`，OpenGL 合成，预览与成片零跳位、无 Bitmap 解码开销。
- **`BitmapCompositor`（兜底）**——解码 `ImageProxy` 为 Bitmap，在 `Canvas` 上调用**同一个** `WatermarkPainter` 再编码回 JPEG。当 `OverlayEffect` 的 GL 初始化/运行失败时自动降级并 Toast 告知。

---

## 🚀 快速开始

### 环境要求

- JDK 17
- Android SDK（API 36）
- Android Studio（或纯命令行 + 已配置好的 SDK）

### 1. 克隆并配置密钥

```bash
git clone https://github.com/winsydeny/watermark-camera.git
cd watermark-camera
cp local.properties.example local.properties
```

`local.properties` **已被 `.gitignore` 排除**，密钥永不进版本控制。按需填写以下字段（均可留空）：

| 字段 | 用途 | 申请地址 |
|---|---|---|
| `amapApiKey` | 高德逆地理编码（可选，留空回退系统 Geocoder） | https://console.amap.com |
| `tencentApiKey` | 腾讯室内定位 + 逆地理编码（推荐填写） | https://lbs.qq.com |
| `tencentSecretKey` | 腾讯 Key 若启用「签名校验」则必填 | 同上，控制台 |

这些值由 Gradle 在构建时注入到 `BuildConfig` 和 `AndroidManifest` 占位符，源码里只出现 `BuildConfig.TENCENT_API_KEY` 之类的符号，不含明文。

> 不填任何密钥也能正常构建和拍照，只是地址精度退回系统 Geocoder（仅到街道级）。

### 2. 构建 & 安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 运行单元测试

```bash
./gradlew test
```

覆盖：水印布局算法、坐标格式化与半球后缀、精度降位、多 Locale 下小数点、配置钳制边界等。

---

## 📦 打包发布

`release` 构建默认使用 Android SDK 自带的 `debug.keystore` 签名，生成的 APK 可直接安装到任意设备，方便自测与分发。

```bash
./gradlew assembleRelease
```

> ⚠️ 上架 Google Play 前请替换为自己的 keystore 与凭据。
> Google Play 自 2026-08-31 起要求 `targetSdk = 36`，本项目已满足。

---

## 🔒 权限说明

| 权限 | 必要性 | 说明 |
|---|---|---|
| `CAMERA` | 必需 | 不授予则完全无法使用 |
| `ACCESS_FINE/COARSE_LOCATION` | 可选 | 不授予仍可拍照，坐标行显示「定位未授权」 |
| `INTERNET` / 网络与 WiFi 状态 | 可选 | 用于腾讯定位与逆地理编码 |
| `VIBRATE` | 可选 | 快门震动反馈 |
| `WRITE_EXTERNAL_STORAGE`（≤ API 28） | 兼容 | API 29+ 走 MediaStore `RELATIVE_PATH`，无需该权限 |

**定位用 `android.location.LocationManager` + 腾讯定位 SDK，而非 Play Services `FusedLocationProvider`**——以兼容无 GMS 的国产 ROM。

---

## ⚠️ 已知风险与缓解

| 风险 | 缓解 |
|---|---|
| `OverlayEffect` 在部分国产 ROM 上 GL 合成异常 | `BitmapCompositor` 兜底，且绘制层共享同一份代码 |
| Preview / ImageCapture 宽高比不一致导致水印被裁 | 共用 `Viewport` + 统一 `ResolutionSelector`，真机验收 |
| 全分辨率 Bitmap 内存峰值（12MP ARGB_8888 ≈ 48MB） | 仅兜底路径触发，并按 `maxMemory/4` 降采样 |

---

## 📂 项目结构

```
app/src/main/java/com/sydeny/wmcamera/
├── data/          SettingsRepository · GalleryRepository · GalleryQuery · LogoStore
├── domain/        WatermarkConfig · WatermarkData
├── render/        WatermarkPainter · WatermarkCompositor · BitmapComposer · BitmapRotation
│                  LocationProvider · ReGeocoder · TencentLocationClient · MediaStoreSaver · LogoImage
├── ui/            MainActivity · CameraScreen · SettingsScreen · GalleryScreen · ViewModels
├── ServiceLocator.kt
└── WatermarkCameraApp.kt
```

设计文档见 [`docs/plans/2026-10-06-watermark-camera-design.md`](docs/plans/2026-10-06-watermark-camera-design.md)。

---

## 📄 License

个人学习与研究用途。设备型号、系统版本差异可能导致局部行为不一致，欢迎提 Issue 反馈。
