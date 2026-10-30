using System.IO.Pipes;
using System.Windows;
using PCRemote.Windows.Services;

namespace PCRemote.Windows;

public partial class App : System.Windows.Application
{
    private const string InstanceName = "Local\\PCRemote-8C4E3A26";
    private Mutex? _mutex;
    private CancellationTokenSource? _lifetime;
    private ServerHost? _server;
    private TrayService? _tray;

    protected override async void OnStartup(StartupEventArgs e)
    {
        _mutex = new Mutex(true, InstanceName, out var first);
        if (!first)
        {
            await NotifyExistingInstanceAsync();
            Shutdown();
            return;
        }

        base.OnStartup(e);
        ThemeService.ApplyCurrent(this);
        _lifetime = new CancellationTokenSource();
        var store = new SecureStore();
        var settings = await store.LoadAsync();
        var pairing = new PairingService(store, settings);
        var apps = new WindowsAppService();
        var commands = new CommandService(apps);
        _server = new ServerHost(store, settings, pairing, apps, commands);

        var window = new MainWindow(settings, store, pairing, _server);
        MainWindow = window;
        _tray = new TrayService(window, ExitAsync);
        if (!e.Args.Contains("--background")) window.Show();
        _ = ListenForActivationAsync(window, _lifetime.Token);

        try { await _server.StartAsync(_lifetime.Token); }
        catch (Exception ex) { window.SetServerError(ex.Message); }
    }

    private static async Task NotifyExistingInstanceAsync()
    {
        try
        {
            using var pipe = new NamedPipeClientStream(".", "PCRemote.Open", PipeDirection.Out);
            await pipe.ConnectAsync(800);
            await pipe.WriteAsync(new byte[] { 1 });
        }
        catch { }
    }

    private static async Task ListenForActivationAsync(Window window, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                await using var pipe = new NamedPipeServerStream("PCRemote.Open", PipeDirection.In, 1,
                    PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await pipe.WaitForConnectionAsync(token);
                var buffer = new byte[1];
                await pipe.ReadAsync(buffer, token);
                await window.Dispatcher.InvokeAsync(() =>
                {
                    window.Show(); window.WindowState = WindowState.Normal; window.Activate();
                });
            }
            catch (OperationCanceledException) { break; }
            catch { await Task.Delay(500, token); }
        }
    }

    private async Task ExitAsync()
    {
        _lifetime?.Cancel();
        if (_server is not null) await _server.DisposeAsync();
        _tray?.Dispose();
        _mutex?.ReleaseMutex();
        _mutex?.Dispose();
        if (MainWindow is MainWindow window) window.ExitApplication();
        Shutdown();
    }
}
