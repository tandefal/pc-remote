using System.Windows;
using System.Windows.Media;
using Microsoft.Win32;
using MediaColor = System.Windows.Media.Color;
using MediaColorConverter = System.Windows.Media.ColorConverter;

namespace PCRemote.Windows.Services;

public static class ThemeService
{
    public static void ApplyCurrent(System.Windows.Application app)
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        var dark = key?.GetValue("AppsUseLightTheme") is int value && value == 0;
        app.Resources["PageBrush"] = new SolidColorBrush((MediaColor)MediaColorConverter.ConvertFromString(dark ? "#1C1B1F" : "#F7F5FA"));
        app.Resources["NavBrush"] = new SolidColorBrush((MediaColor)MediaColorConverter.ConvertFromString(dark ? "#25232A" : "#ECE6F0"));
        app.Resources["CardBrush"] = new SolidColorBrush((MediaColor)MediaColorConverter.ConvertFromString(dark ? "#2B2930" : "#FFFDFB"));
        app.Resources["TextBrush"] = new SolidColorBrush((MediaColor)MediaColorConverter.ConvertFromString(dark ? "#E6E1E5" : "#1D1B20"));
        app.Resources["SecondaryTextBrush"] = new SolidColorBrush((MediaColor)MediaColorConverter.ConvertFromString(dark ? "#CAC4D0" : "#625B71"));
    }
}
