using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using MultideviceRemote.Model;

namespace MultideviceRemote.Views;

public sealed partial class ConnectView : UserControl
{
    public event EventHandler<ConnectRequestEventArgs>? OnConnectRequested;

    public ConnectView()
    {
        InitializeComponent();
        // Load settings tersimpan
        LoadSettings();
    }

    public void ShowError(string message)
    {
        ErrorBar.Message = message;
        ErrorBar.IsOpen = true;
    }

    private void LoadSettings()
    {
        var settings = SettingsManager.Load();
        MyDeviceIdBox.Text = settings.MyDeviceId;
        SignalingUrlBox.Text = settings.SignalingUrl;
        ApiTokenBox.Password = settings.ServerApiToken;
        TargetDeviceIdBox.Text = settings.LastTargetDeviceId;
    }

    private void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        ErrorBar.IsOpen = false;

        var myId = MyDeviceIdBox.Text.Trim();
        var targetId = TargetDeviceIdBox.Text.Trim();
        var url = SignalingUrlBox.Text.Trim();
        var token = ApiTokenBox.Password.Trim();

        if (string.IsNullOrEmpty(myId))
        {
            ShowError("Device ID saya tidak boleh kosong.");
            return;
        }
        if (string.IsNullOrEmpty(targetId))
        {
            ShowError("Device ID target tidak boleh kosong.");
            return;
        }
        if (string.IsNullOrEmpty(url))
        {
            ShowError("URL signaling tidak boleh kosong.");
            return;
        }

        // Simpan settings
        var settings = new AppSettings
        {
            MyDeviceId = myId,
            SignalingUrl = url,
            ServerApiToken = token,
            LastTargetDeviceId = targetId,
            MyAlias = Environment.MachineName
        };
        SettingsManager.Save(settings);

        // Bangun URL dengan token (sama seperti SignalingClient)
        var signalingUrlWithToken = url;
        if (!string.IsNullOrEmpty(token) && !url.Contains("token="))
        {
            var sep = url.Contains('?') ? "&" : "?";
            signalingUrlWithToken = $"{url}{sep}token={Uri.EscapeDataString(token)}";
        }

        OnConnectRequested?.Invoke(this, new ConnectRequestEventArgs(
            SignalingUrl: signalingUrlWithToken,
            MyDeviceId: myId,
            TargetDeviceId: targetId
        ));
    }
}
