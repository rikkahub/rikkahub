package com.hefengfan.hffchat.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * PlayStore utility functions
 */
object PlayStoreUtil {
    
    /**
     * Check if the app was installed from Google Play Store
     * 
     * @param context The application context
     * @return true if the app was installed from Play Store, false otherwise
     */
    fun isInstalledFromPlayStore(context: Context): Boolean {
        return try {
            val installer = getInstallerPackageName(context)
            installer == "com.android.vending"
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get the installer package name
     * 
     * @param context The application context
     * @return The installer package name, or null if unknown
     */
    fun getInstallerPackageName(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android API 30+
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                // Android API < 30
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (e: Exception) {
            null
        }
    }
}