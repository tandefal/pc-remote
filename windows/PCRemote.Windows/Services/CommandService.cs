using System.Runtime.InteropServices;
using PCRemote.Windows.Models;

namespace PCRemote.Windows.Services;

public sealed class CommandService(WindowsAppService apps)
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly Dictionary<string, (DateTimeOffset Time, CommandResult Result)> _completed = [];

    public Task<CommandResult> ExecuteAsync(string requestId, Func<CommandResult> action) => ExecuteAsync(requestId, () => Task.FromResult(action()));

    public async Task<CommandResult> ExecuteAsync(string requestId, Func<Task<CommandResult>> action, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(requestId) || requestId.Length > 256) return new(false, "Требуется корректный идентификатор запроса.");
        await _gate.WaitAsync(cancellationToken);
        try
        {
            var cutoff = DateTimeOffset.UtcNow.AddMinutes(-10);
            foreach (var key in _completed.Where(x => x.Value.Time < cutoff).Select(x => x.Key).ToList()) _completed.Remove(key);
            if (_completed.TryGetValue(requestId, out var old)) return old.Result;
            var result = await action(); _completed[requestId] = (DateTimeOffset.UtcNow, result); return result;
        }
        finally { _gate.Release(); }
    }

    public static CommandResult Lock() => SessionState.IsLocked() == true
        ? new(false, "Компьютер уже заблокирован.")
        : LockWorkStation() ? new(true, "Сеанс заблокирован.") : new(false, "Windows отклонила блокировку.");
    public static CommandResult Shutdown()
    {
        if (!EnableShutdownPrivilege()) return new(false, "Недостаточно прав для выключения.");
        return ExitWindowsEx(0x00000008, 0x00040000) ? new(true, "Выключение начато.") : new(false, "Windows отклонила выключение.");
    }
    public static CommandResult Reboot()
    {
        if (!EnableShutdownPrivilege()) return new(false, "Недостаточно прав для перезагрузки.");
        return ExitWindowsEx(0x00000002, 0x00040000) ? new(true, "Перезагрузка начата.") : new(false, "Windows отклонила перезагрузку.");
    }
    public CommandResult Exit(string id) => apps.Exit(id);
    public CommandResult Terminate(string id) => apps.Terminate(id);
    public CommandResult TerminateAll() => apps.TerminateAll();

    private static bool EnableShutdownPrivilege()
    {
        if (!OpenProcessToken(GetCurrentProcess(), 0x20 | 0x8, out var token)) return false;
        try { if (!LookupPrivilegeValue(null, "SeShutdownPrivilege", out var luid)) return false; var privileges = new TokenPrivileges { Count = 1, Luid = luid, Attributes = 2 }; return AdjustTokenPrivileges(token, false, ref privileges, 0, IntPtr.Zero, IntPtr.Zero) && Marshal.GetLastWin32Error() == 0; }
        finally { CloseHandle(token); }
    }
    [StructLayout(LayoutKind.Sequential)] private struct Luid { public uint Low; public int High; }
    [StructLayout(LayoutKind.Sequential)] private struct TokenPrivileges { public uint Count; public Luid Luid; public uint Attributes; }
    [DllImport("user32.dll")] private static extern bool LockWorkStation();
    [DllImport("user32.dll", SetLastError = true)] private static extern bool ExitWindowsEx(uint flags, uint reason);
    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool LookupPrivilegeValue(string? system, string name, out Luid luid);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool AdjustTokenPrivileges(IntPtr token, bool disableAll, ref TokenPrivileges state, uint length, IntPtr previous, IntPtr required);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
}
