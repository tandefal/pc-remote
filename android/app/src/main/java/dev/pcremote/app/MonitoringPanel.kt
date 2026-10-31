package dev.pcremote.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.pcremote.app.data.*
import java.util.Locale

@Composable
fun MonitoringPanel(state: UiState) {
    val metrics = state.monitoring
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Состояние системы", style = MaterialTheme.typography.titleLarge)
        if (!state.online) Text("Нет соединения · Нет данных", color = MaterialTheme.colorScheme.onSurfaceVariant)
        DeviceCard("Процессор", metrics?.cpu, state.history) { it.cpu }
        if (metrics?.gpus.isNullOrEmpty()) DeviceCard("Видеокарта", null, emptyList()) { null }
        metrics?.gpus?.forEach { gpu ->
            key(gpu.id) { DeviceCard("Видеокарта", gpu, state.history) { sample -> sample.gpus.firstOrNull { it.id == gpu.id } } }
        }
        MonitorCard {
            Text("Оперативная память", style = MaterialTheme.typography.titleMedium)
            val ram = metrics?.memory
            Text(if (ram == null) "Нет данных" else "${gb(ram.used)} / ${gb(ram.total)} ГБ", style = MaterialTheme.typography.headlineSmall)
            Text("Использовано: ${value(ram?.usage, "%")}")
            val progress by animateFloatAsState(((ram?.usage ?: 0.0) / 100).toFloat(), tween(500), label = "Память")
            if (ram != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("Доступно: ${ram?.let { "${gb(it.available)} ГБ" } ?: "Нет данных"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MonitorCard {
            Text("Время работы", style = MaterialTheme.typography.titleMedium)
            Text(metrics?.uptimeSeconds?.let { "${it / 86400} дн. ${it / 3600 % 24} ч. ${it / 60 % 60} мин." } ?: "Нет данных", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun MonitorCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun DeviceCard(title: String, device: DeviceMetrics?, history: List<MonitoringPoint>, select: (SystemMetrics) -> DeviceMetrics?) {
    MonitorCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(device?.name ?: "Нет данных", style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricValue("Загрузка", device?.load, "%")
            MetricValue("Температура", device?.temperature, "°C")
        }
        Text("Загрузка · последние 60 с", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HistoryChart(history, 100.0) { select(it)?.load }
        Text("Температура · последние 60 с", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HistoryChart(history, 150.0) { select(it)?.temperature }
    }
}

@Composable
private fun MetricValue(label: String, number: Double?, unit: String) {
    val animated by animateFloatAsState((number ?: 0.0).toFloat(), tween(500), label = label)
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (number == null) "Нет данных" else value(animated.toDouble(), unit), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun HistoryChart(history: List<MonitoringPoint>, maximum: Double, select: (SystemMetrics) -> Double?) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val end = history.lastOrNull()?.time ?: 0L
    val shift by animateFloatAsState(end.toFloat(), tween(500), label = "График")
    val available = history.any { select(it.metrics) != null }
    if (!available) Text("Нет данных", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
        val path = Path()
        var connected = false
        var previous = 0L
        history.forEach { point ->
            val value = select(point.metrics)
            if (value == null) connected = false
            else {
                val x = (1f - (shift - point.time.toFloat()) / 60_000f).coerceIn(0f, 1f) * size.width
                val y = size.height * (1 - (value / maximum).coerceIn(0.0, 1.0)).toFloat()
                if (connected && point.time - previous <= 5000) path.lineTo(x, y) else path.moveTo(x, y)
                drawCircle(color, 2.dp.toPx(), Offset(x, y))
                connected = true
            }
            previous = point.time
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}
private fun value(number: Double?, unit: String) = number?.let { String.format(Locale.forLanguageTag("ru"), "%.1f %s", it, unit) } ?: "Нет данных"
private fun gb(bytes: Double) = String.format(Locale.forLanguageTag("ru"), "%.1f", bytes / 1073741824.0)
