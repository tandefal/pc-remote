using System.Text.Json;
using PCRemote.Windows.Models;

namespace PCRemote.Windows.Services;

public sealed class PairingService(SecureStore store, AppSettings settings)
{
    private readonly object _sync = new();
    private string? _secret;
    private DateTimeOffset _expires;
    private readonly Queue<DateTimeOffset> _attempts = new();

    public PairingSession CreateSession(int port, string fingerprint)
    {
        lock (_sync)
        {
            _secret = SecureStore.RandomSecret();
            _expires = DateTimeOffset.UtcNow.AddMinutes(3);
            var hosts = ServerHost.GetAdvertisedAddresses().Select(address => address.ToString()).ToArray();
            var payload = JsonSerializer.Serialize(new { v = 1, id = settings.ComputerId, name = settings.ComputerName, port, secret = _secret, cert = fingerprint, hosts });
            return new PairingSession(payload, _expires);
        }
    }

    public void CancelSession() { lock (_sync) { _secret = null; _expires = default; } }

    public async Task<PairResponse?> PairAsync(PairRequest request, string fingerprint)
    {
        if (string.IsNullOrEmpty(request.Secret) || request.Secret.Length > 128) return null;
        string expected;
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
        }

        var token = SecureStore.RandomSecret(48);
        var device = new TrustedDevice { Id = Guid.NewGuid().ToString("N"), Name = string.IsNullOrWhiteSpace(request.DeviceName) ? "Android" : request.DeviceName.Trim()[..Math.Min(request.DeviceName.Trim().Length, 80)], TokenHash = SecureStore.HashToken(token), CreatedUtc = DateTimeOffset.UtcNow };
        lock (settings) settings.Devices.Add(device);
        try { await store.SaveAsync(settings); }
        catch { lock (settings) settings.Devices.Remove(device); throw; }
        return new PairResponse(settings.ComputerId, settings.ComputerName, device.Id, token, fingerprint);
    }
}
