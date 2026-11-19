using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using MultideviceRemote.Input;
using MultideviceRemote.Model;
using Microsoft.UI.Xaml.Controls.Primitives;

namespace MultideviceRemote.Views;

/// <summary>
/// View untuk sesi remote aktif: menampilkan SwapChainPanel video + meneruskan input.
/// </summary>
public sealed partial class RemoteView : UserControl
{
    public Microsoft.UI.Xaml.Controls.SwapChainPanel SwapChainPanel => VideoPanel;
    private InputCapture? _inputCapture;

    public RemoteView()
    {
        InitializeComponent();
    }

    public void StartInputCapture(Func<RemoteControlPacket, Task> sendInput)
    {
        _inputCapture = new InputCapture(VideoPanel, sendInput);
        _inputCapture.Start();
    }

    public void StopInputCapture()
    {
        _inputCapture?.Stop();
        _inputCapture = null;
    }
}
