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
        AppWindow.Resize(new Windows.Graphics.SizeInt32(900, 650));
        AppWindow.SetIcon("Assets/AppIcon.ico");
    }

    private async void ConnectView_OnConnectRequested(object sender, ConnectRequestEventArgs e)
    {
        ConnectView.IsEnabled = false;

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
            ConnectView.Visibility = Visibility.Collapsed;
            RemoteView.Visibility = Visibility.Visible;
            RemoteView.StartInputCapture(packet => _session?.SendInputAsync(packet) ?? Task.CompletedTask);
        });

        _session.OnDisconnected += reason => DispatcherQueue.TryEnqueue(() =>
        {
            RemoteView.StopInputCapture();
            RemoteView.Visibility = Visibility.Collapsed;
            ConnectView.Visibility = Visibility.Visible;
            ConnectView.IsEnabled = true;
            ConnectView.ShowError(reason);
        });

        _session.OnError += err => DispatcherQueue.TryEnqueue(() =>
        {
            RemoteView.StopInputCapture();
            RemoteView.Visibility = Visibility.Collapsed;
            ConnectView.Visibility = Visibility.Visible;
            ConnectView.IsEnabled = true;
            ConnectView.ShowError(err);
        });

        _session.OnStatusUpdate += status => DispatcherQueue.TryEnqueue(() =>
        {
            ConnectView.ShowStatus(status);
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
        ConnectView.Visibility = Visibility.Visible;
        ConnectView.IsEnabled = true;

        if (_session is not null)
        {
            await _session.DisposeAsync();
            _session = null;
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
