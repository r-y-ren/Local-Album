package com.renyxin.localalbum.core.analysis

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 反向地理编码器（Phase 3.5）。
 *
 * 封装 Android [Geocoder] API，将 GPS 坐标解析为可读地名。
 *
 * 策略：
 * - Android 13+ (API 33+) 优先使用 [Geocoder.getFromLocation] 的异步回调重载
 * - 低版本使用同步 [Geocoder.getFromLocation]（在 IO 线程执行）
 * - 超时 3 秒后返回 null，避免阻塞
 * - 结果格式："省 市 区" 或 "国家 城市"（根据可用字段降级）
 *
 * @param context 应用上下文
 * @param locale 地理编码返回文本的语言，默认中文
 */
class ReverseGeocoder(
    private val context: Context,
    private val locale: Locale = Locale.CHINA,
) {
    companion object {
        private const val TAG = "ReverseGeocoder"
        private const val TIMEOUT_MS = 3000L
        private const val MAX_RESULTS = 1
    }

    private val geocoder: Geocoder by lazy { Geocoder(context, locale) }

    /**
     * 反向地理编码结果。
     *
     * @param fullName 完整地名（如 "中国 北京市 朝阳区"）
     * @param shortName 简短地名（如 "朝阳区" 或 "北京市"）
     * @param locality 城市名（如 "北京市"）
     * @param subLocality 区/县名（如 "朝阳区"）
     * @param thoroughfare 街道/道路名（可能为 null）
     */
    data class GeoName(
        val fullName: String,
        val shortName: String,
        val locality: String?,
        val subLocality: String?,
        val thoroughfare: String?,
    )

    /**
     * 将 GPS 坐标解析为地名。
     * 在 IO 线程执行，超时 3 秒后返回 null。
     *
     * @param latitude 纬度
     * @param longitude 经度
     * @return 地名信息，解析失败或超时返回 null
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): GeoName? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) {
                Log.w(TAG, "Geocoder 服务不可用")
                return@withContext null
            }

            try {
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+：使用异步回调重载
                    getFromLocationAsync(latitude, longitude)
                } else {
                    // Android 12 及以下：同步调用（已在 IO 线程）
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(latitude, longitude, MAX_RESULTS)
                }

                val address = addresses?.firstOrNull()
                if (address == null) {
                    Log.d(TAG, "无法解析坐标: ($latitude, $longitude)")
                    return@withContext null
                }

                parseAddress(address)
            } catch (e: Exception) {
                Log.w(TAG, "反向地理编码失败: (${latitude},${longitude}) - ${e.message}")
                null
            }
        }

    /**
     * Android 13+ 异步回调方式获取地址列表。
     */
    @Suppress("NewApi")
    private suspend fun getFromLocationAsync(
        latitude: Double,
        longitude: Double,
    ): List<android.location.Address>? {
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(latitude, longitude, MAX_RESULTS) { addresses ->
                    if (cont.isActive) {
                        cont.resume(addresses)
                    }
                }
            }
        }
    }

    /**
     * 将 [android.location.Address] 解析为 [GeoName]。
     * 根据可用字段降级构建地名文本。
     */
    private fun parseAddress(address: android.location.Address): GeoName {
        val locality = address.locality // 城市
        val subLocality = address.subLocality // 区/县
        val subAdminArea = address.subAdminArea // 地区
        val adminArea = address.adminArea // 省/州
        val countryName = address.countryName // 国家
        val thoroughfare = address.thoroughfare // 街道
        val subThoroughfare = address.subThoroughfare // 门牌号

        // 构建完整地名：国家 + 省 + 市 + 区
        val parts = mutableListOf<String>()
        if (!countryName.isNullOrBlank()) parts.add(countryName)
        if (!adminArea.isNullOrBlank()) parts.add(adminArea)
        if (!locality.isNullOrBlank()) parts.add(locality)
        if (!subLocality.isNullOrBlank()) {
            parts.add(subLocality)
        } else if (!subAdminArea.isNullOrBlank()) {
            parts.add(subAdminArea)
        }
        val fullName = parts.joinToString(" ").ifBlank { "未知地点" }

        // 简短地名：优先 区/县，其次 城市，再次 省/州
        val shortName = subLocality
            ?: subAdminArea
            ?: locality
            ?: adminArea
            ?: countryName
            ?: "未知地点"

        return GeoName(
            fullName = fullName,
            shortName = shortName,
            locality = locality,
            subLocality = subLocality ?: subAdminArea,
            thoroughfare = thoroughfare?.let { street ->
                subThoroughfare?.let { num -> "$street $num" } ?: street
            },
        )
    }

    /**
     * 批量反向地理编码（对聚类中心坐标）。
     * 对相同坐标做内存缓存，避免重复请求。
     *
     * @param coordinates 坐标列表（纬度, 经度）
     * @return 坐标 → 地名映射
     */
    suspend fun batchReverseGeocode(
        coordinates: List<Pair<Double, Double>>,
    ): Map<Pair<Double, Double>, GeoName> = withContext(Dispatchers.IO) {
        if (coordinates.isEmpty()) return@withContext emptyMap()

        val cache = mutableMapOf<Pair<Double, Double>, GeoName>()
        for ((lat, lon) in coordinates) {
            val key = Pair(lat, lon)
            if (key in cache) continue
            cache[key] = reverseGeocode(lat, lon) ?: continue
        }
        cache
    }
}
