package com.sydeny.wmcamera.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sydeny.wmcamera.R

@Composable
fun WatermarkCameraRoot(
    cameraGranted: Boolean,
    locationGranted: Boolean,
    cameraPermissionPermanentlyDenied: Boolean,
    onRequestCameraPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val viewModel: CameraViewModel = viewModel(factory = CameraViewModel.Factory)
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }

    // 权限结果变化时同步给定位提供者，保证水印上的坐标状态跟着变
    LaunchedEffect(locationGranted) {
        viewModel.onLocationPermissionResult(locationGranted)
    }

    // 系统返回键：相册打开时优先关相册，其次关设置；两个都没开时不拦截（让 Activity 正常返回）
    BackHandler(enabled = showGallery || showSettings) {
        when {
            showGallery -> showGallery = false
            showSettings -> showSettings = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            !cameraGranted -> PermissionGate(
                permanentlyDenied = cameraPermissionPermanentlyDenied,
                onGrant = onRequestCameraPermission,
                onOpenSettings = onOpenAppSettings,
            )

            else -> CameraScreen(
                viewModel = viewModel,
                onOpenSettings = { showSettings = true },
                onOpenGallery = { showGallery = true },
            )
        }

        if (showSettings) {
            SettingsScreen(
                viewModel = viewModel,
                onClose = { showSettings = false },
            )
        }

        // 相册盖在相机上面：返回时相机还在后台跑着，不用重新绑相机
        if (showGallery) {
            GalleryScreen(onClose = { showGallery = false })
        }

        CameraEventHost(viewModel)
    }
}

/** 一次性事件（Toast）的宿主。 */
@Composable
private fun CameraEventHost(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val saved = stringResource(R.string.toast_saved)
    val failedTemplate = stringResource(R.string.toast_save_failed)

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                CameraEvent.Saved -> saved
                is CameraEvent.SaveFailed -> failedTemplate.format(event.reason)
            }
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun PermissionGate(
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.permission_camera_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(
                if (permanentlyDenied) R.string.permission_camera_denied_body
                else R.string.permission_camera_body,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = if (permanentlyDenied) onOpenSettings else onGrant) {
            Text(
                stringResource(
                    if (permanentlyDenied) R.string.action_open_settings
                    else R.string.action_grant,
                ),
            )
        }
    }
}
