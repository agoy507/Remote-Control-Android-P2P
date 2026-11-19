using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using MultideviceRemote.Input;
using MultideviceRemote.Model;

namespace MultideviceRemote.Views;

/// <summary>
/// View untuk sesi remote aktif:
///  - SwapChainPanel: surface video dari SIPSorcery via VideoFrameRenderer
///  - InputCapture: tangkap mouse/keyboard gesture → kirim ke DataChannel
///  - Toolbar: tombol Home/Back/Recents/Disconnect
///  - Keyboard shortcuts: H=Home, B=Back, R=Recents, Esc=Disconnect
/// </summary>
public sealed partial class RemoteView : UserControl
{
    public Microsoft.UI.Xaml.Controls.SwapChainPanel SwapChainPanel => VideoPanel;

    private InputCapture? _inputCapture;
    public event EventHandler? OnDisconnectRequested;

    public RemoteView()
    {
        InitializeComponent();
        KeyDown += OnKeyDown;
    }

    public void StartInputCapture(Func<RemoteControlPacket, Task> sendInput)
    {
        ConnectingOverlay.Visibility = Visibility.Collapsed;
        StatusText.Text = "🟢 Tersambung";
        _inputCapture = new InputCapture(VideoPanel, sendInput);
        _inputCapture.Start();
    }

    public void StopInputCapture()
    {
        _inputCapture?.Stop();
        _inputCapture = null;
        ConnectingOverlay.Visibility = Visibility.Visible;
        StatusText.Text = "🔴 Terputus";
    }

    public void UpdateStatus(string text) => StatusText.Text = text;

    // ── Button handlers ───────────────────────────────────────────────────────

    private void HomeButton_Click(object sender, RoutedEventArgs e) =>
        _inputCapture?.SendSystemAction(RemoteSystemAction.HOME);

    private void BackButton_Click(object sender, RoutedEventArgs e) =>
        _inputCapture?.SendSystemAction(RemoteSystemAction.BACK);

    private void RecentsButton_Click(object sender, RoutedEventArgs e) =>
        _inputCapture?.SendSystemAction(RemoteSystemAction.RECENTS);

    private void DisconnectButton_Click(object sender, RoutedEventArgs e) =>
        OnDisconnectRequested?.Invoke(this, EventArgs.Empty);

    // ── Keyboard shortcuts ────────────────────────────────────────────────────

    private void OnKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (_inputCapture is null) return;

        switch (e.Key)
        {
            case Windows.System.VirtualKey.H:
                _inputCapture.SendSystemAction(RemoteSystemAction.HOME);
                e.Handled = true;
                break;
            case Windows.System.VirtualKey.B:
                _inputCapture.SendSystemAction(RemoteSystemAction.BACK);
                e.Handled = true;
                break;
            case Windows.System.VirtualKey.R:
                _inputCapture.SendSystemAction(RemoteSystemAction.RECENTS);
                e.Handled = true;
                break;
            case Windows.System.VirtualKey.Escape:
                OnDisconnectRequested?.Invoke(this, EventArgs.Empty);
                e.Handled = true;
                break;
        }
    }
}
