package com.sydeny.wmcamera.render

import android.content.Context
import android.util.Log
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.core.content.ContextCompat
import com.sydeny.wmcamera.domain.WatermarkConfig
import com.sydeny.wmcamera.domain.WatermarkData
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 水印合成策略。
 *
 * 抽出这个接口的原因只有一个：把「水印怎么画」（[WatermarkPainter]）和
 * 「水印画到哪」（本接口）分开。前者不依赖任何 CameraX 类型，两者因此可以自由组合。
 *
 * 注意预览水印**不走这里**——预览由 UI 层直接调 [WatermarkPainter] 画到 Compose
 * Canvas 上。两条路共用同一个 Painter 和同一套按宽度比例推导的尺寸，所以
 * 预览和成片的水印在视觉上必然一致。
 *
 * 目前只有一个实现：[BitmapCompositor]。
 */
interface WatermarkCompositor {

    fun createUseCaseGroup(
        preview: Preview,
        imageCapture: ImageCapture,
        viewport: ViewPort?,
    ): UseCaseGroup

    fun takePicture(
        imageCapture: ImageCapture,
        context: Context,
        config: WatermarkConfig,
        snapshot: WatermarkData,
        onResult: (Result<Uri>) -> Unit,
    )

    fun onRelease()
}

/**
 * 拍完拿 [ImageProxy]，解码 Bitmap，用同一个 [WatermarkPainter] 画上去再编码。
 *
 * ## 为什么不用 CameraX 的 OverlayEffect
 *
 * 原本首选 `androidx.camera.effects.OverlayEffect` 走 GPU 合成，但它在这台
 * 真机（小米 M2104K10AC / Android 12 / Mali）上做不出竖屏成片，实测三件事：
 *
 * 1. `OverlayEffect` 不接受单独的 `CameraEffect.IMAGE_CAPTURE`。
 *    1.6.2 的构造器直接抛
 *    `IllegalArgumentException: Effects target IMAGE_CAPTURE is not in the
 *    supported list [PREVIEW, VIDEO_CAPTURE, PREVIEW|VIDEO_CAPTURE,
 *    IMAGE_CAPTURE|PREVIEW|VIDEO_CAPTURE]`，
 *    含 `IMAGE_CAPTURE` 的组合只有"三个全带"这一个。
 * 2. 带全三个目标后，预览被拖到约 1.6 fps，`Surface.lockCanvas` 之后再无进展，
 *    `setOnDrawListener` 的回调**一次都没有被调用**。
 * 3. 按下快门后日志里有 `ImageCapture: takePictureInternal`，
 *    但 `onImageSaved` / `onError` 永远不来，照片不落盘。
 *
 * 关于现象 3 要说明清楚：它是在把目标旋转误设成 **90 度**（应为 `Surface.ROTATION_*`
 * 常量，见 [com.sydeny.wmcamera.ui.portraitTargetRotation]）之后才观察到的，
 * 换句话说这条证据当时被自己的 bug 污染了，不能单独用来判 OverlayEffect 的死刑。
 * 真正干净的证据是 1 和 2：构造器直接拒绝，以及预览线程被拖到 ~1.6 fps 且
 * 绘制回调一次都不触发。CPU 路径已经跑通，GPU 路径若要复活，
 * 需要先用正确的旋转常量把现象 3 重测一遍。
 *
 * 所以 GPU 路径暂时砍掉，只留 CPU 路径。代价是 12MP 解码峰值约 48MB、
 * 整条链路多几百毫秒；换来的是完全不依赖厂商 OpenGL 实现，行为可预测。
 */
class BitmapCompositor(
    private val painter: WatermarkPainter,
) : WatermarkCompositor {

    override fun createUseCaseGroup(
        preview: Preview,
        imageCapture: ImageCapture,
        viewport: ViewPort?,
    ): UseCaseGroup = UseCaseGroup.Builder()
        .addUseCase(preview)
        .addUseCase(imageCapture)
        .setViewPort(viewport)
        .build()

    override fun takePicture(
        imageCapture: ImageCapture,
        context: Context,
        config: WatermarkConfig,
        snapshot: WatermarkData,
        onResult: (Result<Uri>) -> Unit,
    ) {
        imageCapture.takePicture(
            // 12MP 的解码—绘制—编码有几百毫秒，绝不能占着主线程；
            // 但 ImageProxy 必须在其对应的线程上关闭，所以回主线程收尾。
            imageExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.d(TAG, "capture ${image.width}x${image.height} rot=${image.imageInfo.rotationDegrees}")
                    image.use { proxy ->
                        runCatching {
                            val bitmap = BitmapComposer.paint(
                                proxy = proxy,
                                painter = painter,
                                config = config,
                                data = snapshot,
                                rotationDegrees = BitmapRotation.degreesFor(
                                    imageCapture.targetRotation,
                                ),
                            )
                            try {
                                val saver = MediaStoreSaver(context)
                                val entry = saver.createPendingEntry(snapshot.timestampMillis)
                                saver.writeBytes(entry, bitmap)
                                saver.finalize(entry)
                                entry.uri
                            } finally {
                                bitmap.recycle()
                            }
                        }.onSuccess { uri ->
                            Log.d(TAG, "saved $uri")
                            onResult(Result.success(uri))
                        }.onFailure { error ->
                            Log.e(TAG, "capture failed", error)
                            onResult(Result.failure(error))
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onResult(Result.failure(exception))
                }
            },
        )
    }

    override fun onRelease() = Unit

    private companion object {
        private const val TAG = "wm-capture"

        /**
         * 单线程串行即可：快门在 [com.sydeny.wmcamera.ui.CameraViewModel] 里
         * 已经有 PROCESSING 态挡着，不会有并发。
         */
        val imageExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "wm-capture").apply { isDaemon = true }
        }
    }
}
