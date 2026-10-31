package dev.pcremote.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import dev.pcremote.app.data.ComputerCredentials
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
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

@Composable private fun PCRemoteApp(state: UiState, vm: MainViewModel, updates: UpdateViewModel, scan: ((String) -> Unit, (String) -> Unit) -> Unit) {
    Scaffold(bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
            if (state.credentials != null) {
                NavItem(Page.HOME, state.page, Icons.Outlined.Home, "Главная", vm::select)
                NavItem(Page.APPS, state.page, Icons.Outlined.Apps, "Приложения", vm::select)
                NavItem(Page.COMPUTERS, state.page, Icons.Outlined.Computer, "Компьютеры", vm::select)
            } else {
                NavItem(Page.CONNECT, state.page, Icons.Outlined.QrCodeScanner, "Подключение", vm::select)
            }
            NavItem(Page.SETTINGS, state.page, Icons.Outlined.Settings, "Настройки", vm::select)
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            when (state.page) {
                Page.CONNECT -> ConnectScreen(state, { scan(vm::acceptQr, vm::reportError) })
                Page.HOME -> if (state.connecting) ConnectingScreen(state) else HomeScreen(state, vm)
                Page.APPS -> if (state.connecting) ConnectingScreen(state) else AppsScreen(state, vm)
                Page.COMPUTERS -> ComputersScreen(state, vm) { scan(vm::acceptQr, vm::reportError) }
                Page.SETTINGS -> SettingsScreen(updates)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            state.error?.let { MessageBanner(it, true, vm::dismissError, Modifier.align(Alignment.BottomCenter)) }
                ?: state.notice?.let { MessageBanner(it, false, vm::dismissNotice, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

@Composable private fun SettingsScreen(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 24.dp)) {
        item { Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
        item {
            RemoteCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Обновления приложения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("PC Remote ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = vm::check, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Проверить обновления") }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.message.isNotBlank()) Text(state.message)
                }
            }
        }
        state.release?.let { release ->
            item { Text(release.notes.ifBlank { "Новая версия PC Remote" }) }
            item { Button(onClick = if (state.apk == null) vm::download else vm::install, enabled = !state.busy) { Text(if (state.apk == null) "Скачать обновление" else "Установить") } }
        }
    }
}

@Composable private fun RowScope.NavItem(page: Page, selected: Page, icon: ImageVector, label: String, select: (Page) -> Unit) { NavigationBarItem(selected = page == selected, onClick = { select(page) }, icon = { Icon(icon, null) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.onSurface, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant)) }

@Composable private fun ConnectScreen(state: UiState, scan: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.padding(24.dp).size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(24.dp)); Text("PC Remote", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(12.dp)); Text("Подключение к компьютеру", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp)); Text("На компьютере откройте «Устройства», нажмите «Добавить телефон» и отсканируйте QR-код.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp)); Button(onClick = scan, enabled = !state.pairing, modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text(if (state.pairing) "Поиск компьютера…" else "Сканировать QR-код") }
        if (state.pairing) { Spacer(Modifier.height(16.dp)); CircularProgressIndicator() }
    }
}

@Composable private fun ConnectingScreen(state: UiState) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text("Подключение…", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(state.credentials?.name.orEmpty(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun HomeScreen(state: UiState, vm: MainViewModel) {
    var confirmShutdown by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    if (confirmShutdown) Confirm("Выключить компьютер?", "Все несохранённые данные могут быть потеряны.", { confirmShutdown = false; vm.shutdown() }, { confirmShutdown = false })
    if (confirmReboot) Confirm("Перезагрузить компьютер?", "Сохраните данные в открытых приложениях перед перезагрузкой.", { confirmReboot = false; vm.reboot() }, { confirmReboot = false })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp)) {
        item {
            Text("Главная", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            RemoteCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Computer, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(state.credentials?.name ?: "Компьютер", style = MaterialTheme.typography.headlineMedium)
                    val statusColor = if (state.online) RemoteSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                    Surface(color = statusColor.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (state.online) Icons.Outlined.CheckCircle else Icons.Outlined.CloudOff, null, Modifier.size(18.dp), tint = statusColor)
                            Text(if (state.online) "В сети" else "Нет соединения", color = statusColor, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        item { ActionCard(Icons.Outlined.Lock, if (state.locked == true) "Компьютер заблокирован" else "Заблокировать", if (state.locked == true) "Сеанс Windows заблокирован" else "Заблокировать текущий сеанс Windows", state.online && !state.loading && state.locked == false, action = vm::lock); Spacer(Modifier.height(16.dp)); ActionCard(Icons.Outlined.RestartAlt, "Перезагрузить", "Перезапустить компьютер", state.online && !state.loading) { confirmReboot = true }; Spacer(Modifier.height(16.dp)); ActionCard(Icons.Outlined.PowerSettingsNew, "Выключить", "Корректно выключить компьютер", state.online && !state.loading, destructive = true) { confirmShutdown = true } }
    }
}

@Composable private fun ComputersScreen(state: UiState, vm: MainViewModel, scan: () -> Unit) {
    var removing by remember { mutableStateOf<ComputerCredentials?>(null) }
    removing?.let { computer ->
        Confirm("Удалить сопряжение с «${computer.name}»?", "Компьютер будет удалён с телефона. Для подключения понадобится новый QR-код. Запись телефона на ПК можно удалить в разделе «Устройства».",
            { removing = null; vm.removeComputer(computer.id) }, { removing = null })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("Компьютеры", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Text("Выберите компьютер для управления или добавьте ещё один.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        Button(onClick = scan, enabled = !state.loading && !state.pairing, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(if (state.pairing) "Поиск компьютера…" else "Добавить компьютер") }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.computers, key = { it.id }) { computer ->
                val selected = state.credentials?.id == computer.id
                Card(onClick = { vm.selectComputer(computer.id) }, enabled = !state.loading && !state.pairing, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.primaryContainer, disabledContentColor = MaterialTheme.colorScheme.onSurface)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Computer, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(computer.name, fontWeight = FontWeight.SemiBold)
                            Text(if (selected) "Выбран · ${if (state.online) "в сети" else "проверка соединения…"}" else "Нажмите, чтобы переключиться", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected) Icon(Icons.Outlined.CheckCircle, "Выбран", tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { removing = computer }, enabled = !state.loading && !state.pairing) {
                            Icon(Icons.Outlined.DeleteOutline, "Удалить сопряжение", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ActionCard(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, destructive: Boolean = false, action: () -> Unit) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = if (enabled) 0.12f else 0.05f)) {
                Icon(icon, null, Modifier.padding(13.dp).size(26.dp), tint = accent.copy(alpha = if (enabled) 1f else 0.4f))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f))
            }
        }
    }
}

@Composable private fun AppsScreen(state: UiState, vm: MainViewModel) {
    var terminate by remember { mutableStateOf<RemoteApp?>(null) }
    terminate?.let { app -> Confirm("Завершить ${app.name} принудительно?", "Несохранённые данные будут потеряны.", { terminate = null; vm.terminate(app.id) }, { terminate = null }) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text("Приложения", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)) }
        if (!state.online) EmptyState(Icons.Outlined.CloudOff, "Соединение не установлено", "Список появится автоматически после подключения.")
        else if (state.apps.isEmpty()) EmptyState(Icons.Outlined.Apps, "Нет открытых приложений", "Откройте приложение на компьютере — список обновится автоматически.")
        else LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(state.apps, key = { it.id }) { app ->
                AppRow(app, !state.loading) { terminate = app }
            }
        }
    }
}

@Composable private fun AppRow(app: RemoteApp, enabled: Boolean, terminate: () -> Unit) {
    RemoteCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val bitmap = remember(app.iconPngBase64) { runCatching {
                val bytes = Base64.decode(app.iconPngBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull() }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.padding(10.dp)) {
                    if (bitmap != null) Image(bitmap, null, Modifier.size(32.dp))
                    else Icon(Icons.Outlined.Window, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (app.windowCount == 0) "В трее" else "Окон: ${app.windowCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = terminate, enabled = enabled) {
                Icon(Icons.Outlined.Close, "Завершить ${app.name} принудительно", tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        }
    }
}
@Composable private fun MessageBanner(message: String, error: Boolean, dismiss: () -> Unit, modifier: Modifier = Modifier) { val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer; val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer; Surface(modifier.padding(16.dp), color = container, shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp) { Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(message, Modifier.weight(1f), color = content); IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, if (error) "Закрыть ошибку" else "Закрыть сообщение", tint = content) } } } }
@Composable private fun EmptyState(icon: ImageVector, title: String, subtitle: String) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(icon, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(14.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Text(subtitle, Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun Confirm(title: String, text: String, yes: () -> Unit, no: () -> Unit) { AlertDialog(onDismissRequest = no, title = { Text(title) }, text = { Text(text) }, confirmButton = { Button(onClick = yes) { Text("Продолжить") } }, dismissButton = { TextButton(onClick = no) { Text("Отмена") } }) }
