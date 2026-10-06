package com.sydeny.wmcamera.render

import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.tencent.map.geolocation.TencentLocation
import com.tencent.map.geolocation.TencentLocationListener
import com.tencent.map.geolocation.TencentLocationManager
import com.tencent.map.geolocation.TencentLocationRequest

/**
 * 腾讯定位 SDK 客户端。
 *
 * 相比 Android 系统 [android.location.LocationManager] 的 network provider，
 * 腾讯 SDK 用自家云端 WiFi 指纹数据库，室内精度能到 10-30m；系统 provider 在
 * 同样环境下经常给出 100-500m 的粗结果。
 *
 * 生命周期跟 [LocationProvider] 一致：[start] 注册回调，[stop] 释放。
 * SDK 内部自己有线程模型，我们只需要保证 listener 在主线程即可。
 */
class TencentLocationClient(context: Context) {

    private val appContext = context.applicationContext
    private val client = TencentLocationManager.getInstance(appContext)
    private var started = false

    /** 收到一次新的 SDK 定位。回调可能在主线程。 */
    var onNewLocation: ((Location) -> Unit)? = null

    /** SDK 顺路返回的地址字符串（含腾讯 WiFi 库里的 POI/路名）。 */
    var onNewAddress: ((String) -> Unit)? = null

    private val listener = object : TencentLocationListener {
        override fun onLocationChanged(
            location: TencentLocation?,
            error: Int,
            message: String?,
        ) {
            if (location == null) {
                Log.d(TAG, "sdk callback null location, error=$error msg=$message")
                return
            }
            Log.d(
                TAG,
                "sdk update lat=${location.latitude} lng=${location.longitude} " +
                    "acc=${location.accuracy}m error=$error name=${location.name}",
            )
            val android = Location(PROVIDER_NAME).apply {
                latitude = location.latitude
                longitude = location.longitude
                accuracy = location.accuracy.coerceAtLeast(1f)
                time = System.currentTimeMillis()
                if (location.altitude > 0.0) altitude = location.altitude
            }
            onNewLocation?.invoke(android)
            val name = location.name
            if (error == TencentLocation.ERROR_OK && !name.isNullOrBlank()) {
                onNewAddress?.invoke(name)
            }
        }

        override fun onStatusUpdate(provider: String?, status: Int, message: String?) {
            Log.d(TAG, "status provider=$provider status=$status msg=$message")
        }
    }

    fun start() {
        if (started) return
        val request = TencentLocationRequest.create()?.apply {
            setInterval(REQUEST_INTERVAL_MS)
            setAllowGPS(true)
            // NAME 级别：name 字段会是"具体小区/大楼"（比 ADMIN_AREA 更精细）
            // POI 级别：name 变成 null，需要读 poiList；这里为了简单用 NAME
            setRequestLevel(TencentLocationRequest.REQUEST_LEVEL_NAME)
        } ?: return
        val ok = client.requestLocationUpdates(request, listener, Looper.getMainLooper())
        started = (ok == 0)
        Log.d(TAG, "start requestId=$ok started=$started")
    }

    fun stop() {
        if (!started) return
        client.removeUpdates(listener)
        started = false
    }

    private companion object {
        const val TAG = "wm-txloc"
        const val REQUEST_INTERVAL_MS = 1_000L
        const val PROVIDER_NAME = "tencent-sdk"
    }
}
