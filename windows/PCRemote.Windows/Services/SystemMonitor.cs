using System.Runtime.InteropServices;
using LibreHardwareMonitor.Hardware;

namespace PCRemote.Windows.Services;

public sealed record DeviceMetrics(string Id, string Name, double? Load, double? Temperature);
public sealed record MemoryMetrics(ulong Total, ulong Available, ulong Used, double Usage);
public sealed record SystemMetrics(DateTimeOffset SampledAt, DeviceMetrics Cpu, DeviceMetrics[] Gpus, MemoryMetrics? Memory, long UptimeSeconds);

// One cached sample shared by all authorized SSE subscribers; no idle polling.
public sealed class SystemMonitor : IDisposable
{
    private readonly object gate = new();
    private Computer? computer;
    private bool initialized;
    private SystemMetrics? cached;
    private ulong previousIdle, previousTotal;
    public SystemMetrics Sample()
    {
        lock (gate)
        {
            if (cached is not null && DateTimeOffset.UtcNow - cached.SampledAt < TimeSpan.FromSeconds(1)) return cached;
            if (!initialized)
            {
                initialized = true;
                computer = new Computer { IsCpuEnabled = true, IsGpuEnabled = true };
                try { computer.Open(); } catch { /* Unsupported sensors remain unavailable. */ }
            }
            var devices = new List<(HardwareType Type, DeviceMetrics Metrics)>();
            foreach (var hardware in computer!.Hardware)
            {
                double? load = null, temperature = null;
                try
                {
                    hardware.Update();
                    load = Read(hardware, SensorType.Load, hardware.HardwareType == HardwareType.Cpu ? "CPU Total" : "GPU Core", 0, 100);
                    if (load is null && hardware.HardwareType != HardwareType.Cpu)
                    {
                        // D3D-only adapters (including Intel): busiest engine on this adapter.
                        var engines = hardware.Sensors.Where(s => s.SensorType == SensorType.Load && s.Name.StartsWith("D3D") && s.Value.HasValue)
                            .Select(s => (double)s.Value!.Value).Where(v => double.IsFinite(v) && v >= 0 && v <= 100).ToArray();
                        if (engines.Length > 0) load = engines.Max();
                    }
                    temperature = Read(hardware, SensorType.Temperature, hardware.HardwareType == HardwareType.Cpu ? "CPU Package" : "GPU Core", -50, 150);
                    if (temperature is null && hardware.HardwareType == HardwareType.Cpu)
                    {
                        var cores = hardware.Sensors.Where(s => s.SensorType == SensorType.Temperature && s.Name.StartsWith("CPU Core") && s.Value.HasValue).Select(s => (double)s.Value!.Value).Where(v => double.IsFinite(v) && v >= -50 && v <= 150).ToArray();
                        if (cores.Length > 0) temperature = cores.Max();
                    }
                }
                catch { /* Failure of one adapter must not hide other adapters. */ }
                devices.Add((hardware.HardwareType, new(hardware.Identifier.ToString(), hardware.Name, load, temperature)));
            }
            var cpu = devices.FirstOrDefault(d => d.Type == HardwareType.Cpu).Metrics;
            var systemLoad = CpuLoad();
            cpu = cpu is null ? new("cpu", CpuName(), systemLoad, null) : cpu with { Load = cpu.Load ?? systemLoad };
            var memory = new MemoryStatus { Length = (uint)Marshal.SizeOf<MemoryStatus>() };
            MemoryMetrics? ram = GlobalMemoryStatusEx(ref memory) && memory.TotalPhysical > 0
                ? new(memory.TotalPhysical, memory.AvailablePhysical, memory.TotalPhysical - memory.AvailablePhysical, 100.0 * (memory.TotalPhysical - memory.AvailablePhysical) / memory.TotalPhysical) : null;
            return cached = new(DateTimeOffset.UtcNow, cpu, devices.Where(d => d.Type is HardwareType.GpuNvidia or HardwareType.GpuAmd or HardwareType.GpuIntel).Select(d => d.Metrics).ToArray(), ram, (long)(GetTickCount64() / 1000));
        }
    }
    private static string CpuName()
    {
        using var key = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
        return (key?.GetValue("ProcessorNameString") as string)?.Trim() ?? "Нет данных";
    }
    private double? CpuLoad()
    {
        if (!GetSystemTimes(out var idle, out var kernel, out var user)) return null;
        var total = kernel + user;
        double? result = previousTotal > 0 && total > previousTotal ? Math.Clamp(100.0 * (1 - (double)(idle - previousIdle) / (total - previousTotal)), 0, 100) : null;
        previousIdle = idle; previousTotal = total;
        return result;
    }
    private static double? Read(IHardware hardware, SensorType type, string name, double min, double max)
    {
        var value = hardware.Sensors.FirstOrDefault(s => s.SensorType == type && s.Name == name)?.Value;
        return value.HasValue && float.IsFinite(value.Value) && value >= min && value <= max ? value.Value : null;
    }
    public void Dispose() { lock (gate) { computer?.Close(); computer = null; } }
    [DllImport("kernel32.dll")] private static extern bool GetSystemTimes(out ulong idle, out ulong kernel, out ulong user);
    [DllImport("kernel32.dll")] private static extern ulong GetTickCount64();
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GlobalMemoryStatusEx(ref MemoryStatus status);
    [StructLayout(LayoutKind.Sequential)] private struct MemoryStatus
    {
        public uint Length, Load;
        public ulong TotalPhysical, AvailablePhysical, TotalPage, AvailablePage, TotalVirtual, AvailableVirtual, Extended;
    }
}
