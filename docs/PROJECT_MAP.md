# Карта проекта PC Remote

## Общая архитектура

Каждая Windows-установка управляет одним компьютером; Android-клиент может хранить несколько сопряжённых Windows-компьютеров и переключаться между ними. Единый WPF-процесс содержит интерфейс, трей, ASP.NET Core Minimal API, HTTPS, DNS-SD и Win32-операции. Android-клиент на Kotlin/Compose обнаруживает ПК через NSD, проверяет TLS-отпечаток из одноразового QR-кода и использует отдельный токен для каждого компьютера.

```text
Android UI → ViewModel → NSD / HTTPS API → WPF-процесс → проверка авторизации → Win32
                               ↑                  ↓
                    Android Keystore        DPAPI-хранилище
```

## Структура проекта

```text
AGENTS.md                                  постоянные правила Codex
README.md                                  сборка, установка и использование
docs/PROJECT_MAP.md                        эта карта
packaging/Build-Release.ps1                сборка релиза, постоянная Android-подпись, SHA-256
packaging/windows/PCRemote.iss             установщик Windows с выбором папки
windows/PCRemote.Windows/
  PCRemote.Windows.csproj                  WPF WinExe + ASP.NET Core
  App.xaml(.cs)                            запуск, mutex, IPC, жизненный цикл
  MainWindow.xaml(.cs)                     главная, устройства, настройки
  Models/Contracts.cs                      настройки и API-модели
  Services/                                сеть, безопасность и Win32
android/
  settings.gradle.kts, build.gradle.kts    конфигурация сборки
  gradlew(.bat), gradle/wrapper/            Gradle Wrapper 8.13
  app/build.gradle.kts                     Android-модуль и зависимости
  app/src/main/AndroidManifest.xml          разрешения и точка входа
  app/src/main/java/dev/pcremote/app/       Compose UI и состояние
  app/src/main/java/dev/pcremote/app/data/  сеть, NSD и Keystore
  app/src/androidTest/                      тест реального HTTPS-клиента на эмуляторе
tests/PCRemote.Checks/                     безопасные Windows/HTTPS integration checks
tests/Test-Android.ps1                     сборка и запуск сквозной проверки на эмуляторе
```

Windows сохраняет зашифрованный DPAPI JSON в `%LocalAppData%/PCRemote/settings.dat`. Android хранит зашифрованный AES-GCM ключом Android Keystore список привязок и выбранный компьютер; одиночная привязка прежней версии переносится автоматически.

## Windows

- `App.xaml.cs` — единственный экземпляр через mutex; named pipe активирует существующее окно; управляет сервером и треем.
- `MainWindow.xaml(.cs)` — русский адаптивный интерфейс с glyph-иконками Segoe MDL2 Assets, системная светлая/тёмная тема, QR и управление устройствами.
- `Services/TrayService.cs` — значок, двойной клик, «Открыть» и «Выход».
- `Services/AutoStartService.cs` — HKCU Run без повышения прав; по умолчанию выключен, включается пользователем.
- `Services/ThemeService.cs` — системная тема Windows.
- `Services/UpdateService.cs` — ручная проверка последнего стабильного GitHub Release, скачивание установщика с проверкой размера/SHA-256; окно запускает установщик и корректно завершает приложение.
- `Services/ServerHost.cs` — Kestrel HTTPS, Minimal API, локальная адресная проверка, bearer-фильтр, DNS-SD только с адресами частных IPv4-адаптеров Ethernet/Wi-Fi с локальным шлюзом.
- `Services/CertificateService.cs` — локальный RSA-сертификат и SHA-256 fingerprint.
- `Services/PairingService.cs` — трёхминутный одноразовый секрет, лимит попыток, уникальный device token.
- `Services/SecureStore.cs` — атомарная запись и DPAPI CurrentUser.
- `Services/WindowsAppService.cs` — видимые окна и приложения с действующим значком трея (Shell_NotifyIconGetRect; идентификаторы из NotifyIconSettings и стандартный диапазон 0–16), включая message-only окна; скрытые окна сами по себе не добавляются. Группировка по точному executable path, иконки; обычный запрос на выход и принудительное завершение дерева процессов; проверка PID/start time/path, владельца, сеанса и критичности; случайные идентификаторы снимков действуют 2 минуты.
- `Services/CommandService.cs` — последовательное выполнение, десятиминутная дедупликация request id, LockWorkStation и ExitWindowsEx для перезагрузки/выключения.

## Android

- `MainActivity.kt` — Material 3 UI: подключение, главная, приложения, выбор компьютера и настройки в нижней навигации; закрываемые сообщения об ошибках и результате, подтверждения блокировки, перезагрузки, выключения, выхода и принудительного завершения приложений; встроенный ZXing QR scanner.
- `UpdateViewModel.kt` — отдельное состояние обновлений в разделе «Настройки» (доступен до сопряжения), GitHub Releases, проверка размера/SHA-256/Android-подписи и системная установка APK. `res/xml/update_paths.xml` ограничивает FileProvider каталогом обновлений в cache.
- `MainViewModel.kt` — StateFlow, активный health-check выбранного компьютера раз в 5 секунд, многокомпьютерное сопряжение/переключение и событийное обновление приложений.
- `data/DiscoveryManager.kt` — Android NSD `_pcremote._tcp`, активный MulticastLock для Android 10–12, обновление IP только после совпадения computer id; mDNS не считается доказательством идентичности.
- `data/ApiClient.kt` — HTTPS с SHA-256 certificate pin, таймауты, bearer token и request id.
- `data/SecurePreferences.kt` — AES-GCM, Android Keystore и миграция одного старого компьютера в список.
- `data/Models.kt` — локальные модели ПК и приложений.

## API

Префикс `/api/v1`; JSON использует camelCase. Ошибка: `{ code, message, traceId }`. Все команды, кроме health/pair, требуют `Authorization: Bearer <token>`. Опасные POST требуют уникальный `X-Request-Id`.

| Метод и путь | Назначение | Авторизация |
|---|---|---|
| `GET /api/v1/health` | лёгкая проверка доступности | нет |
| `POST /api/v1/pair` | одноразовое сопряжение | QR secret + TLS pin |
| `GET /api/v1/auth/check` | проверка токена | token |
| `POST /api/v1/commands/lock` | блокировка сеанса | token + request id |
| `POST /api/v1/commands/reboot` | перезагрузка | token + request id |
| `POST /api/v1/commands/shutdown` | выключение | token + request id |
| `GET /api/v1/apps` | снимок открытых приложений | token |
| `POST /api/v1/apps/{id}/exit` | обычный запрос приложению на выход | token + request id |
| `POST /api/v1/apps/{id}/terminate` | завершение проверенной группы | token + request id |
| `POST /api/v1/apps/terminate-all` | завершение всех разрешённых групп | token + request id |

## Навигация

| Задача | Файлы или модули |
|---|---|
| Интерфейс Windows | `windows/PCRemote.Windows/MainWindow.xaml`, `windows/PCRemote.Windows/MainWindow.xaml.cs`, `windows/PCRemote.Windows/Services/ThemeService.cs` |
| Интерфейс Android | `android/app/src/main/java/dev/pcremote/app/MainActivity.kt`, `android/app/src/main/java/dev/pcremote/app/MainViewModel.kt` |
| Работа с процессами | `windows/PCRemote.Windows/Services/WindowsAppService.cs`, `windows/PCRemote.Windows/Services/CommandService.cs` |
| Авторизация | `windows/PCRemote.Windows/Services/PairingService.cs`, `windows/PCRemote.Windows/Services/SecureStore.cs`, Android `data/ApiClient.kt` и `data/SecurePreferences.kt` |
| HTTP API | `windows/PCRemote.Windows/Services/ServerHost.cs`, `windows/PCRemote.Windows/Models/Contracts.cs`, Android `data/ApiClient.kt` |
| Обнаружение ПК | `windows/PCRemote.Windows/Services/ServerHost.cs`, Android `data/DiscoveryManager.kt` |
| Сборка и проверки | `windows/PCRemote.Windows/PCRemote.Windows.csproj`, `android/build.gradle.kts`, `android/app/build.gradle.kts`, `tests/PCRemote.Checks/Program.cs`, `tests/Test-Android.ps1` |
| Установка и обновления | `packaging/Build-Release.ps1`, `packaging/windows/PCRemote.iss`, Windows `Services/UpdateService.cs`, Android `UpdateViewModel.kt`, `AndroidManifest.xml`, `res/xml/update_paths.xml` |

Android `data/` в таблице находится в `android/app/src/main/java/dev/pcremote/app/data/`.

## Статус реализации

| Возможность | Статус | Проверка |
|---|---|---|
| Структура, AGENTS и документация | Проверено | пути сверены с файлами |
| WPF-интерфейс, тема и трей | Реализовано | Release собран, окно и настройки осмотрены; меню трея вручную |
| Единственный экземпляр и автозапуск | Реализовано | второй процесс завершается, один рабочий экземпляр; галочка выключена по умолчанию; вход в Windows вручную |
| HTTPS API и локальные ограничения | Реализовано | HTTPS и 401/400 проверены локально; граница LAN вручную |
| DNS-SD / Android NSD | Реализовано | нужна общая сеть и телефон |
| QR, TLS pin, токены, отзыв | Реализовано | pairing API, pin, replay, DPAPI/Keystore и отзыв проверены; камера вручную |
| Блокировка, перезагрузка и выключение | Реализовано | системные команды не запускались |
| Список, иконки и завершение приложений | Реализовано | API списка, группировка, выход и завершение тестовых окон; скрытое окно без значка исключено, живой значок добавлен, устаревшая запись исключена; массовое закрытие вручную |
| Android Compose UI и хранение | Реализовано | debug APK/lint; предыдущая одиночная привязка мигрирует; список компьютеров требует ручной проверки |
| Android сеть и восстановление | Реализовано | HTTPS Android→Windows проверен; восстановление сети вручную |
| Установщик и обновления через GitHub Releases | Реализовано | Windows self-contained и подписанный Android release APK собраны, lintRelease пройден; мастер осмотрен до установки, тестовая установка/удаление выполнены; обновление на следующую версию требует проверки при следующем релизе |
| Сквозные сценарии MVP | В работе | доступные безопасные тесты пройдены, реальные сеть/камера/команды проверяет пользователь |
