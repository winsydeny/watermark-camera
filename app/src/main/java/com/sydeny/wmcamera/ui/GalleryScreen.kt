package com.sydeny.wmcamera.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sydeny.wmcamera.R
import com.sydeny.wmcamera.data.GalleryPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册页：只列本 App 拍的照片，网格缩略图，点开看大图。
 *
 * 刻意不跳系统相册——那样点开一张照片之后就跳出去了，用户想看下一张还得再点回来，
 * 而且各家的相册界面样式不可控。
 */
@Composable
fun GalleryScreen(
    onClose: () -> Unit,
    viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<GalleryPhoto?>(null) }

    // GalleryViewModel 挂在 Activity 的 ViewModelStore 上，第一次进相册 init
    // 会 load 一次；之后 showGallery 关掉再打开，同一份 ViewModel 被复用，
    // init 不会重跑，用户看不到新拍的照片。这里显式 reload 一次，等价于
    // "每次进入这一屏都刷新"。
    LaunchedEffect(Unit) { viewModel.reload() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        GalleryTopBar(onClose)

        when (val current = state) {
            GalleryState.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }

            GalleryState.Empty -> CenteredMessage(stringResource(R.string.gallery_empty))

            is GalleryState.Failed -> CenteredMessage(
                stringResource(R.string.gallery_load_failed, current.reason),
            )

            is GalleryState.Ready -> LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(current.photos, key = { it.uri.toString() }) { photo ->
                    PhotoThumbnail(
                        photo = photo,
                        onClick = { opened = photo },
                    )
                }
            }
        }
    }

    opened?.let { photo ->
        PhotoViewer(
            photo = photo,
            onClose = { opened = null },
        )
    }
}

@Composable
private fun GalleryTopBar(onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 顶部避让系统状态栏，否则返回按钮会和刘海/状态栏图标叠在一起
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = Color.White,
            )
        }
        Text(
            text = stringResource(R.string.gallery_title),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PhotoThumbnail(photo: GalleryPhoto, onClick: () -> Unit) {
    val bitmap by rememberDecoded(photo.uri, THUMBNAIL_PX)
    Box(
        Modifier
            // 格子必须是 3:4，不能是 1:1。
            // 缩略图是竖的（480x640），塞进正方块再 ContentScale.Crop 会从上下
            // 各切掉 12.5%，而水印正好画在最底下 5%~9.5%——正好被切没，
            // 缩略图看起来就是一片黑。格子比例和照片一致时才切不到东西。
            .aspectRatio(3f / 4f)
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PhotoViewer(photo: GalleryPhoto, onClose: () -> Unit) {
    // 大图得给足像素才有意义，和缩略图不是一个数量级
    val bitmap by rememberDecoded(photo.uri, FULL_IMAGE_PX)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 在 IO 线程上按需解码一张图。
 *
 * 绝不能直接把 12MP 的 JPEG 读进内存当缩略图——那正是拍照时踩过的
 * 内存峰值。所以先只读 `inJustDecodeBounds` 拿到原始尺寸，
 * 再按 `inSampleSize` 降采样。
 */
@Composable
private fun rememberDecoded(
    uri: Uri,
    targetPx: Int,
    context: Context = LocalContext.current,
): State<Bitmap?> = produceState<Bitmap?>(initialValue = null, uri, targetPx) {
    value = withContext(Dispatchers.IO) {
        runCatching { decodeSampled(context.contentResolver, uri, targetPx) }
            .getOrNull()
    }
}

private fun decodeSampled(
    resolver: ContentResolver,
    uri: Uri,
    targetPx: Int,
): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return resolver.loadThumbnail(uri, Size(targetPx, targetPx), null)
    }

    // Android 9 及更早没有 loadThumbnail，老老实实自己降采样
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        // 缩略图不需要 alpha 通道，RGB_565 直接省一半内存
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

/** 算出 2 的幂次降采样系数，让最长边落在 [targetPx] 附近。 */
private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= targetPx) {
        longest /= 2
        sample *= 2
    }
    return sample
}

private const val GRID_COLUMNS = 3
private const val THUMBNAIL_PX = 480
private const val FULL_IMAGE_PX = 2048
