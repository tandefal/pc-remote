package dev.pcremote.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.pcremote.app.data.RemoteApp

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val updates: UpdateViewModel by viewModels()
    private var scanSuccess: ((String) -> Unit)? = null
    private var scanError: ((String) -> Unit)? = null
    private val qrLauncher: ActivityResultLauncher<ScanOptions> = registerForActivityResult(ScanContract()) { result ->
        val value = result.contents
        if (value == null) scanError?.invoke("Сканирование отменено.") else scanSuccess?.invoke(value)
        scanSuccess = null; scanError = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            PCRemoteTheme { PCRemoteApp(state, viewModel, updates, ::scanQr) }
        }
    }
    override fun onStart() { super.onStart(); viewModel.startActive() }
    override fun onStop() { viewModel.stopActive(); super.onStop() }
    private fun scanQr(onResult: (String) -> Unit, onError: (String) -> Unit) {
        scanSuccess = onResult; scanError = onError
        qrLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Наведите камеру на QR-код PC Remote").setBeepEnabled(false).setOrientationLocked(true))
    }
}

@Composable private fun PCRemoteTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFD0BCFF)) else lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF6750A4)), content = content)
}

@Composable private fun PCRemoteApp(state: UiState, vm: MainViewModel, updates: UpdateViewModel, scan: ((String) -> Unit, (String) -> Unit) -> Unit) {
    var showUpdates by remember { mutableStateOf(false) }
    Scaffold(topBar = { Surface { Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text("PC Remote", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); TextButton(onClick = { showUpdates = !showUpdates }) { Text(if (showUpdates) "Назад" else "Обновления") } } } }, bottomBar = { if (!showUpdates && state.credentials != null) NavigationBar { NavItem(Page.HOME, state.page, Icons.Outlined.Home, "Главная", vm::select); NavItem(Page.APPS, state.page, Icons.Outlined.Apps, "Приложения", vm::select); NavItem(Page.COMPUTERS, state.page, Icons.Outlined.Computer, "Компьютеры", vm::select) } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            if (showUpdates) UpdateScreen(updates) else when (state.page) {
                Page.CONNECT -> ConnectScreen(state, { scan(vm::acceptQr, vm::reportError) })
                Page.HOME -> HomeScreen(state, vm)
                Page.APPS -> AppsScreen(state, vm)
                Page.COMPUTERS -> ComputersScreen(state, vm) { scan(vm::acceptQr, vm::reportError) }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            state.error?.let { MessageBanner(it, true, vm::dismissError, Modifier.align(Alignment.BottomCenter)) }
                ?: state.notice?.let { MessageBanner(it, false, vm::dismissNotice, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

@Composable private fun UpdateScreen(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 24.dp)) {
        item { Text("Обновления", style = MaterialTheme.typography.headlineMedium); Text("Установлена версия ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(onClick = vm::check, enabled = !state.busy) { Text("Проверить обновления") } }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.message.isNotBlank()) item { Text(state.message) }
        state.release?.let { release ->
            item { Text(release.notes.ifBlank { "Новая версия PC Remote" }) }
            item { Button(onClick = if (state.apk == null) vm::download else vm::install, enabled = !state.busy) { Text(if (state.apk == null) "Скачать обновление" else "Установить") } }
        }
    }
}

@Composable private fun RowScope.NavItem(page: Page, selected: Page, icon: ImageVector, label: String, select: (Page) -> Unit) { NavigationBarItem(selected = page == selected, onClick = { select(page) }, icon = { Icon(icon, null) }, label = { Text(label) }) }

@Composable private fun ConnectScreen(state: UiState, scan: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.padding(24.dp).size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(28.dp)); Text("Подключение к компьютеру", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp)); Text("На компьютере откройте «Устройства», нажмите «Добавить телефон» и отсканируйте QR-код.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp)); Button(onClick = scan, enabled = !state.pairing, modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text(if (state.pairing) "Поиск компьютера…" else "Сканировать QR-код") }
        if (state.pairing) { Spacer(Modifier.height(16.dp)); CircularProgressIndicator() }
    }
}

@Composable private fun HomeScreen(state: UiState, vm: MainViewModel) {
    var confirmShutdown by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    if (confirmShutdown) Confirm("Выключить компьютер?", "Все несохранённые данные могут быть потеряны.", { confirmShutdown = false; vm.shutdown() }, { confirmShutdown = false })
    if (confirmReboot) Confirm("Перезагрузить компьютер?", "Сохраните данные в открытых приложениях перед перезагрузкой.", { confirmReboot = false; vm.reboot() }, { confirmReboot = false })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp)) {
        item { Text(state.credentials?.name ?: "Компьютер", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); AssistChip(onClick = {}, label = { Text(if (state.online) "В сети" else "Не в сети") }, leadingIcon = { Icon(if (state.online) Icons.Outlined.CheckCircle else Icons.Outlined.CloudOff, null) }); Spacer(Modifier.height(28.dp)) }
        item { ActionCard(Icons.Outlined.Lock, if (state.lockConfirmed) "Блокировка выполнена" else "Заблокировать", if (state.lockConfirmed) "Windows подтвердила команду" else "Заблокировать текущий сеанс Windows", state.online && !state.loading && !state.lockConfirmed, vm::lock); Spacer(Modifier.height(16.dp)); ActionCard(Icons.Outlined.RestartAlt, "Перезагрузить", "Перезапустить компьютер", state.online && !state.loading) { confirmReboot = true }; Spacer(Modifier.height(16.dp)); ActionCard(Icons.Outlined.PowerSettingsNew, "Выключить", "Корректно выключить компьютер", state.online && !state.loading) { confirmShutdown = true } }
    }
}

@Composable private fun ComputersScreen(state: UiState, vm: MainViewModel, scan: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("Компьютеры", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Text("Выберите компьютер для управления или добавьте ещё один.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        Button(onClick = scan, enabled = !state.loading && !state.pairing, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(if (state.pairing) "Поиск компьютера…" else "Добавить компьютер") }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.computers, key = { it.id }) { computer ->
                val selected = state.credentials?.id == computer.id
                Card(onClick = { vm.selectComputer(computer.id) }, enabled = !selected && !state.loading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Computer, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(computer.name, fontWeight = FontWeight.SemiBold)
                            Text(if (selected) "Выбран · ${if (state.online) "в сети" else "проверка соединения…"}" else "Нажмите, чтобы переключиться", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected) Icon(Icons.Outlined.CheckCircle, "Выбран", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable private fun ActionCard(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, action: () -> Unit) { Card(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(icon, null, Modifier.padding(16.dp).size(30.dp)) }; Spacer(Modifier.width(18.dp)); Column { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }

@Composable private fun AppsScreen(state: UiState, vm: MainViewModel) {
    var exit by remember { mutableStateOf<RemoteApp?>(null) }; var terminate by remember { mutableStateOf<RemoteApp?>(null) }; var all by remember { mutableStateOf(false) }
    exit?.let { app -> Confirm("Выйти из ${app.name}?", "Приложению будет отправлен обычный запрос на выход, чтобы оно могло сохранить данные.", { exit = null; vm.exit(app.id) }, { exit = null }) }
    terminate?.let { app -> Confirm("Завершить ${app.name} принудительно?", "Несохранённые данные будут потеряны.", { terminate = null; vm.terminate(app.id) }, { terminate = null }) }
    if (all) Confirm("Завершить все приложения принудительно?", "Все несохранённые данные во всех доступных приложениях будут потеряны.", { all = false; vm.terminateAll() }, { all = false })
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text("Приложения", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); IconButton(onClick = vm::refreshApps, enabled = state.online && !state.loading) { Icon(Icons.Outlined.Refresh, "Обновить") } }
        if (!state.online) EmptyState(Icons.Outlined.CloudOff, "Компьютер не в сети", "Список станет доступен после восстановления соединения.")
        else if (!state.loading && state.apps.isEmpty()) EmptyState(Icons.Outlined.Apps, "Нет открытых приложений", "Откройте приложение на компьютере и обновите список.")
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(state.apps, key = { it.id }) { app -> AppRow(app, { exit = app }, { terminate = app }) }; item { Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = { all = true }, enabled = state.apps.isNotEmpty() && !state.loading, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Close, null); Spacer(Modifier.width(8.dp)); Text("Завершить все принудительно") }; Spacer(Modifier.height(20.dp)) } }
    }
}

@Composable private fun AppRow(app: RemoteApp, exit: () -> Unit, terminate: () -> Unit) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { val bitmap = remember(app.iconPngBase64) { runCatching { val bytes = Base64.decode(app.iconPngBase64, Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }; if (bitmap != null) Image(bitmap, null, Modifier.size(44.dp)) else Icon(Icons.Outlined.Window, null, Modifier.size(44.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(app.name, fontWeight = FontWeight.Medium); Text(if (app.windowCount == 0) "В трее" else "Окон: ${app.windowCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(onClick = exit) { Text("Выйти") }; IconButton(onClick = terminate) { Icon(Icons.Outlined.Close, "Завершить принудительно") } } } }
@Composable private fun MessageBanner(message: String, error: Boolean, dismiss: () -> Unit, modifier: Modifier = Modifier) { val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer; val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer; Surface(modifier.padding(16.dp), color = container, shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp) { Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(message, Modifier.weight(1f), color = content); IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, if (error) "Закрыть ошибку" else "Закрыть сообщение", tint = content) } } } }
@Composable private fun EmptyState(icon: ImageVector, title: String, subtitle: String) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(icon, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(14.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Text(subtitle, Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun Confirm(title: String, text: String, yes: () -> Unit, no: () -> Unit) { AlertDialog(onDismissRequest = no, title = { Text(title) }, text = { Text(text) }, confirmButton = { Button(onClick = yes) { Text("Продолжить") } }, dismissButton = { TextButton(onClick = no) { Text("Отмена") } }) }
