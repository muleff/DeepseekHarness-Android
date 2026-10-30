package io.github.jackwener.dsh.feature.settings

import android.content.Context
import android.os.PowerManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jackwener.dsh.core.data.RuleDao
import io.github.jackwener.dsh.core.engine.EngineDiagnostics
import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.engine.RpcJson
import io.github.jackwener.dsh.core.model.RuleSpec
import io.github.jackwener.dsh.core.model.Trigger
import io.github.jackwener.dsh.core.trigger.RuleMonitorService
import io.github.jackwener.dsh.core.trigger.TriggerHub
import io.github.jackwener.dsh.service.DshInputMethodService

/**
 * Doctor rows for background reliability and crash evidence (review §6/§8/§12): the battery
 * exemption as a hard dependency of automations, the rule monitor, geofence backend, the engine
 * crash breaker and the last recorded `:engine` exit.
 */
object ReliabilityDoctor {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun rules(): RuleDao
        fun triggers(): TriggerHub
        fun engine(): EngineManager
    }

    suspend fun checks(context: Context, zh: Boolean): List<DoctorCheck> {
        val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
        EngineDiagnostics.collectExitInfo(context)
        val specs = deps.rules().all().filter { it.enabled }.mapNotNull { runCatching { RpcJson.decodeFromString(RuleSpec.serializer(), it.specJson) }.getOrNull() }
        val out = mutableListOf<DoctorCheck>()
        if (specs.isNotEmpty()) {
            val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            val battery = PermissionCatalog.byId("battery")
            out += DoctorCheck(if (zh) "自动化：不受电池优化限制（必需）" else "Automatización: sin optimización de batería (requerido)", exempt,
                when {
                    exempt -> if (zh) "后台触发可以直接启动引擎" else "Los activadores pueden iniciar el motor en segundo plano"
                    zh -> "未豁免时 Android 会拒绝在后台启动引擎，自动化只能降级为“点此执行”通知"
                    else -> "Sin ella, Android rechazará iniciar el motor en segundo plano; las automatizaciones se degradan a una notificación para tocar y ejecutar"
                }, battery?.intent?.invoke(context))
            val needsMonitor = specs.any { it.trigger is Trigger.Wifi || it.trigger is Trigger.Bluetooth || it.trigger is Trigger.Power ||
                it.trigger is Trigger.BatteryLow || it.trigger is Trigger.Headset || it.trigger is Trigger.Screen }
            if (needsMonitor) out += DoctorCheck(if (zh) "规则监听服务" else "Servicio de supervisión", RuleMonitorService.running,
                when {
                    RuleMonitorService.running -> if (zh) "运行中" else "En ejecución"
                    RuleMonitorService.lastStartRefused -> if (zh) "系统拒绝在后台启动；打开 DSH 一次或授予电池豁免" else "Inicio rechazado en segundo plano; abre DSH una vez o concede la excepción de batería"
                    else -> if (zh) "未运行" else "No se está ejecutando"
                })
            if (specs.any { it.trigger is Trigger.Geofence }) {
                val gms = deps.triggers().playServicesGeofencing
                out += DoctorCheck(if (zh) "地点触发" else "Activadores por ubicación", if (gms) true else null,
                    if (gms) (if (zh) "使用 Google Play 服务地理围栏" else "Geovallas de Google Play services")
                    else if (zh) "无 Play 服务：使用系统位置提醒，后台可能延迟数分钟触发" else "Sin servicios Play: alertas de proximidad del sistema, pueden tardar minutos en segundo plano")
            }
        }
        val restarts = deps.engine().restarts
        if (restarts.open || restarts.recentFailures > 0) out += DoctorCheck(if (zh) "引擎崩溃保护" else "Protección contra fallos del motor", !restarts.open,
            if (restarts.open) (if (zh) "引擎连续崩溃 ${restarts.recentFailures} 次，已暂停自动重启；点“重启引擎”重试" else "El motor falló repetidamente (${restarts.recentFailures} veces); reinicio automático pausado — usa Reiniciar motor")
            else if (zh) "最近崩溃 ${restarts.recentFailures} 次，已自动恢复" else "${restarts.recentFailures} fallo(s) recientes, recuperado")
        EngineDiagnostics.exitRecords(context).firstOrNull()?.let { r ->
            out += DoctorCheck(if (zh) "上次引擎异常退出" else "Último cierre del motor", null, r.summary)
        }
        out += DoctorCheck(if (zh) "DSH 输入法（可选）" else "Teclado DSH (opcional)", null,
            when {
                DshInputMethodService.isCurrent(context) -> if (zh) "当前输入法" else "Teclado actual"
                else -> if (zh) "WebView/Flutter 等输入框的兜底；有 Shizuku 时自动切换" else "Respaldo para campos WebView/Flutter; cambia automáticamente con Shizuku"
            })
        return out
    }

    /** Full text of the newest exit record (About → crash record). */
    fun lastExitText(context: Context): String? = EngineDiagnostics.exitRecords(context).firstOrNull()?.file?.readText()?.take(6000)
}
