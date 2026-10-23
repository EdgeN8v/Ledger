package com.ledger.app.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Shortcuts to the system pages that decide whether the app may keep watching in the background. */
object Background {
    fun openSettings(c: Context) {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val tries = mutableListOf<Intent>()
        if (!pm.isIgnoringBatteryOptimizations(c.packageName)) {
            tries += Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName))
        }
        // Xiaomi's "autostart" page, then the generic app info page
        tries += Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        tries += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
        for (i in tries) {
            try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    /** The phone makers whose background cleaners kill the app (and with it auto-capture) unless told not to. */
    enum class Vendor { VIVO, OPPO, XIAOMI, HUAWEI }

    fun vendor(): Vendor? {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            "vivo" in m || "iqoo" in m -> Vendor.VIVO
            "oppo" in m || "realme" in m || "oneplus" in m -> Vendor.OPPO
            "xiaomi" in m || "redmi" in m || "poco" in m -> Vendor.XIAOMI
            "huawei" in m || "honor" in m -> Vendor.HUAWEI
            else -> null
        }
    }

    fun guideSteps(v: Vendor): String = when (v) {
        Vendor.VIVO ->
            "1. 从屏幕底部上滑停住打开最近任务, 按住「记账」卡片 → 锁定(卡片上出现小锁)。\n" +
                "2. 设置 → 电池 → 后台耗电管理 → 记账 → 选「允许后台高耗电」。\n" +
                "3. i管家 → 应用管理 → 权限管理 → 自启动 → 打开「记账」。"
        Vendor.OPPO ->
            "1. 打开最近任务, 按住(或下拉)「记账」卡片 → 锁定。\n" +
                "2. 设置 → 电池 → 更多设置/应用耗电管理 → 记账 → 打开「允许后台运行」「允许自启动」。"
        Vendor.XIAOMI ->
            "1. 打开最近任务, 按住「记账」卡片 → 点小锁。\n" +
                "2. 设置 → 应用设置 → 应用管理 → 记账 → 打开「自启动」, 省电策略选「无限制」。"
        Vendor.HUAWEI ->
            "1. 打开最近任务, 下拉「记账」卡片锁定。\n" +
                "2. 设置 → 电池 → 启动应用管理(应用启动管理) → 记账 → 关闭「自动管理」, 打开自启动、关联启动、后台活动。"
    }

    /** The vendor's own autostart / background-power page where one is known; otherwise the generic pages above. */
    fun openVendorSettings(c: Context) {
        val pkg = c.packageName
        val tries = when (vendor()) {
            Vendor.VIVO -> listOf(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")
            )
            Vendor.OPPO -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            )
            Vendor.XIAOMI -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            )
            Vendor.HUAWEI -> listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
            null -> emptyList()
        }
        for (cn in tries) {
            try {
                c.startActivity(
                    Intent().setComponent(cn).putExtra("package_name", pkg).putExtra("packageName", pkg)
                        .putExtra("package_label", "记账").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: Exception) {
            }
        }
        openSettings(c)
    }
}
