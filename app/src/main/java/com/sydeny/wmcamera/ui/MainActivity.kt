package com.sydeny.wmcamera.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // 初始值只能给 false：属性初始化发生在 Activity 构造期，
    // 那时 base context 还没挂上，ContextCompat.checkSelfPermission 会 NPE。
    // 真实状态在 onCreate 里读一次。
    var cameraGranted by mutableStateOf(false)
        private set

    var locationGranted by mutableStateOf(false)
        private set

    /** 相机权限被永久拒绝后再也弹不出系统对话框，得靠这个引导去设置页。 */
    var cameraPermissionPermanentlyDenied by mutableStateOf(false)
        private set

    // 字段初始化时注册，早于 onStart，符合 ActivityResultRegistry 的要求
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted = granted
            if (granted) requestLocationIfNeeded()
        }

    private val locationLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            // 精略定位都可能拿到，直接重读一遍，别在这里重复一遍判定逻辑
            refreshPermissionState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 必须在 setContent 之前读，Compose 第一次组合就要用这两个值决定进不进相机页
        refreshPermissionState()

        setContent {
            WatermarkCameraTheme {
                WatermarkCameraRoot(
                    cameraGranted = cameraGranted,
                    locationGranted = locationGranted,
                    cameraPermissionPermanentlyDenied = cameraPermissionPermanentlyDenied,
                    onRequestCameraPermission = {
                        cameraPermissionPermanentlyDenied = false
                        cameraLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onRequestLocationPermission = {
                        locationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                    onOpenAppSettings = ::openAppSettings,
                )
            }
        }
    }

    /**
     * 定位是软门槛，不授予照样能拍照，所以只在相机权限已就绪、定位权限还没问过的时候才主动弹。
     */
    private fun requestLocationIfNeeded() {
        if (!locationGranted &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    private fun openAppSettings() {
        cameraPermissionPermanentlyDenied = true
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    override fun onResume() {
        super.onResume()
        // 从系统对话框或设置页返回时同步一次权限状态
        refreshPermissionState()
        if (cameraGranted) requestLocationIfNeeded()
    }

    private fun refreshPermissionState() {
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        locationGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 全局 MaterialTheme。
 *
 * 强制 dark color scheme：本 App 视觉主体是相机预览（永远是深色画面），
 * 二级页（水印设置、相册）沿用 dark scheme 后 `Scaffold` 的 TopAppBar
 * 背景变深，白色标题和图标才有对比。
 *
 * 之前没包这层，Settings/Gallery 用的是 MaterialTheme 默认的 light scheme，
 * 关（Close）图标的白 tint 打在浅灰 surface 上，几乎看不清。
 */
@Composable
fun WatermarkCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
