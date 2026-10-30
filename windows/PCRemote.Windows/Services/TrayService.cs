using System.Drawing;
using System.Windows;
using Forms = System.Windows.Forms;

namespace PCRemote.Windows.Services;

public sealed class TrayService : IDisposable
{
    private readonly Forms.NotifyIcon _icon;
    public TrayService(Window window, Func<Task> exit)
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Открыть", null, (_, _) => Show(window));
        menu.Items.Add("Выход", null, async (_, _) => await exit());
        _icon = new Forms.NotifyIcon { Text = "PC Remote", Icon = SystemIcons.Application, Visible = true, ContextMenuStrip = menu };
        _icon.DoubleClick += (_, _) => Show(window);
    }
    private static void Show(Window window) => window.Dispatcher.Invoke(() => { window.Show(); window.WindowState = WindowState.Normal; window.Activate(); });
    public void Dispose() { _icon.Visible = false; _icon.Dispose(); }
}
