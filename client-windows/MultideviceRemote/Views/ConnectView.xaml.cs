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

    public void ReloadSettings() => LoadSettings();

    public void ShowError(string message)
    {
        StatusBar.IsOpen = false;
        ErrorBar.Message = message;
        ErrorBar.IsOpen = true;
    }

    public void ShowStatus(string message)
    {
        ErrorBar.IsOpen = false;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }

    private void LoadSettings()
    {
        var settings = SettingsManager.Load();
        MyDeviceIdBox.Text = settings.MyDeviceId ?? "";
        SignalingUrlBox.Text = settings.SignalingUrl ?? "wss://remote.agoy507.my.id/ws";
        ApiTokenBox.Password = settings.ServerApiToken ?? "";
        TargetDeviceIdBox.Text = settings.LastTargetDeviceId ?? "";
        TargetPasswordBox.Password = settings.RememberTargetPassword ? (settings.LastTargetPassword ?? "") : "";
        RememberPasswordCheckBox.IsChecked = settings.RememberTargetPassword;
    }

    private void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        ErrorBar.IsOpen = false;

        var myId = MyDeviceIdBox.Text.Trim();
        var targetId = TargetDeviceIdBox.Text.Trim();
        var targetPassword = TargetPasswordBox.Password.Trim();
        var rememberPassword = RememberPasswordCheckBox.IsChecked ?? true;
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
        if (string.IsNullOrEmpty(targetPassword))
        {
            ShowError("Kata sandi sesi target tidak boleh kosong.");
            return;
        }
        if (string.IsNullOrEmpty(url))
        {
            ShowError("URL signaling tidak boleh kosong.");
            return;
        }

        // Simpan settings (pertahankan konfigurasi Host)
        var settings = SettingsManager.Load();
        settings.MyDeviceId = myId;
        settings.SignalingUrl = url;
        settings.ServerApiToken = token;
        settings.LastTargetDeviceId = targetId;
        settings.LastTargetPassword = rememberPassword ? targetPassword : "";
        settings.RememberTargetPassword = rememberPassword;
        if (string.IsNullOrWhiteSpace(settings.MyAlias))
        {
            settings.MyAlias = Environment.MachineName;
        }
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
            TargetDeviceId: targetId,
            TargetPassword: targetPassword
        ));
    }
}
