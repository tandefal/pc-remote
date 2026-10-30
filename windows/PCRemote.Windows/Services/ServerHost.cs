using System.Net;
using System.Net.NetworkInformation;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Makaretu.Dns;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PCRemote.Windows.Models;

namespace PCRemote.Windows.Services;

public sealed class ServerHost(SecureStore store, AppSettings settings, PairingService pairing, WindowsAppService apps, CommandService commands) : IAsyncDisposable
{
    private WebApplication? _app;
    private ServiceDiscovery? _discovery;
    private X509Certificate2? _certificate;
    public int Port => settings.Port;
    public string CertificateFingerprint => _certificate is null ? "" : CertificateService.Fingerprint(_certificate);
    public event EventHandler<ServerState>? StateChanged;

    public async Task StartAsync(CancellationToken lifetime)
    {
        _certificate = await CertificateService.GetOrCreateAsync(settings, store);
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders(); builder.Logging.AddJsonConsole();
        builder.WebHost.ConfigureKestrel(k => k.ListenAnyIP(settings.Port, listen => listen.UseHttps(_certificate)));
        builder.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.PropertyNamingPolicy = System.Text.Json.JsonNamingPolicy.CamelCase);
        _app = builder.Build();

        _app.Use(async (context, next) =>
        {
            if (!IsLocalAddress(context.Connection.RemoteIpAddress)) { await WriteError(context, 403, "local_network_required", "Доступ разрешён только из локальной сети."); return; }
            try { await next(); }
            catch (Exception ex) { context.RequestServices.GetRequiredService<ILogger<ServerHost>>().LogError(ex, "Unhandled request failure"); if (!context.Response.HasStarted) await WriteError(context, 500, "internal_error", "Внутренняя ошибка сервера."); }
        });

        _app.MapGet("/api/v1/health", () => Results.Ok(new { status = "ok", computerId = settings.ComputerId, computerName = settings.ComputerName, version = "1.0.0" }));
        _app.MapPost("/api/v1/pair", async (PairRequest request) => { var result = await pairing.PairAsync(request, CertificateFingerprint); return result is null ? Results.Json(new ApiError("pairing_rejected", "Код недействителен или истёк.", Guid.NewGuid().ToString("N")), statusCode: 401) : Results.Ok(result); });

        var protectedApi = _app.MapGroup("/api/v1");
        protectedApi.AddEndpointFilter(async (invocation, next) =>
        {
            var http = invocation.HttpContext;
            if (!TryAuthorize(http, out var device)) return Results.Json(new ApiError("unauthorized", "Требуется действующая авторизация устройства.", http.TraceIdentifier), statusCode: 401);
            lock (settings) device!.LastSeenUtc = DateTimeOffset.UtcNow;
            await store.SaveAsync(settings); return await next(invocation);
        });
        protectedApi.MapGet("/auth/check", () => Results.Ok(new { authorized = true, computerName = settings.ComputerName }));
        protectedApi.MapGet("/apps", () => Results.Ok(apps.GetApplications()));
        protectedApi.MapPost("/commands/lock", (Func<HttpContext, Task<IResult>>)(c => ExecuteCommand(c, CommandService.Lock)));
        protectedApi.MapPost("/commands/reboot", (Func<HttpContext, Task<IResult>>)(c => ExecuteCommand(c, CommandService.Reboot)));
        protectedApi.MapPost("/commands/shutdown", (Func<HttpContext, Task<IResult>>)(c => ExecuteCommand(c, CommandService.Shutdown)));
        protectedApi.MapPost("/apps/{id}/exit", (string id, HttpContext c) => ExecuteCommand(c, () => commands.Exit(id)));
        protectedApi.MapPost("/apps/{id}/terminate", (string id, HttpContext c) => ExecuteCommand(c, () => commands.Terminate(id)));
        protectedApi.MapPost("/apps/terminate-all", (Func<HttpContext, Task<IResult>>)(c => ExecuteCommand(c, commands.TerminateAll)));

        await _app.StartAsync(lifetime);
        StartAdvertisement();
        StateChanged?.Invoke(this, new(true, _discovery is not null));
    }

    public Task RefreshAdvertisementAsync() { StopAdvertisement(); StartAdvertisement(); return Task.CompletedTask; }
    private void StartAdvertisement()
    {
        try
        {
            _discovery = new ServiceDiscovery();
            var profile = new ServiceProfile($"PC Remote {settings.ComputerId[..8]}", "_pcremote._tcp", (ushort)settings.Port, GetAdvertisedAddresses());
            profile.AddProperty("id", settings.ComputerId); profile.AddProperty("name", settings.ComputerName); profile.AddProperty("cert", CertificateFingerprint);
            _discovery.Advertise(profile);
        }
        catch { _discovery?.Dispose(); _discovery = null; }
    }
    private static IEnumerable<IPAddress> GetAdvertisedAddresses()
    {
        return NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up && adapter.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211)
            .Select(adapter => adapter.GetIPProperties())
            .Where(properties => properties.GatewayAddresses.Any(gateway => gateway.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !IPAddress.Any.Equals(gateway.Address)))
            .SelectMany(properties => properties.UnicastAddresses)
            .Select(address => address.Address)
            .Where(address => address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && IsPrivateIPv4(address))
            .Distinct()
            .ToArray();
    }
    private static bool IsPrivateIPv4(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes.Length == 4 && (bytes[0] == 10 || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31) || (bytes[0] == 192 && bytes[1] == 168));
    }
    private void StopAdvertisement() { _discovery?.Dispose(); _discovery = null; }
    private bool TryAuthorize(HttpContext context, out TrustedDevice? device)
    {
        device = null; var header = context.Request.Headers.Authorization.ToString(); if (!header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)) return false;
        var hash = SecureStore.HashToken(header[7..]);
        lock (settings) device = settings.Devices.FirstOrDefault(x => CryptographicOperations.FixedTimeEquals(Convert.FromHexString(x.TokenHash), Convert.FromHexString(hash)));
        return device is not null;
    }
    private async Task<IResult> ExecuteCommand(HttpContext context, Func<CommandResult> action)
    {
        var requestId = context.Request.Headers["X-Request-Id"].FirstOrDefault();
        if (!Guid.TryParse(requestId, out var id)) return Results.Json(new ApiError("invalid_request_id", "Требуется UUID в X-Request-Id.", context.TraceIdentifier), statusCode: 400);
        if (!TryAuthorize(context, out var device)) return Results.Json(new ApiError("unauthorized", "Доступ устройства отозван.", context.TraceIdentifier), statusCode: 401);
        var result = await commands.ExecuteAsync($"{device!.Id}:{context.Request.Path}:{id:N}", () =>
        {
            lock (settings)
            {
                if (!TryAuthorize(context, out _)) return new(false, "Доступ устройства отозван.");
                return action();
            }
        });
        return ToResult(result);
    }
    private static IResult ToResult(CommandResult r) => r.Success ? Results.Ok(r) : Results.Json(r, statusCode: 409);
    private static bool IsLocalAddress(IPAddress? ip)
    {
        if (ip is null || IPAddress.IsLoopback(ip)) return true; if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        var b = ip.GetAddressBytes(); if (b.Length == 4) return b[0] == 10 || b[0] == 127 || (b[0] == 192 && b[1] == 168) || (b[0] == 172 && b[1] is >= 16 and <= 31) || (b[0] == 169 && b[1] == 254);
        return ip.IsIPv6LinkLocal || (b[0] & 0xFE) == 0xFC;
    }
    private static Task WriteError(HttpContext c, int status, string code, string message) { c.Response.StatusCode = status; return c.Response.WriteAsJsonAsync(new ApiError(code, message, c.TraceIdentifier)); }
    public async ValueTask DisposeAsync() { StopAdvertisement(); if (_app is not null) { await _app.StopAsync(TimeSpan.FromSeconds(5)); await _app.DisposeAsync(); } _certificate?.Dispose(); }
}
