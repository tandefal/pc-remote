package dev.pcremote.app.data

import org.json.JSONObject

data class DeviceMetrics(val id: String, val name: String, val load: Double?, val temperature: Double?)
data class MemoryMetrics(val total: Double, val available: Double, val used: Double, val usage: Double)
data class SystemMetrics(val cpu: DeviceMetrics, val gpus: List<DeviceMetrics>, val memory: MemoryMetrics?, val uptimeSeconds: Long)
data class MonitoringPoint(val time: Long, val metrics: SystemMetrics)

fun parseMonitoring(json: JSONObject): SystemMetrics {
    fun JSONObject.number(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
    fun device(j: JSONObject) = DeviceMetrics(j.getString("id"), j.getString("name"), j.number("load")?.takeIf { it in 0.0..100.0 }, j.number("temperature"))
    val gpus = json.getJSONArray("gpus")
    val memory = json.optJSONObject("memory")?.let { MemoryMetrics(it.getDouble("total"), it.getDouble("available"), it.getDouble("used"), it.getDouble("usage")) }
    return SystemMetrics(device(json.getJSONObject("cpu")), (0 until gpus.length()).map { device(gpus.getJSONObject(it)) }, memory, json.getLong("uptimeSeconds"))
}
