using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using PCRemote.Windows.Models;
using PCRemote.Windows.Services;
using QRCoder;

namespace PCRemote.Windows;

public partial class MainWindow : Window
{
    private readonly AppSettings _settings;
    private readonly SecureStore _store;
    private readonly PairingService _pairing;
    private readonly ServerHost _server;
    private bool _allowClose;

    public MainWindow(AppSettings settings, SecureStore store, PairingService pairing, ServerHost server)
    {
        InitializeComponent();
        _settings = settings; _store = store; _pairing = pairing; _server = server;
        ComputerNameText.Text = settings.ComputerName;
        ComputerNameBox.Text = settings.ComputerName;
        AutoStartCheck.IsChecked = AutoStartService.IsEnabled();
        VersionText.Text = "PC Remote " + UpdateService.CurrentVersion;
        RefreshDevices();
        server.StateChanged += (_, state) => Dispatcher.Invoke(() =>
        {
            ServerStatusText.Text = state.ServerRunning ? "Работает" : "Остановлен";
            DiscoveryStatusText.Text = state.DiscoveryRunning ? "Доступно" : "Недоступно";
        });
    }

    public void SetServerError(string message)
    {
        ServerStatusText.Text = "Ошибка";
        ServerStatusText.ToolTip = message;
    }

    private async void CheckUpdates(object sender, RoutedEventArgs e)
    {
        UpdateButton.IsEnabled = false;
        UpdateStatus.Text = "Проверка обновлений…";
        try
        {
            var update = await UpdateService.CheckAsync();
            if (update is null) { UpdateStatus.Text = "Установлена последняя опубликованная версия."; return; }
            UpdateStatus.Text = "Доступна версия " + update.Version;
            if (System.Windows.MessageBox.Show($"Доступна версия {update.Version}.\n\n{update.Notes}\n\nСкачать обновление и запустить установщик? PC Remote будет закрыт.", "Обновление PC Remote", MessageBoxButton.YesNo, MessageBoxImage.Information) != MessageBoxResult.Yes) return;
            UpdateStatus.Text = "Скачивание и проверка обновления…";
            var path = await UpdateService.DownloadAsync(update);
            var start = new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute = true };
            start.ArgumentList.Add("/DIR=" + AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar));
            System.Diagnostics.Process.Start(start);
            await ((App)System.Windows.Application.Current).ExitAsync();
        }
        catch { UpdateStatus.Text = "Не удалось обновить приложение. Проверьте интернет и повторите попытку. Релиз должен содержать установщик с SHA-256."; }
        finally { UpdateButton.IsEnabled = true; }
    }

    public void ExitApplication() { _allowClose = true; Close(); }
    private void OnClosing(object? sender, CancelEventArgs e) { if (!_allowClose) { e.Cancel = true; Hide(); } }
    private void ShowHome(object s, RoutedEventArgs e) => ShowPanel(HomePanel);
    private void ShowDevices(object s, RoutedEventArgs e) { RefreshDevices(); ShowPanel(DevicesPanel); }
    private void ShowSettings(object s, RoutedEventArgs e) => ShowPanel(SettingsPanel);
    private void OnSourceInitialized(object? sender, EventArgs e) => ThemeService.ApplyWindow(this);
    private void ShowPanel(UIElement panel) { HomeButton.Tag = panel == HomePanel ? "Selected" : null; DevicesButton.Tag = panel == DevicesPanel ? "Selected" : null; SettingsButton.Tag = panel == SettingsPanel ? "Selected" : null; HomePanel.Visibility = DevicesPanel.Visibility = SettingsPanel.Visibility = Visibility.Collapsed; panel.Visibility = Visibility.Visible; }
    private void RefreshDevices() { lock (_settings) { DevicesList.ItemsSource = null; DevicesList.ItemsSource = _settings.Devices.Select(x => new { x.Id, x.Name, LastSeenDisplay = x.LastSeenUtc is null ? "Ещё не подключалось" : $"Последнее подключение: {x.LastSeenUtc.Value.ToLocalTime():g}" }).ToList(); DeviceCountText.Text = _settings.Devices.Count.ToString(); } }

    private void CreatePairing(object s, RoutedEventArgs e)
    {
        var session = _pairing.CreateSession(_server.Port, _server.CertificateFingerprint);
        using var data = QRCodeGenerator.GenerateQrCode(session.Payload, QRCodeGenerator.ECCLevel.Q);
        var bytes = new PngByteQRCode(data).GetGraphic(8);
        var image = new BitmapImage(); image.BeginInit(); image.StreamSource = new MemoryStream(bytes); image.CacheOption = BitmapCacheOption.OnLoad; image.EndInit();
        QrImage.Source = image; PairingExpiryText.Text = $"Код действует до {session.ExpiresUtc.ToLocalTime():T}"; PairingCard.Visibility = Visibility.Visible;
    }

    private void ClosePairing(object s, RoutedEventArgs e) { _pairing.CancelSession(); PairingCard.Visibility = Visibility.Collapsed; RefreshDevices(); }
    private async void RevokeDevice(object s, RoutedEventArgs e) { if ((s as FrameworkElement)?.Tag is string id && System.Windows.MessageBox.Show("Отвязать это устройство? Доступ будет прекращён немедленно.", "PC Remote", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes) { lock (_settings) _settings.Devices.RemoveAll(x => x.Id == id); await _store.SaveAsync(_settings); RefreshDevices(); } }
    private void AutoStartChanged(object s, RoutedEventArgs e) { if (!IsLoaded) return; AutoStartService.SetEnabled(AutoStartCheck.IsChecked == true); }
    private async void SaveName(object s, RoutedEventArgs e) { var value = ComputerNameBox.Text.Trim(); if (value.Length is < 1 or > 64) { System.Windows.MessageBox.Show("Имя должно содержать от 1 до 64 символов.", "PC Remote"); return; } _settings.ComputerName = value; await _store.SaveAsync(_settings); ComputerNameText.Text = value; await _server.RefreshAdvertisementAsync(); }
}
