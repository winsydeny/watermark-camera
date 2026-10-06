package com.sydeny.wmcamera.render

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.sydeny.wmcamera.domain.AddressProvider
import com.sydeny.wmcamera.domain.LocationStatus
import com.sydeny.wmcamera.domain.WatermarkData
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 定位提供者。
 *
 * 用 Framework 的 [LocationManager] 而不是 Play Services 的
 * `FusedLocationProviderClient`：国产 ROM 常常没有 GMS，用后者在目标设备上
 * 直接跑不起来。代价是室内和城市环境下首个定位慢一些。
 *
 * 对外只暴露一个 [current] 状态，UI 层订阅它即可。
 *
 * 集成逆地理编码：定位更新时在后台线程异步解析地址，
 * 解析完成后通过 [notifyListeners] 通知 UI 刷新预览。
 */
class LocationProvider(
    context: Context,
    private val reGeocoder: ReGeocoder? = null,
) {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * 腾讯定位 SDK 客户端。用系统 LocationManager 之外的第二条腿：
     * SDK 走腾讯自家 WiFi 指纹 + 基站数据库，室内精度可以到 10-30m，
     * 而系统 network provider 通常只能给 50-500m 的粗结果。
     * 只在 Android 6+ 且 SDK 依赖成功打入时才有值。
     */
    private val tencentClient: TencentLocationClient? = runCatching {
        TencentLocationClient(appContext).also { c ->
            c.onNewLocation = { loc -> publish(loc) }
            c.onNewAddress = { addr ->
                // SDK 的 name 是云端粗匹配，常常只有园区/行政区级别（如"西安高新技术产业开发区"），
                // 精度不如 ReGeocoder 的 POI 选点。所以：
                // 1) 一旦 ReGeocoder 成功过，SDK 不再覆盖 lastAddress（避免闪烁）
                // 2) HTTP 至少试过 1 次并且失败，SDK 才允许填粗地址
                //    ——防止冷启动第一帧就被 SDK 粗地址抢跑，然后又被 HTTP 精修值闪一下
                if (!httpGeocodeSucceeded &&
                    httpAttempted > 0 &&
                    addr.isNotBlank() &&
                    addr != lastAddress
                ) {
                    lastAddress = addr
                    android.os.Handler(Looper.getMainLooper()).post { notifyListeners() }
                }
            }
        }
    }.getOrNull()

    private val listeners = mutableSetOf<() -> Unit>()
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = publish(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Framework 回调，仅为兼容旧版本")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    /**
     * 定位数据超过这个时长就当过期。
     * 相机场景下用户通常静止或缓慢移动，5 分钟内的定位仍然可信；
     * 室内 GPS 停止更新时不至于频繁退回"无信号"。
     */
    private val staleAfterMillis = 300_000L

    @Volatile
    private var lastLocation: Location? = null

    /** 逆地理编码缓存：每次定位变化后异步更新。 */
    @Volatile
    private var lastAddress: String? = null

    /** 当前高德 API key；由外部（CameraViewModel）在配置变化时同步过来。 */
    @Volatile
    var amapApiKey: String = ""

    /** 当前腾讯 API key。 */
    @Volatile
    var tencentApiKey: String = ""

    /** 腾讯 SK（签名校验密钥），空表示不签名。 */
    @Volatile
    var tencentSecretKey: String = ""

    /** 当前选用的地址解析服务商。 */
    @Volatile
    var addressProvider: AddressProvider = AddressProvider.AMAP

    /** 上一次腾讯 SDK 定位的时间戳。0 表示从未有过。 */
    @Volatile
    private var lastSdkFixAt: Long = 0L

    /** 本次会话 SDK 第一次给出 fix 的时间戳（不管精度）。0 表示从未有过。 */
    @Volatile
    private var firstSdkFixAt: Long = 0L

    /**
     * ReGeocoder HTTP 是否至少成功过一次。
     * 用来把 SDK 的 name 字段降级为"冷启动/HTTP 失败时的兜底"，
     * 避免 SDK 每秒回调和 HTTP 结果交替覆盖 lastAddress 造成水印闪烁。
     */
    @Volatile
    private var httpGeocodeSucceeded: Boolean = false

    /** HTTP 至少被尝试过几次；>0 意味着 SDK 可以开始兜底。 */
    @Volatile
    private var httpAttempted: Int = 0

    /** 上次 HTTP 逆地编实际发起时的坐标；配合 [GEOCODE_MOVE_THRESHOLD_M] 做位移去抖。 */
    @Volatile
    private var lastGeocodedLat: Double = Double.NaN
    @Volatile
    private var lastGeocodedLng: Double = Double.NaN

    /**
     * 上一次 geocode 返回的候选 POI 列表，UI 可展示为"位置选择面板"。
     * 首项即系统认为最匹配的那个。
     */
    private val _poiCandidates = MutableStateFlow<List<PoiOption>>(emptyList())
    val poiCandidates: StateFlow<List<PoiOption>> = _poiCandidates.asStateFlow()

    /**
     * 用户手动选中的地址；非 null 时优先于 [lastAddress] 展示。
     * 位置移动超过 [MANUAL_PICK_RESET_DISTANCE_M] 时会自动清空，
     * 以免用户带着旧地点的手选值跑遍全城。
     */
    @Volatile
    private var manualPick: String? = null
    @Volatile
    private var manualPickAnchorLat: Double = Double.NaN
    @Volatile
    private var manualPickAnchorLng: Double = Double.NaN

    /** 后台单线程执行 geocode 请求，避免并发。 */
    private val geocodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wm-geocode").apply { isDaemon = true }
    }

    @Volatile
    var permissionGranted: Boolean = false
        private set

    @Volatile
    var status: LocationStatus = LocationStatus.LOCATING
        private set

    /** 生成一份用于预览的实时水印数据。 */
    fun currentData(timestampMillis: Long): WatermarkData {
        val location = lastLocation
        val fresh = location != null &&
            timestampMillis - location.time <= staleAfterMillis
        return if (fresh && location != null) {
            WatermarkData(
                timestampMillis = timestampMillis,
                locationStatus = LocationStatus.FIXED,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                // 用户手选优先级最高；否则展示自动解析结果
                address = manualPick ?: lastAddress,
            )
        } else {
            WatermarkData(
                timestampMillis = timestampMillis,
                locationStatus = when {
                    !permissionGranted -> LocationStatus.NO_PERMISSION
                    location == null -> LocationStatus.LOCATING
                    else -> LocationStatus.NO_SIGNAL
                },
                address = manualPick,
            )
        }
    }

    /**
     * 用户从"位置选择面板"里手动挑一个候选。传 null 表示恢复自动匹配。
     * 记录锚点坐标，用户移动超过 [MANUAL_PICK_RESET_DISTANCE_M] 后自动清空。
     */
    fun selectManualPick(option: PoiOption?) {
        if (option == null) {
            manualPick = null
            manualPickAnchorLat = Double.NaN
            manualPickAnchorLng = Double.NaN
        } else {
            manualPick = option.display
            val loc = lastLocation
            if (loc != null) {
                manualPickAnchorLat = loc.latitude
                manualPickAnchorLng = loc.longitude
            }
        }
        notifyListeners()
    }

    fun observe(listener: () -> Unit) {
        synchronized(listeners) { listeners += listener }
        listener()
    }

    fun removeObserver(listener: () -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    /** 外部（如 API key 变化后）主动要求重跑一次逆地理编码。 */
    fun rerunGeocode() {
        val loc = lastLocation ?: return
        // 清掉缓存，让新结果能覆盖上去；同时放开 SDK 兜底和位移去抖，
        // 以防新 key 依然失败时至少能显示粗地址、下一帧也允许重发。
        lastAddress = null
        httpGeocodeSucceeded = false
        httpAttempted = 0
        lastGeocodedLat = Double.NaN
        lastGeocodedLng = Double.NaN
        _poiCandidates.value = emptyList()
        triggerGeocode(loc.latitude, loc.longitude)
    }

    /** 权限状态变化时调用。返回 true 表示已开始监听。 */
    fun onPermissionResult(granted: Boolean): Boolean {
        permissionGranted = granted
        if (!granted) {
            stopUpdates()
            lastLocation = null
            lastAddress = null
            httpGeocodeSucceeded = false
            httpAttempted = 0
            lastGeocodedLat = Double.NaN
            lastGeocodedLng = Double.NaN
            firstSdkFixAt = 0L
            manualPick = null
            manualPickAnchorLat = Double.NaN
            manualPickAnchorLng = Double.NaN
            _poiCandidates.value = emptyList()
            status = LocationStatus.NO_PERMISSION
            notifyListeners()
            return false
        }
        // 重置每轮会话的状态
        httpGeocodeSucceeded = false
        httpAttempted = 0
        lastGeocodedLat = Double.NaN
        lastGeocodedLng = Double.NaN
        firstSdkFixAt = 0L
        _poiCandidates.value = emptyList()
        // 先用最后一次已知位置把水印填上，避免用户一进来看到"定位中"干等好几秒。
        // 但缓存值有两个坑：
        // 1) 精度差（network provider 常年 300-600m），拿它 geocode 会拿到错的 POI
        // 2) 时间旧（上次会话遗留），坐标根本不是现在的位置
        // 只有 SDK 不可用时才用缓存坐标兜底 geocode；SDK 在的设备上
        // 宁可显示 1-2 秒"定位中…"，也等它给出准确 fix 再解析。
        val cached = bestLastKnownLocation()
        lastLocation = cached
        status = if (cached != null) LocationStatus.FIXED else LocationStatus.LOCATING
        val sdkAvailable = tencentClient != null
        val cachedIsTrustworthy = cached != null &&
            cached.accuracy <= COLD_START_GEOCODE_MAX_ACCURACY_M &&
            System.currentTimeMillis() - cached.time <= COLD_START_GEOCODE_MAX_AGE_MS
        if (!sdkAvailable && cachedIsTrustworthy && cached != null) {
            triggerGeocode(cached.latitude, cached.longitude)
        }
        notifyListeners()
        return startUpdates()
    }

    private fun publish(location: Location) {
        Log.i(
            TAG,
            "publish provider=${location.provider} lat=${location.latitude} " +
                "lng=${location.longitude} acc=${location.accuracy}m t=${location.time}",
        )
        val fromSdk = location.provider == SDK_PROVIDER
        if (fromSdk) {
            lastSdkFixAt = location.time
            if (firstSdkFixAt == 0L) firstSdkFixAt = location.time
        }
        // SDK 只要出过一次结果，短时间内它的坐标就是最可信的：
        // 系统 network provider 常年在室内漂 500m，用它做逆地编会拿到错的 POI。
        // 因此在 SDK 有效窗口内，忽略非 SDK 的坐标。
        if (!fromSdk &&
            System.currentTimeMillis() - lastSdkFixAt < SDK_PRIORITY_WINDOW_MS
        ) {
            Log.d(TAG, "reject: SDK priority window active")
            return
        }
        // 策略：始终接受更新的定位（时间戳更近），因为用户可能已经移动。
        // 仅拒绝明显偏差极大的粗定位（>10km），避免垃圾数据污染水印。
        val existing = lastLocation
        if (location.accuracy > MAX_ACCEPTABLE_ACCURACY_METERS) {
            Log.d(TAG, "reject: accuracy > ${MAX_ACCEPTABLE_ACCURACY_METERS}m")
            return
        }
        // 同一时间或更旧的结果，只有精度更高时才采纳
        if (existing != null && location.time <= existing.time
            && location.accuracy > existing.accuracy
        ) {
            Log.d(TAG, "reject: stale or worse accuracy")
            return
        }
        lastLocation = location
        status = LocationStatus.FIXED
        // 用户手选了地址但已经移动到新区域：清空手选，让自动匹配接管
        maybeClearManualPick(location)
        // SDK 定位自带 name 字段（腾讯云端已解析好的 POI 名），
        // 但 name 常常只是"西安高新技术产业开发区"这种泛化描述；
        // 依然触发一次 HTTP 逆地编拿到"夏日景色1区"级别的精确 POI 更靠谱。
        //
        // geocode 触发权限（严格）：
        // 1. 来自 SDK 的坐标：
        //    - 精度 <=200m：直接触发（正常路径）
        //    - 精度 >200m：先不触发。SDK 冷启动前 1-3 秒的 fix 常是 220-500m 的粗值，
        //      拿它 geocode 会短暂显示错误地址。等到 SDK 收敛再解析。
        //      超过 SDK_STUCK_TIMEOUT_MS 仍未收敛，就"退而求其次"用当前粗值。
        // 2. 来自系统 provider（network/fused/gps）：
        //    - SDK 可用：完全跳过（等 SDK 精修）
        //    - SDK 不可用（依赖没打入 / 初始化失败）：允许，精度 <=200m 时才用
        val sdkAvailable = tencentClient != null
        val accuracyOk = location.accuracy <= COLD_START_GEOCODE_MAX_ACCURACY_M
        // 首次 SDK fix 之后超过阈值时间，SDK 精度还没收敛（室内/边缘情况），
        // 就"退而求其次"用它——一直显示"定位中"更糟
        val sdkStuckTooLong = firstSdkFixAt > 0L &&
            System.currentTimeMillis() - firstSdkFixAt > SDK_STUCK_TIMEOUT_MS
        val shouldGeocode = when {
            fromSdk -> accuracyOk || sdkStuckTooLong
            sdkAvailable -> false // 等 SDK 出结果，别用系统粗坐标抢跑
            accuracyOk -> true
            else -> false
        }
        if (shouldGeocode) {
            triggerGeocode(location.latitude, location.longitude)
        }
        notifyListeners()
    }

    private fun maybeClearManualPick(location: Location) {
        if (manualPick == null) return
        if (manualPickAnchorLat.isNaN()) return
        val moved = haversineMeters(
            manualPickAnchorLat, manualPickAnchorLng,
            location.latitude, location.longitude,
        )
        if (moved > MANUAL_PICK_RESET_DISTANCE_M) {
            manualPick = null
            manualPickAnchorLat = Double.NaN
            manualPickAnchorLng = Double.NaN
        }
    }

    /** 异步触发逆地理编码，结果到达后再次通知 listeners。 */
    private fun triggerGeocode(latitude: Double, longitude: Double) {
        val geocoder = reGeocoder ?: return
        // 位移去抖：只要最近一次"发起"的坐标在阈值内，就不再重复请求。
        // 关键：不要求上一次请求已经完成——否则 SDK 1Hz 回调期间，
        // 每秒都会看到 `httpGeocodeSucceeded=false` → 通过 → 排队 → 又发一个 HTTP，
        // 结果一秒内堆出好几个 in-flight 请求，返回顺序不定时还会导致 lastAddress 抖动。
        if (!lastGeocodedLat.isNaN()) {
            val moved = haversineMeters(
                lastGeocodedLat, lastGeocodedLng, latitude, longitude,
            )
            if (moved < GEOCODE_MOVE_THRESHOLD_M) return
        }
        lastGeocodedLat = latitude
        lastGeocodedLng = longitude
        httpAttempted++
        val provider = addressProvider
        val amap = amapApiKey
        val tencent = tencentApiKey
        val sk = tencentSecretKey
        geocodeExecutor.execute {
            val result = geocoder.reverseGeocodeDetailed(
                latitude, longitude, provider, amap, tencent, sk,
            )
            val address = result?.composed
            Log.d("wm-geocode", "resolve provider=$provider amap=${amap.take(4)} tencent=${tencent.take(4)} sk=${sk.take(4)} -> $address (candidates=${result?.candidates?.size ?: 0})")
            if (address != null) {
                _poiCandidates.value = result?.candidates ?: emptyList()
                httpGeocodeSucceeded = true
                if (address != lastAddress) {
                    lastAddress = address
                    android.os.Handler(Looper.getMainLooper()).post {
                        notifyListeners()
                    }
                }
            } else {
                // 失败时允许下一次定位再试，不要被去抖卡死
                lastGeocodedLat = Double.NaN
                lastGeocodedLng = Double.NaN
            }
        }
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    private fun bestLastKnownLocation(): Location? {
        val manager = locationManager ?: return null
        return try {
            listOf(
                "fused",
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ).mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.minByOrNull { it.accuracy }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun startUpdates(): Boolean {
        val manager = locationManager ?: return false
        if (!hasPermission()) return false
        var registered = false
        // 启动腾讯 SDK 定位（走腾讯自家 WiFi 指纹数据库，室内精度显著优于系统 provider）
        runCatching { tencentClient?.start() }
            .onFailure { Log.w(TAG, "tencent sdk start failed: ${it.javaClass.simpleName}: ${it.message}") }
        // 除了 GPS 和网络定位，还订阅 PASSIVE：其他 App（微信/地图）产生的精准定位
        // 会被系统 passive provider 镜像过来，等于白捡的高精度结果。
        // 另外枚举所有 provider，尝试订阅厂商融合 provider（如 MIUI 的 fused）。
        // MIUI 的 "fused" provider（com.xiaomi.location.fused）不在 allProviders 列表里返回，
        // 但用字符串名可以直接注册，它结合了 GPS+WiFi+基站，室内精度显著优于单纯 network。
        val candidates = LinkedHashSet<String>().apply {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            add("fused") // Xiaomi/Huawei/Oppo 等厂商融合定位
            runCatching { manager.allProviders }.getOrNull()?.let {
                Log.d(TAG, "allProviders=$it")
                addAll(it)
            }
        }
        for (provider in candidates) {
            try {
                val enabled = manager.isProviderEnabled(provider)
                // MIUI 的 fused provider 对第三方 App 屏蔽了 isProviderEnabled 检查，
                // 但直接 requestLocationUpdates 有时能成功；因此对 "fused" 特殊放行。
                val forceTry = provider == "fused"
                Log.d(TAG, "try provider=$provider enabled=$enabled")
                if (enabled || forceTry) {
                    manager.requestLocationUpdates(
                        provider,
                        MIN_INTERVAL_MILLIS,
                        MIN_DISTANCE_METERS,
                        locationListener,
                        Looper.getMainLooper(),
                    )
                    registered = true
                    Log.d(TAG, "registered provider=$provider")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "provider=$provider denied by SecurityException: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "provider=$provider not available on device")
            }
        }
        return registered
    }

    private fun stopUpdates() {
        val manager = locationManager ?: return
        runCatching { tencentClient?.stop() }
        runCatching { manager.removeUpdates(locationListener) }
    }

    fun release() {
        stopUpdates()
        geocodeExecutor.shutdownNow()
        synchronized(listeners) { listeners.clear() }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it() }
    }

    private companion object {
        const val TAG = "wm-locate"
        const val MIN_INTERVAL_MILLIS = 1_000L
        const val MIN_DISTANCE_METERS = 1f
        /** 和 TencentLocationClient.PROVIDER_NAME 保持一致 */
        const val SDK_PROVIDER = "tencent-sdk"
        /** SDK 有过结果后，这段时间内忽略其他 provider 的坐标。 */
        const val SDK_PRIORITY_WINDOW_MS = 15_000L
        /** 精度超过 10km 的粗定位视为垃圾，不展示给用户。 */
        const val MAX_ACCEPTABLE_ACCURACY_METERS = 10_000f
        /**
         * 位移超过这个阈值才重新触发 HTTP 逆地编。
         * SDK 精度约 10-30m；小于 60m 的抖动通常是原地不动时的坐标噪声，
         * 重新查一次也只会拿到同一个 POI，白烧额度。
         */
        const val GEOCODE_MOVE_THRESHOLD_M = 60.0
        /**
         * 冷启动时缓存坐标精度超过这个值就不立刻 geocode，等 SDK 提供准确 fix。
         * 200m 是室内 network provider 常见的漂移量，用它 geocode 会拿到错的 POI，
         * 然后 SDK 修正时又闪回正确 POI，形成用户看到的"先显示错误地址"现象。
         */
        const val COLD_START_GEOCODE_MAX_ACCURACY_M = 200f
        /**
         * 冷启动时缓存坐标"多久之前"的上限。60 秒内的缓存才被信任；
         * 更旧的可能是上次会话在别处的位置（用户可能已经开车换地方了），
         * 拿它 geocode 会显示一个明显错的地址，随后 SDK 修正时闪回。
         */
        const val COLD_START_GEOCODE_MAX_AGE_MS = 60_000L
        /**
         * 用户手选地址后，一旦真实位置离锚点超过这个距离就清空手选，
         * 让自动匹配接管。150m 大致是"从一栋楼走到隔壁街区"的量级，
         * 用户在这个距离内一般仍然认为手选地址有效。
         */
        const val MANUAL_PICK_RESET_DISTANCE_M = 150.0
        /**
         * SDK 冷启动后一直没收敛到 <=200m 精度时的兜底窗口。
         * 6 秒后就用它当前给的最准坐标，别再让水印空着"定位中"。
         */
        const val SDK_STUCK_TIMEOUT_MS = 6_000L
    }
}
