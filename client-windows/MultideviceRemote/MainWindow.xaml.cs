using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using MultideviceRemote.Model;
using MultideviceRemote.Network;
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

        _session = new SessionCoordinator(
            signalingUrl: e.SignalingUrl,
            myDeviceId: e.MyDeviceId,
            targetDeviceId: e.TargetDeviceId,
            targetPassword: e.TargetPassword,
            swapChainPanel: RemoteView.SwapChainPanel
        );

        _session.OnConnected += () => DispatcherQueue.TryEnqueue(() =>
        {
            ConnectView.Visibility = Visibility.Collapsed;
            RemoteView.Visibility = Visibility.Visible;
        });

        _session.OnDisconnected += reason => DispatcherQueue.TryEnqueue(() =>
        {
            RemoteView.Visibility = Visibility.Collapsed;
            ConnectView.Visibility = Visibility.Visible;
            ConnectView.IsEnabled = true;
            ConnectView.ShowError(reason);
        });

        await _session.StartAsync();
    }
}

/// <summary>Event args dari ConnectView saat user tap Connect.</summary>
public record ConnectRequestEventArgs(
    string SignalingUrl,
    string MyDeviceId,
    string TargetDeviceId,
    string TargetPassword = ""
);
