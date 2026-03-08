package me.rerere.rikkahub.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 返回最近一次可用的系统定位 经度在前 纬度在后
 *
 * 用于提示词变量注入
 * 无权限 定位服务关闭 或拿不到有效定位时返回空字符串
 */
fun Context.formatLastKnownLocationLngLat(): String {
    val location = getLastKnownUserLocation() ?: return ""
    return String.format(Locale.US, "%.6f,%.6f", location.longitude, location.latitude)
}

@SuppressLint("MissingPermission")
private fun Context.getLastKnownUserLocation(): Location? {
    val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null

    val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    if (!locationManager.isLocationServiceEnabledCompat()) return null

    val availableProviders = runCatching { locationManager.allProviders.toSet() }.getOrDefault(emptySet())
    val candidateProviders = if (hasFine) {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    } else {
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    }

    val candidates = candidateProviders
        .asSequence()
        .filter { it in availableProviders }
        .mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }
        .toList()

    return candidates.maxByOrNull { it.time }
}

private fun LocationManager.isLocationServiceEnabledCompat(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        isLocationEnabled
    } else {
        runCatching {
            isProviderEnabled(LocationManager.GPS_PROVIDER) || isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }
}
