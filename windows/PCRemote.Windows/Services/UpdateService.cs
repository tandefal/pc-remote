using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;

namespace PCRemote.Windows.Services;

public sealed record ReleaseUpdate(string Version, string Notes, string Url, string Sha256, long Size);

public static class UpdateService
{
    private static readonly HttpClient Client = CreateClient();
    public static string CurrentVersion => typeof(UpdateService).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";
    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("PCRemote/" + CurrentVersion);
        return client;
    }

    public static async Task<ReleaseUpdate?> CheckAsync()
    {
        using var response = await Client.GetAsync("https://api.github.com/repos/tandefal/pc-remote/releases/latest");
        if (response.StatusCode == HttpStatusCode.NotFound) return null;
        response.EnsureSuccessStatusCode();
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var root = json.RootElement;
        var tag = root.GetProperty("tag_name").GetString()!;
        if (!Version.TryParse(tag.TrimStart('v'), out var version) || version <= Version.Parse(CurrentVersion)) return null;
        var name = $"PCRemote-Windows-{version.ToString(3)}-Setup.exe";
        foreach (var asset in root.GetProperty("assets").EnumerateArray())
        {
            if (asset.GetProperty("name").GetString() != name) continue;
            var digest = asset.GetProperty("digest").GetString() ?? "";
            var url = asset.GetProperty("browser_download_url").GetString() ?? "";
            var size = asset.GetProperty("size").GetInt64();
            if (!digest.StartsWith("sha256:") || digest.Length != 71 || size is <= 0 or > 314572800 ||
                !url.StartsWith("https://github.com/tandefal/pc-remote/releases/download/", StringComparison.Ordinal))
                throw new InvalidDataException("У релиза нет корректного файла обновления или контрольной суммы.");
            return new(tag, root.GetProperty("body").GetString() ?? "", url, digest[7..], size);
        }
        throw new InvalidDataException("Установщик Windows ещё не опубликован в релизе.");
    }

    public static async Task<string> DownloadAsync(ReleaseUpdate update)
    {
        var directory = Path.Combine(Path.GetTempPath(), "PCRemote", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "PCRemote-Setup.exe");
        try
        {
            using var response = await Client.GetAsync(update.Url, HttpCompletionOption.ResponseHeadersRead);
            response.EnsureSuccessStatusCode();
            await using (var input = await response.Content.ReadAsStreamAsync())
            await using (var output = File.Create(path))
            {
                var buffer = new byte[81920];
                long total = 0;
                int count;
                while ((count = await input.ReadAsync(buffer)) > 0)
                {
                    total += count;
                    if (total > update.Size) throw new InvalidDataException("Размер обновления не совпадает.");
                    await output.WriteAsync(buffer.AsMemory(0, count));
                }
                if (total != update.Size) throw new InvalidDataException("Обновление скачано не полностью.");
            }
            await using var file = File.OpenRead(path);
            var hash = Convert.ToHexString(await SHA256.HashDataAsync(file));
            if (!hash.Equals(update.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Контрольная сумма обновления не совпадает.");
            return path;
        }
        catch { File.Delete(path); throw; }
    }
}
