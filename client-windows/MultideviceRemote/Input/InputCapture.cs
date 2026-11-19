using System.Diagnostics;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using MultideviceRemote.Model;

namespace MultideviceRemote.Input;

/// <summary>
/// Menangkap interaksi mouse dan keyboard dari SwapChainPanel,
/// mengonversinya menjadi RemoteControlPacket (Tap, Swipe, SystemKey).
/// 
/// Interaksi:
/// - Klik kiri singkat (&lt;500ms, jarak kecil) → Tap
/// - Klik kiri tahan (&gt;=500ms, jarak kecil) → Long Press Tap
/// - Drag kiri → Swipe (startX, startY, endX, endY)
/// - Klik kanan → Back
/// - Klik tengah → Home
/// - Wheel scroll → Swipe vertikal
/// </summary>
public sealed class InputCapture
{
    private readonly SwapChainPanel _panel;
    private readonly Func<RemoteControlPacket, Task> _sendInput;
    private bool _running;

    // State pelacakan gesture
    private double _downX;
    private double _downY;
    private long _downTimestamp;
    private bool _isPointerDown;

    public InputCapture(SwapChainPanel panel, Func<RemoteControlPacket, Task> sendInput)
    {
        _panel = panel;
        _sendInput = sendInput;
    }

    public void Start()
    {
        if (_running) return;
        _running = true;

        _panel.PointerPressed += OnPointerPressed;
        _panel.PointerReleased += OnPointerReleased;
        _panel.PointerMoved += OnPointerMoved;
        _panel.PointerWheelChanged += OnPointerWheelChanged;
    }

    public void Stop()
    {
        _running = false;
        _isPointerDown = false;
        _panel.PointerPressed -= OnPointerPressed;
        _panel.PointerReleased -= OnPointerReleased;
        _panel.PointerMoved -= OnPointerMoved;
        _panel.PointerWheelChanged -= OnPointerWheelChanged;
    }

    public void SendSystemAction(RemoteSystemAction action)
    {
        Send(RemoteControlPacket.SystemKey(action));
    }

    // ── Mouse / Pointer Handlers ─────────────────────────────────────────────

    private void OnPointerPressed(object sender, PointerRoutedEventArgs e)
    {
        var pt = e.GetCurrentPoint(_panel);
        var props = pt.Properties;

        if (props.IsRightButtonPressed)
        {
            // Klik kanan langsung trigger Back
            Send(RemoteControlPacket.SystemKey(RemoteSystemAction.BACK));
            e.Handled = true;
            return;
        }

        if (props.IsMiddleButtonPressed)
        {
            // Klik tengah trigger Home
            Send(RemoteControlPacket.SystemKey(RemoteSystemAction.HOME));
            e.Handled = true;
            return;
        }

        if (props.IsLeftButtonPressed)
        {
            _downX = pt.Position.X;
            _downY = pt.Position.Y;
            _downTimestamp = Stopwatch.GetTimestamp();
            _isPointerDown = true;
            _panel.CapturePointer(e.Pointer);
            e.Handled = true;
        }
    }

    private void OnPointerMoved(object sender, PointerRoutedEventArgs e)
    {
        // Trajectory bisa dipantau jika perlu preview kursor
    }

    private void OnPointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (!_isPointerDown) return;
        _isPointerDown = false;
        _panel.ReleasePointerCapture(e.Pointer);

        var pt = e.GetCurrentPoint(_panel).Position;
        var elapsedMs = (Stopwatch.GetTimestamp() - _downTimestamp) * 1000 / Stopwatch.Frequency;

        var (startX, startY) = Normalize(_downX, _downY);
        var (endX, endY) = Normalize(pt.X, pt.Y);

        double dx = pt.X - _downX;
        double dy = pt.Y - _downY;
        double distance = Math.Sqrt(dx * dx + dy * dy);

        // Jika pergeseran < 12 pixel, anggap sebagai Tap
        if (distance < 12)
        {
            bool isLong = elapsedMs >= 500;
            Send(RemoteControlPacket.Tap(startX, startY, isLongPress: isLong));
        }
        else
        {
            // Drag / Swipe
            long duration = Math.Clamp(elapsedMs, 100, 800);
            Send(RemoteControlPacket.Swipe(startX, startY, endX, endY, durationMs: duration));
        }

        e.Handled = true;
    }

    private void OnPointerWheelChanged(object sender, PointerRoutedEventArgs e)
    {
        var pt = e.GetCurrentPoint(_panel);
        var delta = pt.Properties.MouseWheelDelta;
        var (cx, cy) = Normalize(pt.Position.X, pt.Position.Y);

        // Scroll delta positif = scroll up (konten bergerak ke bawah → swipe ke bawah)
        // Scroll delta negatif = scroll down (konten bergerak ke atas → swipe ke atas)
        float swipeAmount = (delta > 0 ? 0.25f : -0.25f);
        float startY = Math.Clamp(cy, 0.1f, 0.9f);
        float endY = Math.Clamp(startY + swipeAmount, 0.05f, 0.95f);

        Send(RemoteControlPacket.Swipe(cx, startY, cx, endY, durationMs: 180));
        e.Handled = true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private (float nx, float ny) Normalize(double x, double y)
    {
        var w = _panel.ActualWidth;
        var h = _panel.ActualHeight;
        float nx = (float)Math.Clamp(w > 0 ? x / w : 0, 0.0, 1.0);
        float ny = (float)Math.Clamp(h > 0 ? y / h : 0, 0.0, 1.0);
        return (nx, ny);
    }

    private void Send(RemoteControlPacket packet) =>
        _ = _sendInput(packet);
}
