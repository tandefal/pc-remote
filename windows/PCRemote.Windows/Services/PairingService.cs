using System.Text.Json;
using PCRemote.Windows.Models;

namespace PCRemote.Windows.Services;

public sealed class PairingService(SecureStore store, AppSettings settings)
{
    private readonly object _sync = new();
    private string? _secret;
    private string? _interfaceId;
    private DateTimeOffset _expires;
    private readonly Queue<DateTimeOffset> _attempts = new();

    public PairingSession CreateSession(int port, string fingerprint, string? interfaceId = null)
    {
        lock (_sync)
        {
            var hosts = (interfaceId is null ? ServerHost.GetAdvertisedAddresses() : ServerHost.GetPairingAddresses(interfaceId)).Select(address => address.ToString()).ToArray();
            _secret = SecureStore.RandomSecret();
            _interfaceId = interfaceId;
            _expires = DateTimeOffset.UtcNow.AddMinutes(3);
            var payload = JsonSerializer.Serialize(new { v = 1, id = settings.ComputerId, name = settings.ComputerName, port, secret = _secret, cert = fingerprint, hosts });
            return new PairingSession(payload, _expires);
        }
    }

    public void CancelSession() { lock (_sync) { _secret = null; _interfaceId = null; _expires = default; } }

    public bool IsInterfaceAllowed(string interfaceId)
    {
        lock (_sync) { if (_secret is not null && DateTimeOffset.UtcNow <= _expires && _interfaceId == interfaceId) return true; }
        lock (settings) return settings.PairedInterfaceIds.Contains(interfaceId);
    }

    public async Task<PairResponse?> PairAsync(PairRequest request, string fingerprint)
    {
        if (string.IsNullOrEmpty(request.Secret) || request.Secret.Length > 128) return null;
        string expected;
        string? selectedInterface;
        lock (_sync)
        {
            var now = DateTimeOffset.UtcNow;
            while (_attempts.TryPeek(out var old) && now - old > TimeSpan.FromMinutes(1)) _attempts.Dequeue();
            if (_attempts.Count >= 8) return null;
            _attempts.Enqueue(now);
            if (_secret is null || now > _expires) return null;
            expected = _secret;
            if (!System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(System.Text.Encoding.UTF8.GetBytes(expected), System.Text.Encoding.UTF8.GetBytes(request.Secret))) return null;
            _secret = null;
            selectedInterface = _interfaceId;
            _interfaceId = null;
        }

        var token = SecureStore.RandomSecret(48);
        var device = new TrustedDevice { Id = Guid.NewGuid().ToString("N"), Name = string.IsNullOrWhiteSpace(request.DeviceName) ? "Android" : request.DeviceName.Trim()[..Math.Min(request.DeviceName.Trim().Length, 80)], TokenHash = SecureStore.HashToken(token), CreatedUtc = DateTimeOffset.UtcNow };
        bool addedInterface;
        lock (settings)
        {
            settings.Devices.Add(device);
            addedInterface = selectedInterface is not null && !settings.PairedInterfaceIds.Contains(selectedInterface);
            if (addedInterface) settings.PairedInterfaceIds.Add(selectedInterface!);
        }
        try { await store.SaveAsync(settings); }
        catch { lock (settings) { settings.Devices.Remove(device); if (addedInterface) settings.PairedInterfaceIds.Remove(selectedInterface!); } throw; }
        return new PairResponse(settings.ComputerId, settings.ComputerName, device.Id, token, fingerprint);
    }
}
