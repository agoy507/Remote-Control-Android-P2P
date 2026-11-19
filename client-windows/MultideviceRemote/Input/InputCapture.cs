using Microsoft.UI.Xaml.Controls;
using MultideviceRemote.Model;

namespace MultideviceRemote.Input;

/// <summary>
/// Menangkap input mouse dan keyboard dari SwapChainPanel lalu
/// mengubahnya jadi RemoteControlPacket yang dikirim via DataChannel.
/// 
/// Phase 1: Tangkap mouse events dari SwapChainPanel (PointerPressed, PointerMoved, dll.)
/// Phase 3: Tambah SetWindowsHookEx global hook untuk capture keyboard saat window unfocused.
/// </summary>
public sealed class InputCapture
{
    private readonly SwapChainPanel _panel;
    private readonly Func<RemoteControlPacket, Task> _sendInput;
    private bool _running;

    public InputCapture(SwapChainPanel panel, Func<RemoteControlPacket, Task> sendInput)
    {
        _panel = panel;
        _sendInput = sendInput;
    }

    public void Start()
    {
        if (_running) return;
        _running = true;

        // Mouse events dari WinUI panel
        _panel.PointerPressed += OnPointerPressed;
        _panel.PointerReleased += OnPointerReleased;
        _panel.PointerMoved += OnPointerMoved;
        _panel.PointerWheelChanged += OnPointerWheelChanged;
    }

    public void Stop()
    {
        _running = false;
        _panel.PointerPressed -= OnPointerPressed;
        _panel.PointerReleased -= OnPointerReleased;
        _panel.PointerMoved -= OnPointerMoved;
        _panel.PointerWheelChanged -= OnPointerWheelChanged;
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    private void OnPointerPressed(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
    {
        var pt = e.GetCurrentPoint(_panel).Position;
        var (nx, ny) = Normalize(pt.X, pt.Y);
        var props = e.GetCurrentPoint(_panel).Properties;

        if (props.IsRightButtonPressed)
        {
            // Right-click → Back
            Send(RemoteControlPacket.Key(RemoteControlPacket.KeycodeBack));
        }
        else
        {
            Send(RemoteControlPacket.Touch(nx, ny, RemoteControlPacket.ActionDown));
        }
    }

    private void OnPointerReleased(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
    {
        var pt = e.GetCurrentPoint(_panel).Position;
        var (nx, ny) = Normalize(pt.X, pt.Y);
        Send(RemoteControlPacket.Touch(nx, ny, RemoteControlPacket.ActionUp));
    }

    private void OnPointerMoved(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
    {
        var pt = e.GetCurrentPoint(_panel).Position;
        if (!e.GetCurrentPoint(_panel).Properties.IsLeftButtonPressed) return;
        var (nx, ny) = Normalize(pt.X, pt.Y);
        Send(RemoteControlPacket.Touch(nx, ny, RemoteControlPacket.ActionMove));
    }

    private void OnPointerWheelChanged(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
    {
        var delta = e.GetCurrentPoint(_panel).Properties.MouseWheelDelta;
        // Konversi delta ke normalized scroll — Android biasanya expects nilai kecil
        var scrollDy = -(delta / 120f) * 0.1f;
        Send(RemoteControlPacket.Scroll(0f, scrollDy));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private (float nx, float ny) Normalize(double x, double y)
    {
        var w = _panel.ActualWidth;
        var h = _panel.ActualHeight;
        return ((float)(w > 0 ? x / w : 0), (float)(h > 0 ? y / h : 0));
    }

    private void Send(RemoteControlPacket packet) =>
        _ = _sendInput(packet);
}
