package io.github.jackwener.dsh.feature.settings

import io.github.jackwener.dsh.core.capability.tools.hasAllFilesAccess
import io.github.jackwener.dsh.core.trigger.canExact
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import io.github.jackwener.dsh.core.designsystem.findActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.service.DshAccessibilityService
import io.github.jackwener.dsh.service.DshNotificationListener

/** One grantable thing: how to check it, how to ask for it, and which capabilities need it. */
data class PermissionItem(
    val id: String,
    val titleZh: String, val titleEn: String, val titleEs: String,
    val whyZh: String, val whyEn: String, val whyEs: String,
    val groups: Set<ToolGroup>,
    /** Runtime permissions requested in-app; null when granted through a Settings screen. */
    val runtime: List<String>? = null,
    val restrictedSetting: Boolean = false,
    val check: (Context) -> Boolean,
    val intent: (Context) -> Intent? = { null },
) {
    fun title(zh: Boolean) = if (zh) titleZh else titleEs
    fun why(zh: Boolean) = if (zh) whyZh else whyEs
    fun title(lang: String) = when (lang) {
        "zh" -> titleZh
        "en" -> titleEn
        else -> titleEs
    }
    fun why(lang: String) = when (lang) {
        "zh" -> whyZh
        "en" -> whyEn
        else -> whyEs
    }
}

object PermissionCatalog {
    private fun pkgUri(c: Context) = Uri.parse("package:${c.packageName}")
    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun appDetails(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c))

    /**
     * After a denied request: true when none of [perms] can be asked again ("don't ask again", or
     * the second denial on Android 11+), so only the app's settings page can grant them.
     */
    fun permanentlyDenied(c: Context, perms: Collection<String>): Boolean {
        val act = c.findActivity() ?: return false
        return perms.isNotEmpty() && perms.none { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(act, it) } &&
            perms.none { androidx.core.content.ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    val all: List<PermissionItem> = listOf(
        PermissionItem("notifications", "通知", "Notifications", "Notificaciones",
            "任务完成、需要确认时提醒你", "Tell you when tasks finish or need approval", "Avisarte cuando las tareas terminen o requieran aprobación",
            ToolGroup.entries.toSet(), runtime = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else null,
            check = { Build.VERSION.SDK_INT < 33 || granted(it, Manifest.permission.POST_NOTIFICATIONS) }),
        PermissionItem("battery", "不受电池优化限制", "Unrestricted battery", "Sin optimización de batería",
            "锁屏和后台时也能执行任务、调用模型", "Run tasks and reach the model while the phone sleeps", "Ejecutar tareas y consultar al modelo mientras el teléfono está en reposo",
            ToolGroup.entries.toSet(),
            check = { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
            intent = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(it)) }),
        PermissionItem("files", "所有文件访问", "All files access", "Acceso a todos los archivos",
            "查找、整理、清理你的文件", "Find, organise and clean up files", "Buscar, organizar y limpiar tus archivos",
            setOf(ToolGroup.Files),
            // Android 10 has no "All files access"; legacy storage + WRITE_EXTERNAL_STORAGE covers it there.
            runtime = if (Build.VERSION.SDK_INT < 30) listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE) else null,
            check = { hasAllFilesAccess(it) },
            intent = { if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri(it)) else null }),
        PermissionItem("accessibility", "无障碍（屏幕操作）", "Accessibility (screen control)", "Accesibilidad (control de pantalla)",
            "读取屏幕、替你点按和输入", "Read the screen, tap and type for you", "Leer la pantalla, pulsar y escribir por ti",
            setOf(ToolGroup.Screen, ToolGroup.Apps), restrictedSetting = true,
            check = { isA11yEnabled(it) },
            intent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
        PermissionItem("notif_access", "通知使用权", "Notification access", "Acceso a notificaciones",
            "读取通知、通知触发自动化、控制媒体播放", "Read notifications, notification triggers, media control", "Leer notificaciones, activadores por notificación y control multimedia",
            setOf(ToolGroup.Notifications, ToolGroup.Media, ToolGroup.Rules), restrictedSetting = true,
            check = { isListenerEnabled(it) },
            intent = { c -> if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(c, DshNotificationListener::class.java).flattenToString())
                else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }),
        PermissionItem("exact_alarm", "闹钟和提醒", "Alarms & reminders", "Alarmas y recordatorios",
            "定时自动化准点执行", "Run timed automations on time", "Ejecutar automatizaciones programadas a tiempo",
            setOf(ToolGroup.Rules),
            check = { it.getSystemService(AlarmManager::class.java).canExact() },
            intent = { if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(it)) else null }),
        PermissionItem("dnd", "勿扰模式权限", "Do Not Disturb access", "Acceso a No molestar",
            "切换静音和勿扰", "Switch silent mode and Do Not Disturb", "Cambiar al modo silencio y No molestar",
            setOf(ToolGroup.System),
            check = { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
            intent = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }),
        PermissionItem("write_settings", "修改系统设置", "Modify system settings", "Modificar ajustes del sistema",
            "调节亮度", "Adjust brightness", "Ajustar el brillo",
            setOf(ToolGroup.System),
            check = { Settings.System.canWrite(it) },
            intent = { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(it)) }),
        PermissionItem("location", "位置", "Location", "Ubicación",
            "按 Wi‑Fi 名称或地点触发自动化", "Trigger automations by Wi‑Fi name or place", "Activar automatizaciones por red Wi‑Fi o lugar",
            setOf(ToolGroup.Rules), runtime = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            check = { granted(it, Manifest.permission.ACCESS_FINE_LOCATION) }),
        PermissionItem("bg_location", "后台位置", "Background location", "Ubicación en segundo plano",
            "离开 App 时也能按地点触发", "Place triggers while DSH is closed", "Activar por lugar incluso cuando DSH esté cerrado",
            setOf(ToolGroup.Rules),
            check = { granted(it, Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            intent = { appDetails(it) }),
        PermissionItem("bluetooth", "附近设备（蓝牙）", "Nearby devices", "Dispositivos cercanos (Bluetooth)",
            "按蓝牙设备名称触发自动化", "Trigger automations by Bluetooth device", "Activar automatizaciones por dispositivo Bluetooth",
            setOf(ToolGroup.Rules), runtime = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else null,
            check = { Build.VERSION.SDK_INT < 31 || granted(it, Manifest.permission.BLUETOOTH_CONNECT) }),
        PermissionItem("comms", "联系人、短信、通话记录、日历", "Contacts, SMS, calls, calendar", "Contactos, SMS, llamadas y calendario",
            "查联系人、读短信、记日程、发短信", "Look up contacts, read SMS, add events, send SMS", "Consultar contactos, leer SMS, añadir eventos y enviar SMS",
            setOf(ToolGroup.Comms), runtime = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            restrictedSetting = true,
            check = { granted(it, Manifest.permission.READ_CONTACTS) && granted(it, Manifest.permission.READ_SMS) }),
        PermissionItem("overlay", "悬浮窗", "Display over other apps", "Mostrar sobre otras aplicaciones",
            "显示悬浮球", "Show the floating button", "Mostrar el botón flotante",
            emptySet(),
            check = { Settings.canDrawOverlays(it) },
            intent = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(it)) }),
    )

    fun forGroups(groups: Set<ToolGroup>) = all.filter { it.groups.any(groups::contains) }

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun isA11yEnabled(c: Context): Boolean {
        if (DshAccessibilityService.instance != null) return true
        val am = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        if (am != null) {
            val enabledList = runCatching {
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            }.getOrNull()
            if (enabledList?.any { it.id?.contains(c.packageName) == true || it.resolveInfo?.serviceInfo?.packageName == c.packageName } == true) {
                return true
            }
        }
        val flat = runCatching {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: return false
        val cn = ComponentName(c, DshAccessibilityService::class.java)
        if (flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString()) ||
            flat.contains("${c.packageName}/.service.DshAccessibilityService") ||
            flat.contains("${c.packageName}/${DshAccessibilityService::class.java.name}")) {
            return true
        }
        return flat.split(':').any { it.trim().startsWith("${c.packageName}/") }
    }

    fun isListenerEnabled(c: Context): Boolean {
        if (DshNotificationListener.instance != null) return true
        val enabledListeners = runCatching { androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c) }.getOrNull()
        if (enabledListeners?.contains(c.packageName) == true) return true
        val flat = runCatching {
            Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")
        }.getOrNull() ?: return false
        val cn = ComponentName(c, DshNotificationListener::class.java)
        if (flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString()) ||
            flat.contains("${c.packageName}/.service.DshNotificationListener") ||
            flat.contains("${c.packageName}/${DshNotificationListener::class.java.name}")) {
            return true
        }
        return flat.split(':').any { it.trim().startsWith("${c.packageName}/") }
    }
}
