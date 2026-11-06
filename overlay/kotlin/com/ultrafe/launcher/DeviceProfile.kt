package com.ultrafe.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

/**
 * What we know about this phone. MediaTek detection uses the SoC fields on Android 12+
 * and falls back to Build.HARDWARE / BOARD ("mt6789", "mt6893", ...) on older builds.
 */
data class DeviceProfile(
    val deviceName: String,
    val soc: String,
    val isMediaTek: Boolean,
    val ramGb: Float,
    val vulkan11: Boolean
) {
    companion object {
        // VK_MAKE_API_VERSION(0, 1, 1, 0)
        private const val VULKAN_1_1 = 0x00401000

        fun detect(ctx: Context): DeviceProfile {
            var maker = ""
            var model = ""
            if (Build.VERSION.SDK_INT >= 31) {
                maker = Build.SOC_MANUFACTURER ?: ""
                model = Build.SOC_MODEL ?: ""
            }
            val hw = (Build.HARDWARE ?: "").lowercase(Locale.US)
            val board = (Build.BOARD ?: "").lowercase(Locale.US)

            val mediaTek = maker.contains("mediatek", ignoreCase = true) ||
                model.uppercase(Locale.US).startsWith("MT") ||
                hw.startsWith("mt") ||
                board.startsWith("mt")

            val socLabel = when {
                maker.isNotBlank() || model.isNotBlank() -> "$maker $model".trim()
                !Build.HARDWARE.isNullOrBlank() -> Build.HARDWARE
                else -> "Unknown SoC"
            }

            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val ramGb = mi.totalMem / 1073741824f

            val vulkan11 = ctx.packageManager.hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1
            )

            return DeviceProfile(
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                soc = socLabel,
                isMediaTek = mediaTek,
                ramGb = ramGb,
                vulkan11 = vulkan11
            )
        }
    }
}
