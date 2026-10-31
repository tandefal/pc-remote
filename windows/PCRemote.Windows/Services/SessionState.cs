using System.Diagnostics;
using System.Runtime.InteropServices;

namespace PCRemote.Windows.Services;

internal static class SessionState
{
    public static bool? IsLocked()
    {
        using var process = Process.GetCurrentProcess();
        if (!WTSQuerySessionInformation(IntPtr.Zero, process.SessionId, 25, out var buffer, out var length)) return null;
        try
        {
            // WTSINFOEX: DWORD Level, aligned WTSINFOEX_LEVEL1 union,
            // then SessionId, SessionState and SessionFlags (Windows 10+).
            if (length < 20 || Marshal.ReadInt32(buffer) != 1) return null;
            return Marshal.ReadInt32(buffer, 16) switch { 0 => true, 1 => false, _ => null };
        }
        finally { WTSFreeMemory(buffer); }
    }
    [DllImport("wtsapi32.dll", EntryPoint = "WTSQuerySessionInformationW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool WTSQuerySessionInformation(IntPtr server, int sessionId, int infoClass, out IntPtr buffer, out int bytes);
    [DllImport("wtsapi32.dll")] private static extern void WTSFreeMemory(IntPtr memory);
}
