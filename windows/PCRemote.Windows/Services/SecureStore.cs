using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.IO;
using PCRemote.Windows.Models;

namespace PCRemote.Windows.Services;

public sealed class SecureStore
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("PCRemote.Settings.v1");
    private readonly string _path;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public SecureStore(string? path = null) => _path = path ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PCRemote", "settings.dat");

    public async Task<AppSettings> LoadAsync()
    {
        if (!File.Exists(_path)) return new AppSettings();
        try
        {
            var encrypted = await File.ReadAllBytesAsync(_path);
            var json = ProtectedData.Unprotect(encrypted, Entropy, DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<AppSettings>(json) ?? new AppSettings();
        }
        catch { return new AppSettings(); }
    }

    public async Task SaveAsync(AppSettings settings)
    {
        await _gate.WaitAsync();
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            byte[] json;
            lock (settings) json = JsonSerializer.SerializeToUtf8Bytes(settings);
            var encrypted = ProtectedData.Protect(json, Entropy, DataProtectionScope.CurrentUser);
            var temporary = _path + ".tmp";
            await File.WriteAllBytesAsync(temporary, encrypted);
            File.Move(temporary, _path, true);
        }
        finally { _gate.Release(); }
    }

    public static string HashToken(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));
    public static string RandomSecret(int bytes = 32) => Convert.ToBase64String(RandomNumberGenerator.GetBytes(bytes)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
