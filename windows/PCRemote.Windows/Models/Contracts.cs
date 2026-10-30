namespace PCRemote.Windows.Models;

public sealed class AppSettings
{
    public string ComputerId { get; set; } = Guid.NewGuid().ToString("N");
    public string ComputerName { get; set; } = Environment.MachineName;
    public int Port { get; set; } = 49821;
    public List<TrustedDevice> Devices { get; set; } = [];
    public string? CertificatePfx { get; set; }
    public string? CertificatePassword { get; set; }
}

public sealed class TrustedDevice
{
    public required string Id { get; set; }
    public required string Name { get; set; }
    public required string TokenHash { get; set; }
    public DateTimeOffset CreatedUtc { get; set; }
    public DateTimeOffset? LastSeenUtc { get; set; }
}

public sealed record PairRequest(string Secret, string DeviceName);
public sealed record PairResponse(string ComputerId, string ComputerName, string DeviceId, string Token, string CertificateSha256);
public sealed record ApiError(string Code, string Message, string TraceId);
public sealed record AppInfo(string Id, string Name, string? IconPngBase64, int WindowCount);
public sealed record CommandResult(bool Success, string Message, IReadOnlyList<string>? Errors = null);
public sealed record ServerState(bool ServerRunning, bool DiscoveryRunning);
public sealed record PairingSession(string Payload, DateTimeOffset ExpiresUtc);
