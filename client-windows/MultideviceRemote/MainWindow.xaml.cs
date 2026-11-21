using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using MultideviceRemote.Model;
using MultideviceRemote.WebRtc;
using WinRT.Interop;

namespace MultideviceRemote;

public sealed partial class MainWindow : Window
{
    private SessionCoordinator? _session;
    private SystemTrayManager? _tray;
    private bool _startupHandled;
    private bool _userSwitchedToController;
    private bool _isExplicitExit;
    private bool _isRestoringFromTray;
    private bool _trayHintShown;

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

        InitializeSystemTray(iconPath);

        AppWindow.Changed += AppWindow_Changed;
        AppWindow.Closing += AppWindow_Closing;
        Closed += MainWindow_Closed;

        HostView.HostRunningStateChanged += running => DispatcherQueue.TryEnqueue(() =>
        {
            HostTabButton.Content = running ? "🖥️ Mode Host • Aktif" : "🖥️ Mode Host";
            UpdateTitleAndTrayState(running);
        });

        HostView.IncomingApprovalRequested += () => DispatcherQueue.TryEnqueue(RestoreFromTray);
    }

    private void InitializeSystemTray(string iconPath)
    {
        _tray = new SystemTrayManager(iconPath);
        _tray.UpdateState("Multidevice Remote", HostView.IsHostRunning, HostView.CurrentDeviceIdFormatted);

        _tray.OpenRequested += () => DispatcherQueue.TryEnqueue(RestoreFromTray);
        _tray.ToggleHostRequested += () => DispatcherQueue.TryEnqueue(async () =>
        {
            await HostView.ToggleHostFromTrayAsync();
        });
        _tray.CopyDeviceIdRequested += () => DispatcherQueue.TryEnqueue(() =>
        {
            HostView.CopyDeviceIdToClipboard();
            var id = HostView.CurrentDeviceIdFormatted;
            if (!string.IsNullOrWhiteSpace(id))
            {
                _tray?.ShowBalloon("Device ID Disalin", $"Device ID PC ({id}) sudah disalin ke clipboard.");
            }
        });
        _tray.ExitRequested += () => DispatcherQueue.TryEnqueue(() =>
        {
            _isExplicitExit = true;
            Close();
        });
    }

    private void UpdateTitleAndTrayState(bool hostRunning)
    {
        var idFormatted = HostView.CurrentDeviceIdFormatted;
        var title = hostRunning && !string.IsNullOrWhiteSpace(idFormatted)
            ? $"Multidevice Remote — Host Aktif ({idFormatted})"
            : "Multidevice Remote";
        Title = title;
        _tray?.UpdateState(title, hostRunning, idFormatted);
    }

    private void AppWindow_Changed(AppWindow sender, AppWindowChangedEventArgs args)
    {
        if (!args.DidPresenterChange || _isExplicitExit || _isRestoringFromTray || !sender.IsVisible)
            return;

        if (sender.Presenter is OverlappedPresenter presenter &&
            presenter.State == OverlappedPresenterState.Minimized &&
            SettingsManager.Load().MinimizeToTray)
        {
            HideToTray(showBalloonOnce: true);
        }
    }

    private void AppWindow_Closing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (_isExplicitExit) return;

        if (SettingsManager.Load().MinimizeToTray)
        {
            args.Cancel = true;
            HideToTray(showBalloonOnce: true);
        }
    }

    private void HideToTray(bool showBalloonOnce)
    {
        AppWindow.Hide();
        if (showBalloonOnce && !_trayHintShown)
        {
            _trayHintShown = true;
            var status = HostView.IsHostRunning
                ? "Mode Host tetap aktif di latar belakang. Klik ikon tray untuk membuka kembali."
                : "Aplikasi diminimize ke System Tray. Klik ikon tray untuk membuka kembali.";
            _tray?.ShowBalloon("Multidevice Remote di System Tray", status);
        }
    }

    private void RestoreFromTray()
    {
        _isRestoringFromTray = true;
        try
        {
            AppWindow.Show();
            if (AppWindow.Presenter is OverlappedPresenter presenter &&
                presenter.State == OverlappedPresenterState.Minimized)
            {
                presenter.Restore();
            }
            var hwnd = WindowNative.GetWindowHandle(this);
            if (hwnd != IntPtr.Zero)
            {
                ShowWindow(hwnd, SW_RESTORE);
                SetForegroundWindow(hwnd);
            }
            Activate();
        }
        finally
        {
            _isRestoringFromTray = false;
        }
    }

    private async void MainTabsGrid_Loaded(object sender, RoutedEventArgs e)
    {
        if (_startupHandled) return;
        _startupHandled = true;

        var settings = SettingsManager.Load();
        UpdateTitleAndTrayState(HostView.IsHostRunning);
        if (!settings.AutoStartHostOnLaunch)
        {
            return;
        }

        SwitchLobbyTab(isHostTab: true);
        var started = await HostView.StartHostIfNotRunningAsync(isAutoStart: true);
        if (started && settings.AutoMinimizeOnHostStart && !_userSwitchedToController)
        {
            if (settings.MinimizeToTray)
            {
                HideToTray(showBalloonOnce: true);
            }
            else if (AppWindow.Presenter is OverlappedPresenter presenter)
            {
                presenter.Minimize();
            }
        }
    }

    private void ControllerTabButton_Click(object sender, RoutedEventArgs e)
    {
        _userSwitchedToController = true;
        SwitchLobbyTab(isHostTab: false);
    }

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
        AppWindow.Changed -= AppWindow_Changed;
        AppWindow.Closing -= AppWindow_Closing;

        _tray?.Dispose();
        _tray = null;

        RemoteView.StopInputCapture();
        HostView.StopForWindowClose();

        var session = _session;
        _session = null;
        if (session is not null)
        {
            _ = Task.Run(async () => await session.DisposeAsync());
        }
    }

    private const int SW_RESTORE = 9;

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr hWnd);
}

/// <summary>Event args dari ConnectView saat user tap Connect.</summary>
public record ConnectRequestEventArgs(
    string SignalingUrl,
    string MyDeviceId,
    string TargetDeviceId,
    string TargetPassword = ""
);
