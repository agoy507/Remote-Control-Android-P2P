using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using MultideviceRemote.Model;
using MultideviceRemote.WebRtc;

namespace MultideviceRemote;

public sealed partial class MainWindow : Window
{
    private SessionCoordinator? _session;

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        AppWindow.Resize(new Windows.Graphics.SizeInt32(920, 720));
        var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico");
        if (File.Exists(iconPath))
        {
            AppWindow.SetIcon(iconPath);
        }

        Closed += MainWindow_Closed;
        HostView.HostRunningStateChanged += running => DispatcherQueue.TryEnqueue(() =>
        {
            HostTabButton.Content = running ? "🖥️ Mode Host • Aktif" : "🖥️ Mode Host";
        });
    }

    private void ControllerTabButton_Click(object sender, RoutedEventArgs e) => SwitchLobbyTab(isHostTab: false);

    private void HostTabButton_Click(object sender, RoutedEventArgs e) => SwitchLobbyTab(isHostTab: true);

    private static Style? TryGetStyle(string key) =>
        Application.Current.Resources.TryGetValue(key, out var val) ? val as Style : null;

    private void SwitchLobbyTab(bool isHostTab)
    {
        if (isHostTab)
        {
            ConnectView.Visibility = Visibility.Collapsed;
            HostView.Visibility = Visibility.Visible;
            if (!HostView.IsHostRunning)
            {
                HostView.ReloadSettings();
            }
            ControllerTabButton.Style = TryGetStyle("DefaultButtonStyle");
            HostTabButton.Style = TryGetStyle("AccentButtonStyle");
        }
        else
        {
            HostView.Visibility = Visibility.Collapsed;
            ConnectView.Visibility = Visibility.Visible;
            ConnectView.ReloadSettings();
            ControllerTabButton.Style = TryGetStyle("AccentButtonStyle");
            HostTabButton.Style = TryGetStyle("DefaultButtonStyle");
        }
    }

    private async void ConnectView_OnConnectRequested(object sender, ConnectRequestEventArgs e)
    {
        ConnectView.IsEnabled = false;

        // Pastikan Mode Host dimatikan agar deviceId yang sama tidak bentrok di signaling
        await HostView.StopHostIfRunningAsync();

        // Dispose sesi sebelumnya jika ada
        if (_session is not null)
        {
            await _session.DisposeAsync();
            _session = null;
        }

        var settings = SettingsManager.Load();

        _session = new SessionCoordinator(
            signalingUrl: e.SignalingUrl,
            myDeviceId: e.MyDeviceId,
            myAlias: settings.MyAlias.Length > 0 ? settings.MyAlias : Environment.MachineName,
            targetDeviceId: e.TargetDeviceId,
            targetPassword: e.TargetPassword,
            swapChainPanel: RemoteView.SwapChainPanel
        );

        _session.OnConnected += () => DispatcherQueue.TryEnqueue(() =>
        {
            MainTabsGrid.Visibility = Visibility.Collapsed;
            RemoteView.Visibility = Visibility.Visible;
            RemoteView.StartInputCapture(packet => _session?.SendInputAsync(packet) ?? Task.CompletedTask);
        });

        _session.OnDisconnected += reason => DispatcherQueue.TryEnqueue(() =>
        {
            RemoteView.StopInputCapture();
            RemoteView.Visibility = Visibility.Collapsed;
            MainTabsGrid.Visibility = Visibility.Visible;
            ConnectView.IsEnabled = true;
            ConnectView.ShowError(reason);
        });

        _session.OnError += err => DispatcherQueue.TryEnqueue(() =>
        {
            RemoteView.StopInputCapture();
            RemoteView.Visibility = Visibility.Collapsed;
            MainTabsGrid.Visibility = Visibility.Visible;
            ConnectView.IsEnabled = true;
            ConnectView.ShowError(err);
        });

        _session.OnStatusUpdate += status => DispatcherQueue.TryEnqueue(() =>
        {
            ConnectView.ShowStatus(status);
        });

        _session.OnFrameResolutionChanged += (w, h) => DispatcherQueue.TryEnqueue(() =>
        {
            RemoteView.UpdateFrameResolution(w, h);
        });

        try
        {
            await _session.StartAsync();
        }
        catch (Exception ex)
        {
            ConnectView.IsEnabled = true;
            ConnectView.ShowError($"Gagal memulai sesi: {ex.Message}");
        }
    }

    private async void RemoteView_OnDisconnectRequested(object sender, EventArgs e)
    {
        RemoteView.StopInputCapture();
        RemoteView.Visibility = Visibility.Collapsed;
        MainTabsGrid.Visibility = Visibility.Visible;
        ConnectView.IsEnabled = true;

        if (_session is not null)
        {
            await _session.DisposeAsync();
            _session = null;
        }
    }

    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        RemoteView.StopInputCapture();
        HostView.StopForWindowClose();

        var session = _session;
        _session = null;
        if (session is not null)
        {
            _ = Task.Run(async () => await session.DisposeAsync());
        }
    }
}

/// <summary>Event args dari ConnectView saat user tap Connect.</summary>
public record ConnectRequestEventArgs(
    string SignalingUrl,
    string MyDeviceId,
    string TargetDeviceId,
    string TargetPassword = ""
);
