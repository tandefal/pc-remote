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
    private bool _loadFailed;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public SecureStore(string? path = null) => _path = path ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PCRemote", "settings.dat");

    public async Task<AppSettings> LoadAsync()
    {
        await _gate.WaitAsync();
        try
        {
            byte[] encrypted;
            try { encrypted = await File.ReadAllBytesAsync(_path); }
            catch (FileNotFoundException) { return new AppSettings(); }
            catch (DirectoryNotFoundException) { return new AppSettings(); }
            var json = ProtectedData.Unprotect(encrypted, Entropy, DataProtectionScope.CurrentUser);
            var settings = JsonSerializer.Deserialize<AppSettings>(json) ?? throw new JsonException("Пустое хранилище настроек.");
            if (settings.Devices is null || settings.PairedInterfaceIds is null || settings.PairedInterfaceIds.Any(string.IsNullOrWhiteSpace) || string.IsNullOrWhiteSpace(settings.ComputerId) || settings.Port is < 1 or > 65535 ||
                settings.Devices.Any(device => device is null || device.TokenHash is null || device.TokenHash.Length != 64 || !device.TokenHash.All(Uri.IsHexDigit)))
                throw new JsonException("Некорректные настройки.");
            return settings;
        }
        catch (Exception ex)
        {
            _loadFailed = true;
            throw new InvalidDataException(ex is CryptographicException ? "Не удалось расшифровать настройки. Исходный файл сохранён." :
                ex is JsonException ? "Настройки повреждены. Исходный файл сохранён." : "Не удалось прочитать настройки. Проверьте доступ к файлу.", ex);
        }
        finally { _gate.Release(); }
    }

    public async Task SaveAsync(AppSettings settings)
    {
        await _gate.WaitAsync();
        try
        {
            if (_loadFailed) throw new InvalidOperationException("Запись запрещена после ошибки чтения настроек.");
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
