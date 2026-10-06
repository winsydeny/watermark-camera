package com.sydeny.wmcamera.render

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import com.sydeny.wmcamera.domain.AddressProvider
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 一个可被用户手动选中的地址候选。
 *
 * [display] 是最终显示到水印上的字符串（已包含"市+区+POI 名"前缀），
 * [title] 是 POI 原始名字（如"城市风景·夏日景色1区"），
 * [distanceMeters] 是用户坐标到这个 POI 的实际距离。
 */
data class PoiOption(
    val display: String,
    val title: String,
    val category: String,
    val distanceMeters: Double,
)

/**
 * 一次逆地理编码的完整结果：显示用的字符串 + 可切选的候选列表。
 * [candidates] 首项即 [composed]（默认选中的那个）。
 */
data class ReGeocodeResult(
    val composed: String,
    val candidates: List<PoiOption>,
)

/**
 * 逆地理编码器。
 *
 * 支持两条主路径 + 一条兜底：
 * - [AddressProvider.AMAP]：高德 Web 服务，`restapi.amap.com/v3/geocode/regeo`
 * - [AddressProvider.TENCENT]：腾讯位置服务，`apis.map.qq.com/ws/geocoder/v1/`
 * - 未配置 key 或请求失败时，回退到 Android 框架自带的 [Geocoder]
 *
 * 三家在国内的地址覆盖都远好于系统 Geocoder，能拿到小区/大楼级信息。
 *
 * 调用方在后台线程使用，[reverseGeocode] 是阻塞 I/O。
 */
class ReGeocoder(context: Context) {

    private val appContext = context.applicationContext
    private val systemGeocoder = Geocoder(appContext, Locale.CHINA)

    /**
     * 将经纬度解析为可读地址字符串。
     *
     * 降级链：
     * 1. 优先使用设置里选中的服务商（前提是对应 Key 已填）
     * 2. 主选失败或未填 Key 时，尝试另一个服务商（同样要求 Key 已填）
     * 3. 两个 Key 都没填 / 都失败时，回退到 Android 系统 Geocoder
     *
     * 返回 null 表示所有路径都失败。必须在非主线程调用。
     */
    fun reverseGeocode(
        latitude: Double,
        longitude: Double,
        provider: AddressProvider,
        amapKey: String,
        tencentKey: String,
        tencentSecretKey: String,
    ): String? = reverseGeocodeDetailed(
        latitude, longitude, provider, amapKey, tencentKey, tencentSecretKey,
    )?.composed

    /**
     * 同 [reverseGeocode]，但同时返回可切选的候选 POI 列表，
     * 供 UI 上的"位置选择"面板使用。
     */
    fun reverseGeocodeDetailed(
        latitude: Double,
        longitude: Double,
        provider: AddressProvider,
        amapKey: String,
        tencentKey: String,
        tencentSecretKey: String,
    ): ReGeocodeResult? {
        val primary: AddressProvider = provider
        val secondary: AddressProvider = if (provider == AddressProvider.AMAP) {
            AddressProvider.TENCENT
        } else {
            AddressProvider.AMAP
        }
        return attempt(latitude, longitude, primary, amapKey, tencentKey, tencentSecretKey)
            ?: attempt(latitude, longitude, secondary, amapKey, tencentKey, tencentSecretKey)
            ?: reverseGeocodeViaSystem(latitude, longitude)
    }

    private fun attempt(
        latitude: Double,
        longitude: Double,
        provider: AddressProvider,
        amapKey: String,
        tencentKey: String,
        tencentSecretKey: String,
    ): ReGeocodeResult? = when (provider) {
        AddressProvider.AMAP ->
            if (amapKey.isNotBlank()) reverseGeocodeViaAmap(latitude, longitude, amapKey) else null
        AddressProvider.TENCENT ->
            if (tencentKey.isNotBlank())
                reverseGeocodeViaTencent(latitude, longitude, tencentKey, tencentSecretKey)
            else null
    }

    // ---------------- 高德 ----------------

    private fun reverseGeocodeViaAmap(
        latitude: Double,
        longitude: Double,
        key: String,
    ): ReGeocodeResult? {
        // 高德 location 参数是 "lng,lat"，顺序反了会返回"参数错误"
        val url = AMAP_ENDPOINT +
            "?key=${urlEncode(key)}" +
            "&location=$longitude,$latitude" +
            "&extensions=all" +
            "&radius=$RADIUS_METERS"
        val body = httpGet(url) ?: return null
        return try {
            val root = JSONObject(body)
            if (root.optString("status") != "1") {
                Log.d(TAG, "amap status=${root.optString("status")} info=${root.optString("info")}")
                return null
            }
            val regeocode = root.optJSONObject("regeocode") ?: return null
            val component = regeocode.optJSONObject("addressComponent") ?: JSONObject()

            val province = component.optStringOrNull("province")
            val city = component.optStringOrNull("city") ?: province // 直辖市 city 是空数组
            val district = component.optStringOrNull("district")
            val township = component.optStringOrNull("township")

            val neighborhood = component.pickNameOrNull("neighborhood")
            val building = component.pickNameOrNull("building")
            val formatted = regeocode.optStringOrNull("formatted_address")

            // 高德 pois 数组：{ name, distance (meters, string), type, address, location }
            val poiOptions: List<PoiOption> = regeocode.optJSONArray("pois")
                ?.scoreAmapPois(latitude, longitude, province, city, district, township)
                ?: emptyList()

            // 高德的 neighborhood/building 通常比 pois 更贴近"用户所在的具体位置"，
            // 手动插到候选队首（若有的话）。
            val specials = listOfNotNull(
                neighborhood?.let {
                    PoiOption(
                        display = composeAddress(province, city, district, it, formatted, township) ?: it,
                        title = it,
                        category = "住宅小区",
                        distanceMeters = 0.0,
                    )
                },
                building?.let {
                    PoiOption(
                        display = composeAddress(province, city, district, it, formatted, township) ?: it,
                        title = it,
                        category = "商务楼宇",
                        distanceMeters = 0.0,
                    )
                },
            )
            val allOptions = (specials + poiOptions).distinctBy { it.title }
            val top = allOptions.firstOrNull()
            val specific = top?.title
                ?: regeocode.optJSONArray("pois")?.pickFirstNonEmpty("name")
            val composed = composeAddress(province, city, district, specific, formatted, township)
                ?: return null
            ReGeocodeResult(
                composed = composed,
                candidates = allOptions.ifEmpty {
                    listOf(PoiOption(composed, specific ?: composed, "", 0.0))
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "amap parse failed: ${e.message}")
            null
        }
    }

    /**
     * 高德 `neighborhood` / `building` 是 `{ name: [] | "str", type: ... }` 结构，
     * name 可能是空数组 `[]` 或逗号分隔字符串。
     */
    private fun JSONObject.pickNameOrNull(key: String): String? {
        val obj = optJSONObject(key) ?: return null
        val raw = opt("name") ?: return null
        val str = when (raw) {
            is JSONArray -> if (raw.length() == 0) null else (0 until raw.length())
                .joinToString(",") { raw.optString(it) }
            else -> raw.toString()
        }
        return str?.trim()?.takeIf { it.isNotEmpty() && it != "[]" }
    }

    /** 高德 POI 打分为 `distance + categoryPenalty`（与腾讯同一套 penalty 表）。 */
    private fun JSONArray.scoreAmapPois(
        userLat: Double,
        userLng: Double,
        province: String?,
        city: String?,
        district: String?,
        township: String?,
    ): List<PoiOption> {
        data class Scored(val option: PoiOption, val score: Double)
        val out = ArrayList<Scored>(length())
        for (i in 0 until length()) {
            val p = optJSONObject(i) ?: continue
            val title = p.optStringOrNull("name") ?: continue
            if (title.isBlank()) continue
            val cat = p.optString("type")
            // 高德直接给了 distance 字段（米），但偶尔为空，兜底用 haversine
            val dist = p.optStringOrNull("distance")?.toDoubleOrNull()
                ?: p.optStringOrNull("location")?.let { ll ->
                    val parts = ll.split(",")
                    if (parts.size == 2) {
                        val lng = parts[0].toDoubleOrNull()
                        val lat = parts[1].toDoubleOrNull()
                        if (lat != null && lng != null) {
                            haversineMeters(userLat, userLng, lat, lng)
                        } else Double.POSITIVE_INFINITY
                    } else Double.POSITIVE_INFINITY
                } ?: Double.POSITIVE_INFINITY
            val penalty = categoryPenalty(cat, title)
            val display = composeAddress(province, city, district, title, null, township) ?: title
            out += Scored(PoiOption(display, title, cat, dist), dist + penalty)
        }
        return out.sortedBy { it.score }.map { it.option }
    }

    // ---------------- 腾讯 ----------------

    private fun reverseGeocodeViaTencent(
        latitude: Double,
        longitude: Double,
        key: String,
        secretKey: String,
    ): ReGeocodeResult? {
        // 官方文档要求：参与 sig 计算的参数必须按参数名 ASCII 升序、且值是原始未编码的。
        // 参考 https://lbs.qq.com/faq/serverFaq/webServiceKey
        val path = "/ws/geocoder/v1"
        val rawParams = sortedMapOf(
            "get_poi" to "1",
            "key" to key,
            "location" to "$latitude,$longitude",
            "poi_options" to "radius=$RADIUS_METERS;policy=5",
        )
        val rawQuery = rawParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = if (secretKey.isNotBlank()) {
            val sig = md5Hex("$path?$rawQuery$secretKey")
            "$TENCENT_ENDPOINT_PREFIX$path?$rawQuery&sig=$sig"
        } else {
            "$TENCENT_ENDPOINT_PREFIX$path?$rawQuery"
        }
        val body = httpGet(url) ?: return null
        return try {
            val root = JSONObject(body)
            if (root.optInt("status", -1) != 0) {
                Log.d(TAG, "tencent status=${root.optInt("status")} msg=${root.optString("message")}")
                return null
            }
            val result = root.optJSONObject("result") ?: return null
            val component = result.optJSONObject("address_component") ?: JSONObject()

            val province = component.optStringOrNull("province")
            val city = component.optStringOrNull("city") ?: province
            val district = component.optStringOrNull("district")
            val street = component.optStringOrNull("street")
            val streetNumber = component.optStringOrNull("street_number")

            // policy=5 名义上是距离序，实际腾讯返回并不严格按距离；自己按真实距离 + 类别惩罚重排
            val poiOptions: List<PoiOption> = result.optJSONArray("pois")
                ?.scoreTencentPois(latitude, longitude, province, city, district)
                ?: emptyList()

            // 腾讯没有高德那种 neighborhood/building 字段，用 street 或 POI 拼装
            val formatted = result.optStringOrNull("address")
            val streetLine = if (street != null) {
                listOfNotNull(district, street, streetNumber).joinToString("")
            } else null

            val top = poiOptions.firstOrNull()?.title
            val composedSpecific = top ?: streetLine ?: formatted
            val composed = composeAddress(province, city, district, composedSpecific, formatted, null)
                ?: return null
            val finalCandidates = poiOptions.ifEmpty {
                listOf(PoiOption(composed, composedSpecific ?: composed, "", 0.0))
            }
            ReGeocodeResult(composed = composed, candidates = finalCandidates)
        } catch (e: Exception) {
            Log.w(TAG, "tencent parse failed: ${e.message}")
            null
        }
    }

    /** 打分并返回排序后的候选列表；[pickBestPoi] 保留给老代码路径用。 */
    private fun JSONArray.scoreTencentPois(
        userLat: Double,
        userLng: Double,
        province: String?,
        city: String?,
        district: String?,
    ): List<PoiOption> {
        data class Scored(val option: PoiOption, val score: Double)
        val items = ArrayList<Scored>(length())
        for (i in 0 until length()) {
            val p = optJSONObject(i) ?: continue
            val title = p.optStringOrNull("title") ?: continue
            if (title.isBlank()) continue
            val loc = p.optJSONObject("location") ?: continue
            val lat = loc.optStringOrNull("lat")?.toDoubleOrNull() ?: continue
            val lng = loc.optStringOrNull("lng")?.toDoubleOrNull() ?: continue
            val dist = haversineMeters(userLat, userLng, lat, lng)
            val cat = p.optString("category")
            val penalty = categoryPenalty(cat, title)
            val display = composeAddress(province, city, district, title, null, null) ?: title
            items += Scored(PoiOption(display, title, cat, dist), dist + penalty)
        }
        return items.sortedBy { it.score }.map { it.option }
    }

    /**
     * 类别惩罚表。所有服务商共用，保证同一套 UI 语义。
     * 返回值越小越优先；负数是明确加分。
     */
    private fun categoryPenalty(cat: String, title: String): Double = when {
        cat.contains("住宅小区") -> -150.0
        cat.contains("商务楼宇") || cat.contains("楼栋") -> -50.0
        cat.contains("产业园区") -> 400.0
        cat.contains("地名地址") || cat.contains("热点区域") ||
            cat.contains("道路") || cat.contains("路口") ||
            cat.contains("桥梁") || cat.contains("村庄") ||
            cat.contains("行政区划") -> 500.0
        cat.contains("交通设施") || cat.contains("地铁站") ||
            cat.contains("公交车站") -> 350.0
        cat.contains("社区中心") -> 300.0
        cat.contains("党群") || cat.contains("政府") -> 300.0
        cat.contains("教育学校") || cat.contains("大学") || cat.contains("学院") -> 200.0
        cat.contains("号楼") || cat.contains("-西门") || cat.contains("-北门") ||
            cat.contains("-东门") || cat.contains("-南门") ||
            title.endsWith("门") -> 100.0
        cat.startsWith("美食") || cat.startsWith("购物") ||
            cat.startsWith("医疗保健") || cat.startsWith("生活服务") -> 250.0
        else -> 0.0
    }

    /**
     * 从 pois 数组里挑一个最合适的（腾讯路径）。
     *
     * 现在被 [scoreTencentPois] 取代，保留是为了单测方便。
     */
    private fun JSONArray.pickBestPoi(userLat: Double, userLng: Double): String? =
        scoreTencentPois(userLat, userLng, null, null, null).firstOrNull()?.title

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    private fun md5Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    // ---------------- 通用工具 ----------------

    /**
     * 组合成最终显示地址。规则：
     * 1. 有具体地点（小区/POI）：`市 + 区 + 具体地点`
     * 2. 只有街道：`市 + 区 + 街道`
     * 3. 什么都没有：用服务方给的 formatted 整行
     * 直辖市下 city == province 时不重复。
     */
    private fun composeAddress(
        province: String?,
        city: String?,
        district: String?,
        specific: String?,
        formatted: String?,
        township: String?,
    ): String? {
        if (specific != null) {
            // 如果服务商已经返回了完整串（例如腾讯走 streetLine 分支已含 district），
            // 就不要再重复拼接
            if (specific.contains(district.orEmpty()) && district != null) {
                return specific
            }
            // POI 名字常常自带城市名（如"西安市丈八北路丁家桥社区"），去掉重复前缀
            val cleanedSpecific = city?.let { c ->
                if (specific.startsWith(c)) specific.removePrefix(c).trimStart('-', ' ') else specific
            } ?: specific
            val prefix = buildString {
                if (city != null && city != province) append(city)
                if (district != null) append(district)
                if (township != null && !cleanedSpecific.contains(township)) {
                    // 高德的 township 有时能增加定位粒度，加进前缀
                    append(township)
                }
            }
            return if (cleanedSpecific.startsWith(prefix) || prefix.isEmpty()) {
                cleanedSpecific
            } else {
                prefix + cleanedSpecific
            }
        }
        // 没有具体地点，退回服务商完整地址串
        return formatted ?: listOfNotNull(
            province,
            city.takeIf { it != province },
            district,
            township,
        ).joinToString("").takeIf { it.isNotEmpty() }
    }

    private fun JSONArray.pickFirstNonEmpty(key: String): String? {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            val name = item.optStringOrNull(key) ?: continue
            if (name.isNotBlank()) return name
        }
        return null
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        opt(key)?.toString()?.takeIf { it.isNotEmpty() && it != "[]" && it != "null" }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("User-Agent", "WMCamera/1.0")
        }
        return try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "http $code for $url")
                null
            } else {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "httpGet failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 系统 Geocoder 兜底 ----------------

    private fun reverseGeocodeViaSystem(
        latitude: Double,
        longitude: Double,
    ): ReGeocodeResult? {
        if (!Geocoder.isPresent()) return null
        return try {
            val addresses: List<Address>? =
                systemGeocoder.getFromLocation(latitude, longitude, MAX_RESULTS)
            val sorted = addresses
                ?.sortedByDescending { it.poiRelevance() }
                ?.filter { !it.formatSystemAddress().isNullOrBlank() }
            val first = sorted?.firstOrNull() ?: return null
            val composed = first.formatSystemAddress() ?: return null
            val candidates = sorted.map { addr ->
                PoiOption(
                    display = addr.formatSystemAddress() ?: composed,
                    title = addr.featureName?.takeIf { it.isNotBlank() }
                        ?: addr.premises?.takeIf { it.isNotBlank() }
                        ?: addr.thoroughfare?.takeIf { it.isNotBlank() }
                        ?: composed,
                    category = "",
                    distanceMeters = 0.0,
                )
            }.distinctBy { it.display }
            ReGeocodeResult(composed = composed, candidates = candidates)
        } catch (e: Exception) {
            Log.d(TAG, "system geocoder failed: ${e.message}")
            null
        }
    }

    private fun Address.poiRelevance(): Int {
        var score = 0
        if (!featureName.isNullOrBlank()) score += 10
        if (!premises.isNullOrBlank()) score += 8
        if (!subLocality.isNullOrBlank()) score += 2
        if (!thoroughfare.isNullOrBlank()) score += 1
        return score
    }

    private fun Address.formatSystemAddress(): String? {
        val city = locality.orEmpty()
        val district = subLocality.orEmpty()
        val poi = featureName?.takeIf { it.isNotBlank() } ?: premises?.takeIf { it.isNotBlank() }
        if (poi != null) {
            val prefix = buildString {
                if (city.isNotBlank()) append(city)
                if (district.isNotBlank()) append(district)
            }
            return if (poi.startsWith(city) || poi.startsWith(district)) poi else prefix + poi
        }
        val street = thoroughfare?.takeIf { it.isNotBlank() }
        val number = subThoroughfare?.takeIf { it.isNotBlank() }
        if (street != null) {
            return listOfNotNull(
                city.takeIf { it.isNotBlank() },
                district.takeIf { it.isNotBlank() },
                street,
                number,
            ).joinToString("")
        }
        return if (maxAddressLineIndex >= 0) getAddressLine(0) else null
    }

    companion object {
        private const val TAG = "wm-geocode"
        private const val AMAP_ENDPOINT = "https://restapi.amap.com/v3/geocode/regeo"
        private const val TENCENT_ENDPOINT_PREFIX = "https://apis.map.qq.com"
        /** POI 搜索半径，米。太大会拿到远处的 POI。 */
        private const val RADIUS_METERS = 1500
        private const val MAX_RESULTS = 5
    }
}
