import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 从 local.properties（已在 .gitignore 里）读取密钥，注入到 BuildConfig 和
 * AndroidManifest 的 manifestPlaceholder。**永远不要把密钥写进源码**——
 * 一旦进 commit 就算 git filter-repo 也容易被 GitHub 的爬虫扫到。
 *
 * 新克隆本仓库的人请照着 `local.properties.example` 复制一份并填值；
 * 没有本地文件时构建仍然会过，只是解析出来的地址会退回系统 Geocoder 精度。
 */
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, default: String = ""): String =
    localProperties.getProperty(key, default).trim()

android {
    namespace = "com.sydeny.wmcamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sydeny.wmcamera"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 密钥注入到 BuildConfig（Kotlin 层）和 AndroidManifest 占位符
        buildConfigField("String", "AMAP_API_KEY", "\"${secret("amapApiKey")}\"")
        buildConfigField("String", "TENCENT_API_KEY", "\"${secret("tencentApiKey")}\"")
        buildConfigField("String", "TENCENT_SECRET_KEY", "\"${secret("tencentSecretKey")}\"")
        // 腾讯定位 SDK 从 AndroidManifest 的 meta-data 读 AK，走 placeholder
        manifestPlaceholders["tencentMapSdk"] = secret("tencentApiKey")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 本地打包：默认走 Android SDK 自带的 debug.keystore（`~/.android/debug.keystore`），
            // 生成的 APK 可以直接安装到任何设备，方便自测/分发。
            // 上架 Google Play 之前请换成自己的 keystore 和凭据。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24 上用 java.time（DateTimeFormatter 线程安全且不可变），靠脱糖实现
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // AGP 8+ 默认关闭；打开才能生成 BuildConfig 类
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    val cameraXVersion = "1.6.2"
    val composeBomVersion = "2024.10.01"

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation(platform("androidx.compose:compose-bom:$composeBomVersion"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.camera:camera-effects:$cameraXVersion")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 腾讯定位 SDK：室内 WiFi 指纹定位，精度显著优于系统 network provider
    implementation("com.tencent.map.geolocation:TencentLocationSdk-openplatform:7.2.6")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
