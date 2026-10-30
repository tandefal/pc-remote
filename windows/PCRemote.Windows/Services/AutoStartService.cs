using Microsoft.Win32;

namespace PCRemote.Windows.Services;

public static class AutoStartService
{
    private const string KeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "PC Remote";
    public static bool IsEnabled() { using var key = Registry.CurrentUser.OpenSubKey(KeyPath); return key?.GetValue(Name) is string; }
    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(KeyPath);
        if (enabled) key.SetValue(Name, $"\"{Environment.ProcessPath}\" --background"); else key.DeleteValue(Name, false);
    }
}
