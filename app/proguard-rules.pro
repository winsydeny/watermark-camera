# 本地打包用的 ProGuard/R8 规则。
# 只加"不加就一定会挂"的部分——默认 proguard-android-optimize.txt 已经
# 覆盖了 Android 框架层的 keep 规则，这里只补第三方 SDK 和用户反射的地方。

# ---------------- 腾讯定位 SDK ----------------
# 通过 JNI + 反射加载内部类；混淆后包名/字段变了会 ClassNotFoundException 或
# NoSuchMethodError，症状通常是"SDK 静默不返回结果"，很难定位。
-keep class com.tencent.map.geolocation.** { *; }
-dontwarn com.tencent.map.geolocation.**

# ---------------- CameraX ----------------
# CameraX 里若干内部类通过 Camera2 的 @ExperimentalCamera2Interop 反射访问。
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---------------- Compose ----------------
# Compose 编译器生成的类里含大量 lambda / 内部类，R8 有时候会误伤。
# 官方 compose-bom 的 consumer rules 已经处理了大部分，这里加一层保险。
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ---------------- Kotlin metadata ----------------
# 反射时用到 Kotlin 的 @Metadata 注解；默认规则里已经保留，但 shrink 场景容易丢。
-keepattributes *Annotation*, InnerClasses, Signature

# ---------------- 应用层 ----------------
# 本项目 ServiceLocator / ViewModelProvider.Factory 走的是显式引用，无反射；
# 不额外加 keep。若日后加了 DI 框架或序列化，再补规则。
