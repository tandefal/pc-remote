using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using System.IO;
using System.Diagnostics;
using PCRemote.Windows.Models;
using PCRemote.Windows.Services;

if (args.Contains("--test-window"))
{
    var thread = new Thread(() =>
    {
        var window = new System.Windows.Window { Title = "PC Remote safe test window", Width = 200, Height = 100, Left = -10000, Top = -10000, ShowInTaskbar = true };
        using var icon = new System.Windows.Forms.NotifyIcon { Icon = System.Drawing.SystemIcons.Application, Text = "PC Remote — тест трея" };
        window.Loaded += (_, _) =>
        {
            if (args.Contains("--hidden")) window.Hide();
            icon.Visible = args.Contains("--tray");
            var readyName = args.FirstOrDefault(x => x.StartsWith("--ready="))?[8..];
            if (readyName is not null) { using var ready = EventWaitHandle.OpenExisting(readyName); ready.Set(); }
        };
        new System.Windows.Application().Run(window);
    });
    thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join(); return;
}

// Real service integration, isolated DPAPI file; no lock/shutdown/terminate calls.
var directory = Path.Combine(Path.GetTempPath(), "PCRemoteChecks", Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(directory);
var path = Path.Combine(directory, "settings.dat");
var store = new SecureStore(path);
var listener = new TcpListener(IPAddress.Loopback, 0);
listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
var settings = new AppSettings { Port = port, ComputerName = "Проверка PC Remote" };
var pairing = new PairingService(store, settings);
var apps = new WindowsAppService();
var commands = new CommandService(apps);
await using var server = new ServerHost(store, settings, pairing, apps, commands);
await server.StartAsync(CancellationToken.None);
using var handler = new HttpClientHandler();
handler.ServerCertificateCustomValidationCallback = (_, certificate, _, _) => certificate is not null &&
    Convert.ToHexString(SHA256.HashData(certificate.RawData)).Equals(server.CertificateFingerprint, StringComparison.OrdinalIgnoreCase);
using var client = new HttpClient(handler) { BaseAddress = new Uri($"https://127.0.0.1:{port}"), Timeout = TimeSpan.FromSeconds(10) };
void Check(bool condition, string name) { if (!condition) throw new Exception("FAIL: " + name); Console.WriteLine("PASS: " + name); }
Check((await client.GetAsync("/api/v1/health")).IsSuccessStatusCode, "HTTPS health with certificate pin");
foreach (var route in new[] { "/api/v1/apps", "/api/v1/auth/check" })
    Check((await client.GetAsync(route)).StatusCode == HttpStatusCode.Unauthorized, "Unauthorized GET " + route);
foreach (var route in new[] { "/api/v1/commands/lock", "/api/v1/commands/shutdown", "/api/v1/apps/example/exit", "/api/v1/apps/terminate-all" })
    Check((await client.PostAsync(route, null)).StatusCode == HttpStatusCode.Unauthorized, "Unauthorized POST " + route);
var session = pairing.CreateSession(port, server.CertificateFingerprint);
var secret = JsonDocument.Parse(session.Payload).RootElement.GetProperty("secret").GetString()!;
var response = await client.PostAsJsonAsync("/api/v1/pair", new PairRequest(secret, "Тестовый телефон"));
Check(response.IsSuccessStatusCode, "Pairing");
var credentials = (await response.Content.ReadFromJsonAsync<PairResponse>())!;
Check((await client.PostAsJsonAsync("/api/v1/pair", new PairRequest(secret, "Повтор"))).StatusCode == HttpStatusCode.Unauthorized, "Single use QR");
client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", credentials.Token);
Check((await client.GetAsync("/api/v1/auth/check")).IsSuccessStatusCode, "Device authorization");
Check((await client.PostAsync("/api/v1/commands/lock", null)).StatusCode == HttpStatusCode.BadRequest, "Dangerous command without request id rejected");
using (var invalidTarget = new HttpRequestMessage(HttpMethod.Post, "/api/v1/apps/nonexistent/terminate"))
{
    invalidTarget.Headers.Add("X-Request-Id", Guid.NewGuid().ToString());
    Check((await client.SendAsync(invalidTarget)).StatusCode == HttpStatusCode.Conflict, "Unknown application rejected");
}
Check((await client.GetAsync("/api/v1/apps")).IsSuccessStatusCode, "Window enumeration");
Check(!apps.GetApplications().Any(x => x.Name is "PCRemote" or "explorer"), "PC Remote and shell absent from allowed applications");
var children = new List<Process>();
try
{
    var safeWindowExecutable = Path.Combine(AppContext.BaseDirectory, "PCRemote.SafeWindow.exe");
    File.Copy(Path.Combine(AppContext.BaseDirectory, "PCRemote.Checks.exe"), safeWindowExecutable, true);
    for (var i = 0; i < 2; i++) children.Add(Process.Start(new ProcessStartInfo(safeWindowExecutable, "--test-window") { UseShellExecute = false, CreateNoWindow = true })!);
    for (var attempt = 0; attempt < 50; attempt++)
    {
        foreach (var child in children) child.Refresh();
        if (children.All(x => x.MainWindowHandle != IntPtr.Zero)) break;
        await Task.Delay(100);
    }
    var testApp = apps.GetApplications().Single(x => x.Name is "PCRemote.Checks" or "PCRemote.SafeWindow");
    Check(testApp.WindowCount == 2, "Two safe test windows grouped");
    var termination = apps.Terminate(testApp.Id);
    Check(termination.Success && children.All(x => x.WaitForExit(5000)), "Only explicitly created test applications terminated");
    Check(!apps.Terminate(testApp.Id).Success, "Stale application identity rejected");

    var exiting = Process.Start(new ProcessStartInfo(safeWindowExecutable, "--test-window") { UseShellExecute = false, CreateNoWindow = true })!;
    children.Add(exiting);
    for (var attempt = 0; attempt < 50 && exiting.MainWindowHandle == IntPtr.Zero; attempt++) { await Task.Delay(100); exiting.Refresh(); }
    testApp = apps.GetApplications().Single(x => x.Name is "PCRemote.Checks" or "PCRemote.SafeWindow");
    var gracefulExit = apps.Exit(testApp.Id);
    Check(gracefulExit.Success && exiting.WaitForExit(5000), "Synthetic application accepted normal exit request");

    Process StartHiddenWindow(bool tray)
    {
        var readyName = "Local\\PCRemoteChecks-" + Guid.NewGuid().ToString("N");
        using var ready = new EventWaitHandle(false, EventResetMode.ManualReset, readyName);
        var child = Process.Start(new ProcessStartInfo(safeWindowExecutable, $"--test-window --hidden {(tray ? "--tray" : "")} --ready={readyName}") { UseShellExecute = false, CreateNoWindow = true })!;
        children.Add(child);
        Check(ready.WaitOne(5000), "Synthetic hidden window ready");
        return child;
    }
    var background = StartHiddenWindow(false);
    Check(!apps.GetApplications().Any(x => x.Name is "PCRemote.Checks" or "PCRemote.SafeWindow"), "Hidden background window without tray icon excluded");
    background.Kill(false); background.WaitForExit(5000);
    var tray = StartHiddenWindow(true);
    testApp = apps.GetApplications().Single(x => x.Name is "PCRemote.Checks" or "PCRemote.SafeWindow");
    Check(testApp.WindowCount == 0, "Live tray icon included without visible windows");
    Check(apps.Exit(testApp.Id).Success && tray.WaitForExit(5000), "Synthetic tray application exited");
    Check(!apps.GetApplications().Any(x => x.Name is "PCRemote.Checks" or "PCRemote.SafeWindow"), "Stale tray registry entry does not add an application");
}
finally
{
    foreach (var child in children) { if (!child.HasExited) child.Kill(false); child.Dispose(); }
}
var saved = await store.LoadAsync();
Check(saved.Devices.Count == 1 && saved.Devices[0].TokenHash != credentials.Token, "DPAPI storage and token hash");
Check(!System.Text.Encoding.UTF8.GetString(await File.ReadAllBytesAsync(path)).Contains(settings.ComputerName), "Settings encrypted on disk");
var calls = 0;
var results = await Task.WhenAll(Enumerable.Range(0, 12).Select(_ => commands.ExecuteAsync("same-request", () => { calls++; return new(true, "ok"); })));
Check(calls == 1 && results.All(x => x.Success), "Concurrent request deduplication");
await commands.ExecuteAsync("", () => { calls++; return new(true, "unexpected"); });
Check(calls == 1, "Missing request id rejected before action");
settings.Devices.Clear(); await store.SaveAsync(settings);
Check((await client.GetAsync("/api/v1/auth/check")).StatusCode == HttpStatusCode.Unauthorized, "Immediate token revocation");
Console.WriteLine("All checks passed. Only synthetic test windows were terminated; lock and shutdown were not invoked.");
if (args.Contains("--android"))
{
    var androidSession = JsonDocument.Parse(pairing.CreateSession(port, server.CertificateFingerprint).Payload).RootElement;
    var handoff = Path.GetFullPath(Path.Combine("artifacts", "checks-session.json"));
    Directory.CreateDirectory(Path.GetDirectoryName(handoff)!);
    await File.WriteAllTextAsync(handoff, JsonSerializer.Serialize(new { id = settings.ComputerId, port, cert = server.CertificateFingerprint, secret = androidSession.GetProperty("secret").GetString() }));
    Console.WriteLine("Android integration server ready (3 minutes).");
    await Task.Delay(TimeSpan.FromMinutes(3));
    File.Delete(handoff);
}
// Leave temporary test output recoverable; it contains only synthetic settings protected by DPAPI.
