using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Principal;
using Microsoft.Win32;
using PCRemote.Windows.Models;

namespace PCRemote.Windows.Services;

public sealed class WindowsAppService
{
    private sealed record Target(int Pid, DateTime StartTime, string Path);
    private sealed record WindowTarget(IntPtr Handle, int Pid, DateTime StartTime, bool Visible);
    private sealed record Snapshot(List<Target> Processes, List<WindowTarget> Windows, DateTimeOffset CreatedUtc);
    private readonly object _sync = new();
    private Dictionary<string, Snapshot> _snapshot = [];
    private static readonly HashSet<string> Blocked = new(StringComparer.OrdinalIgnoreCase) { "PCRemote.exe", "explorer.exe", "dwm.exe", "winlogon.exe", "csrss.exe", "lsass.exe", "services.exe", "svchost.exe", "smss.exe", "sihost.exe", "taskhostw.exe", "fontdrvhost.exe", "systemsettings.exe" };

    public IReadOnlyList<AppInfo> GetApplications()
    {
        var windows = new List<(IntPtr Handle, int Pid, bool Visible)>();
        EnumWindows((hwnd, _) =>
        {
            GetWindowThreadProcessId(hwnd, out var pid); if (pid == Environment.ProcessId) return true;
            var visible = IsWindowVisible(hwnd) && GetWindow(hwnd, 4) == IntPtr.Zero && GetWindowTextLength(hwnd) > 0;
            windows.Add((hwnd, (int)pid, visible)); return true;
        }, IntPtr.Zero);

        // Message-only windows can also own notification icons, but EnumWindows omits them.
        var messageWindow = IntPtr.Zero;
        while ((messageWindow = FindWindowEx(new IntPtr(-3), messageWindow, null, null)) != IntPtr.Zero)
        {
            GetWindowThreadProcessId(messageWindow, out var pid);
            if (pid != Environment.ProcessId) windows.Add((messageWindow, (int)pid, false));
        }

        var (trayIds, trayPaths) = GetTrayIcons();
        var candidates = new List<(int Pid, DateTime StartTime, string Path, string Name)>();
        foreach (var processWindows in windows.GroupBy(x => x.Pid))
        {
            try
            {
                using var p = Process.GetProcessById(processWindows.Key);
                if (!IsAllowedProcess(p)) continue;
                var path = p.MainModule?.FileName; if (path is null || !IsSafeExecutable(path)) continue;
                if (!processWindows.Any(x => x.Visible) && !trayPaths.Contains(path)
                    && !processWindows.Any(x => trayIds.Any(id => HasTrayIcon(x.Handle, id)))) continue;
                var name = p.MainModule?.FileVersionInfo.FileDescription;
                candidates.Add((p.Id, p.StartTime, path, string.IsNullOrWhiteSpace(name) ? Path.GetFileNameWithoutExtension(path) : name));
            }
            catch { }
        }

        var next = new Dictionary<string, Snapshot>();
        var result = new List<AppInfo>();
        foreach (var group in candidates.GroupBy(x => x.Path, StringComparer.OrdinalIgnoreCase))
        {
            var targets = FindRelatedProcesses(group.Key);
            var targetByPid = targets.ToDictionary(x => x.Pid);
            var ownedWindows = windows
                .Where(x => targetByPid.ContainsKey(x.Pid))
                .Select(x => new WindowTarget(x.Handle, x.Pid, targetByPid[x.Pid].StartTime, x.Visible))
                .ToList();
            var id = Guid.NewGuid().ToString("N");
            lock (_sync)
            {
                var existing = _snapshot.FirstOrDefault(x =>
                    x.Value.Processes.OrderBy(p => p.Pid).SequenceEqual(targets.OrderBy(p => p.Pid)) &&
                    x.Value.Windows.OrderBy(w => w.Handle.ToInt64()).SequenceEqual(ownedWindows.OrderBy(w => w.Handle.ToInt64())));
                if (existing.Key is not null) id = existing.Key;
            }
            next[id] = new Snapshot(targets, ownedWindows, DateTimeOffset.UtcNow);
            result.Add(new AppInfo(id, group.First().Name, TryReadIcon(group.Key), ownedWindows.Count(x => x.Visible)));
        }
        lock (_sync)
        {
            foreach (var key in _snapshot.Where(x => DateTimeOffset.UtcNow - x.Value.CreatedUtc > TimeSpan.FromMinutes(2)).Select(x => x.Key).ToList()) _snapshot.Remove(key);
            foreach (var entry in next) _snapshot[entry.Key] = entry.Value;
        }
        return result.OrderBy(x => x.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
    }

    public CommandResult Terminate(string id) => TerminateAsync(id).GetAwaiter().GetResult();

    public async Task<CommandResult> TerminateAsync(string id, CancellationToken cancellationToken = default)
    {
        Snapshot? snapshot; lock (_sync) _snapshot.TryGetValue(id, out snapshot);
        if (snapshot is null || !IsCurrent(snapshot)) return new(false, "Список приложений устарел. Обновите его.");
        return await TerminateTargets(snapshot.Processes, cancellationToken);
    }

    public CommandResult Exit(string id) => ExitAsync(id).GetAwaiter().GetResult();

    public async Task<CommandResult> ExitAsync(string id, CancellationToken cancellationToken = default)
    {
        Snapshot? snapshot; lock (_sync) _snapshot.TryGetValue(id, out snapshot);
        if (snapshot is null || !IsCurrent(snapshot)) return new(false, "Список приложений устарел. Обновите его.");

        var sent = 0;
        var steam = snapshot.Processes.FirstOrDefault(x => IsSteam(x.Path));
        if (steam is not null)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!IsCurrent(steam)) return new(false, "Цель Steam изменилась. Обновите список.");
            try
            {
                var start = new ProcessStartInfo(steam.Path) { UseShellExecute = false };
                start.ArgumentList.Add("-shutdown");
                using var request = Process.Start(start);
                sent = request is null ? 0 : 1;
            }
            catch { return new(false, "Не удалось отправить Steam запрос на выход."); }
        }
        else
        foreach (var window in snapshot.Windows.DistinctBy(x => x.Handle))
        {
            if (!IsCurrent(window) || !IsWindow(window.Handle) || GetWindowTextLength(window.Handle) == 0) continue;
            if (PostMessage(window.Handle, 0x0010, IntPtr.Zero, IntPtr.Zero)) sent++;
        }
        if (sent == 0) return new(false, "Приложение не поддерживает обычный выход. Используйте «Завершить принудительно».");

        var deadline = DateTimeOffset.UtcNow.AddSeconds(5);
        while (DateTimeOffset.UtcNow < deadline && snapshot.Processes.Any(IsRunning)) await Task.Delay(100, cancellationToken);
        return snapshot.Processes.Any(IsRunning) || snapshot.Processes.Select(x => x.Path).Distinct().SelectMany(FindProcessesByExactPath).Any()
            ? new(false, "Запрос на выход отправлен, но приложение продолжает работать. Используйте «Завершить принудительно».")
            : new(true, "Приложение завершило работу.");
    }

    public CommandResult TerminateAll() => TerminateAllAsync().GetAwaiter().GetResult();

    public async Task<CommandResult> TerminateAllAsync(CancellationToken cancellationToken = default)
    {
        var currentIds = GetApplications().Select(x => x.Id).ToHashSet();
        List<Snapshot> snapshots; lock (_sync) snapshots = _snapshot.Where(x => currentIds.Contains(x.Key)).Select(x => x.Value).ToList();
        var targets = snapshots.SelectMany(x => x.Processes).DistinctBy(x => (x.Pid, x.StartTime)).ToList();
        return await TerminateTargets(targets, cancellationToken);
    }

    private static (HashSet<uint> Ids, HashSet<string> Paths) GetTrayIcons()
    {
        // Registry entries are only identifiers to query, never evidence of a live icon.
        // Small IDs cover common legacy icons when NotifyIconSettings is unavailable.
        var ids = Enumerable.Range(0, 17).Select(x => (uint)x).ToHashSet();
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            using var settings = Registry.CurrentUser.OpenSubKey(@"Control Panel\NotifyIconSettings");
            if (settings is not null) foreach (var name in settings.GetSubKeyNames())
            {
                using var entry = settings.OpenSubKey(name);
                if (entry?.GetValue("UID") is int id) ids.Add(unchecked((uint)id));
                if (!Guid.TryParse(entry?.GetValue("IconGuid") as string, out var guid)) continue;
                var icon = new NotifyIconIdentifier { Size = (uint)Marshal.SizeOf<NotifyIconIdentifier>(), Guid = guid };
                if (Shell_NotifyIconGetRect(ref icon, out _) != 0) continue;
                var path = entry?.GetValue("ExecutablePath") as string;
                if (string.IsNullOrWhiteSpace(path)) continue;
                if (path.StartsWith('{') && path.IndexOf('}') is var end && end > 0
                    && Guid.TryParse(path[..(end + 1)], out var folder))
                {
                    if (SHGetKnownFolderPath(ref folder, 0, IntPtr.Zero, out var pointer) != 0) continue;
                    try { path = Marshal.PtrToStringUni(pointer) + path[(end + 1)..]; }
                    finally { Marshal.FreeCoTaskMem(pointer); }
                }
                paths.Add(Environment.ExpandEnvironmentVariables(path));
            }
        }
        catch (System.Security.SecurityException) { }
        catch (UnauthorizedAccessException) { }
        catch (IOException) { }
        return (ids, paths);
    }

    private static bool HasTrayIcon(IntPtr window, uint id)
    {
        var icon = new NotifyIconIdentifier { Size = (uint)Marshal.SizeOf<NotifyIconIdentifier>(), Window = window, Id = id };
        return Shell_NotifyIconGetRect(ref icon, out _) == 0;
    }

    private static async Task<CommandResult> TerminateTargets(IEnumerable<Target> targets, CancellationToken cancellationToken)
    {
        var tracked = targets.ToList();
        var errors = new List<string>();
        foreach (var target in tracked)
        {
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                using var process = Process.GetProcessById(target.Pid);
                // Open and retain the handle before validating identity, so PID reuse cannot retarget Kill.
                _ = process.SafeHandle;
                if (!IsAllowedProcess(process) || process.StartTime != target.StartTime || !string.Equals(process.MainModule?.FileName, target.Path, StringComparison.OrdinalIgnoreCase) || !IsSafeExecutable(target.Path)) { errors.Add($"PID {target.Pid}: цель изменилась или недоступна"); continue; }
                process.Kill(false);

            }
            catch (ArgumentException) { }
            catch (Exception ex) { errors.Add($"PID {target.Pid}: {ex.Message}"); }
        }
        var deadline = Stopwatch.StartNew();
        while (deadline.Elapsed < TimeSpan.FromSeconds(3) && tracked.Any(IsRunning)) await Task.Delay(100, cancellationToken);
        await Task.Delay(1000, cancellationToken);
        var restarted = tracked.Select(x => x.Path).Distinct(StringComparer.OrdinalIgnoreCase).SelectMany(FindProcessesByExactPath).Any(x => !tracked.Contains(x));
        if (restarted) return new(false, "Обнаружен новый экземпляр приложения. Обновите список.", errors);
        if (tracked.Any(IsRunning)) return new(false, "Часть процессов продолжает работать.", errors);
        return new(errors.Count == 0, errors.Count == 0 ? "Все проверенные процессы завершены." : $"Завершение неполное, ошибок: {errors.Count}", errors);
    }

    private static List<Target> FindRelatedProcesses(string path)
    {
        var targets = FindProcessesByExactPath(path);
        if (!IsSteam(path)) return targets;
        var helpers = new[] { "cef.win7", "cef.win7x64" }.SelectMany(folder =>
            FindProcessesByExactPath(Path.Combine(Path.GetDirectoryName(path)!, "bin", "cef", folder, "steamwebhelper.exe"))).ToList();
        using var snapshot = new Microsoft.Win32.SafeHandles.SafeFileHandle(CreateToolhelp32Snapshot(2, 0), true);
        if (snapshot.IsInvalid) return targets;
        var parents = new Dictionary<int, int>();
        var entry = new ProcessEntry { Size = (uint)Marshal.SizeOf<ProcessEntry>() };
        if (Process32First(snapshot, ref entry))
            do { parents[(int)entry.Pid] = (int)entry.ParentPid; } while (Process32Next(snapshot, ref entry));
        bool added;
        do
        {
            added = false;
            foreach (var helper in helpers.Where(x => !targets.Contains(x)))
                if (parents.TryGetValue(helper.Pid, out var parent) && targets.Any(x => x.Pid == parent && x.StartTime <= helper.StartTime && IsCurrent(x)))
                { targets.Add(helper); added = true; }
        } while (added);
        return targets;
    }

    private static bool IsRunning(Target target)
    {
        try { using var process = Process.GetProcessById(target.Pid); return process.StartTime == target.StartTime && !process.HasExited; }
        catch (ArgumentException) { return false; }
        catch { return true; }
    }

    private static bool IsSteam(string path)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Valve\Steam");
            var installed = (key?.GetValue("SteamExe") as string)?.Replace('/', Path.DirectorySeparatorChar);
            return installed is not null && string.Equals(Path.GetFullPath(installed), path, StringComparison.OrdinalIgnoreCase)
                && string.Equals(Path.GetFileName(path), "steam.exe", StringComparison.OrdinalIgnoreCase)
                && FileVersionInfo.GetVersionInfo(path).CompanyName == "Valve Corporation";
        }
        catch { return false; }
    }

    private static bool IsSafeExecutable(string path)
    {
        var name = Path.GetFileName(path);
        if (Blocked.Contains(name) || string.Equals(path, Environment.ProcessPath, StringComparison.OrdinalIgnoreCase)) return false;
        var windows = Environment.GetFolderPath(Environment.SpecialFolder.Windows).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (path.StartsWith(windows, StringComparison.OrdinalIgnoreCase)) return false;
        try
        {
            using var process = Process.GetCurrentProcess();
            return File.Exists(path) && !string.Equals(name, Path.GetFileName(process.MainModule?.FileName), StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private static List<Target> FindProcessesByExactPath(string executablePath)
    {
        var session = Process.GetCurrentProcess().SessionId;
        var targets = new List<Target>();
        foreach (var process in Process.GetProcesses())
        {
            try
            {
                var path = process.MainModule?.FileName;
                if (process.SessionId == session && IsAllowedProcess(process) && string.Equals(path, executablePath, StringComparison.OrdinalIgnoreCase))
                    targets.Add(new Target(process.Id, process.StartTime, path!));
            }
            catch { }
            finally { process.Dispose(); }
        }
        return targets;
    }

    private static bool IsCurrent(Snapshot snapshot) => DateTimeOffset.UtcNow - snapshot.CreatedUtc <= TimeSpan.FromMinutes(2) && snapshot.Processes.Count > 0 && snapshot.Processes.All(IsCurrent);

    private static bool IsCurrent(Target target)
    {
        try
        {
            using var process = Process.GetProcessById(target.Pid);
            return process.StartTime == target.StartTime
                && IsAllowedProcess(process)
                && string.Equals(process.MainModule?.FileName, target.Path, StringComparison.OrdinalIgnoreCase)
                && IsSafeExecutable(target.Path);
        }
        catch { return false; }
    }

    private static bool IsCurrent(WindowTarget window)
    {
        try
        {
            GetWindowThreadProcessId(window.Handle, out var pid);
            using var process = Process.GetProcessById(window.Pid);
            return pid == window.Pid && process.StartTime == window.StartTime && IsAllowedProcess(process);
        }
        catch { return false; }
    }

    private static bool IsAllowedProcess(Process process)
    {
        try
        {
            if (process.Id == Environment.ProcessId || process.SessionId != Process.GetCurrentProcess().SessionId) return false;
            GetWindowThreadProcessId(GetShellWindow(), out var shellPid);
            if (process.Id == shellPid || !IsProcessCritical(process.Handle, out var critical) || critical) return false;
            if (!OpenProcessToken(process.Handle, 8, out var token)) return false;
            try
            {
                using var owner = new WindowsIdentity(token);
                using var current = WindowsIdentity.GetCurrent();
                return owner.User is not null && current.User is not null && owner.User.Equals(current.User);
            }
            finally { CloseHandle(token); }
        }
        catch { return false; }
    }

    private static string? TryReadIcon(string path)
    {
        try { using var icon = Icon.ExtractAssociatedIcon(path); if (icon is null) return null; using var bitmap = icon.ToBitmap(); using var stream = new MemoryStream(); bitmap.Save(stream, ImageFormat.Png); return Convert.ToBase64String(stream.ToArray()); }
        catch { return null; }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ProcessEntry
    {
        public uint Size, Usage, Pid; public UIntPtr Heap; public uint Module, Threads, ParentPid; public int Priority; public uint Flags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string Exe;
    }
    [DllImport("kernel32.dll")] private static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint pid);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, EntryPoint = "Process32FirstW")] private static extern bool Process32First(Microsoft.Win32.SafeHandles.SafeFileHandle snapshot, ref ProcessEntry entry);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, EntryPoint = "Process32NextW")] private static extern bool Process32Next(Microsoft.Win32.SafeHandles.SafeFileHandle snapshot, ref ProcessEntry entry);
    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);
    [StructLayout(LayoutKind.Sequential)] private struct NotifyIconIdentifier { public uint Size; public IntPtr Window; public uint Id; public Guid Guid; }
    [StructLayout(LayoutKind.Sequential)] private struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("shell32.dll")] private static extern int Shell_NotifyIconGetRect(ref NotifyIconIdentifier identifier, out Rect rectangle);
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)] private static extern int SHGetKnownFolderPath(ref Guid folder, uint flags, IntPtr token, out IntPtr path);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr FindWindowEx(IntPtr parent, IntPtr after, string? className, string? title);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool PostMessage(IntPtr hwnd, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] private static extern IntPtr GetWindow(IntPtr hwnd, uint command);
    [DllImport("user32.dll")] private static extern int GetWindowTextLength(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
    [DllImport("user32.dll")] private static extern IntPtr GetShellWindow();
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool IsProcessCritical(IntPtr process, out bool critical);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
}
