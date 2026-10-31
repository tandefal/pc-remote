using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace PCRemote.Windows.Services;

public static class ThemeService
{
    // Keep native caption buttons, resizing and DPI behavior; request a dark caption on supported Windows.
    public static void ApplyWindow(Window window)
    {
        var enabled = 1;
        _ = DwmSetWindowAttribute(new WindowInteropHelper(window).Handle, 20, ref enabled, sizeof(int));
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}
