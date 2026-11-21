using System.Diagnostics;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using MultideviceRemote.Model;
using Windows.System;

namespace MultideviceRemote.Input;

public enum TargetInputMode
{
    MobileTouch,
    DesktopMouse
}

/// <summary>
/// Menangkap interaksi mouse dan keyboard dari SwapChainPanel,
/// memetakannya dengan presisi sesuai rasio aspek Stretch.Uniform,
/// dan mengonversinya menjadi RemoteControlPacket (Desktop mouse / Mobile touch & keyboard).
/// </summary>
public sealed class InputCapture
{
    private readonly SwapChainPanel _panel;
    private readonly Func<RemoteControlPacket, Task> _sendInput;
    private bool _running;

    public TargetInputMode InputMode { get; set; } = TargetInputMode.MobileTouch;
    public event Action? OnCanvasInteracted;

    private int _frameWidth;
    private int _frameHeight;
    private long _lastMoveTicks;

    // State pelacakan gesture / klik
    private double _downX;
    private double _downY;
    private long _downTimestamp;
    private bool _isPointerDown;
    private string _heldButton = "LEFT";

    public InputCapture(SwapChainPanel panel, Func<RemoteControlPacket, Task> sendInput)
    {
        _panel = panel;
        _sendInput = sendInput;
    }

    public void SetFrameResolution(int width, int height)
    {
        _frameWidth = width;
        _frameHeight = height;
    }

    public void Start()
    {
        if (_running) return;
        _running = true;

        _panel.PointerPressed += OnPointerPressed;
        _panel.PointerReleased += OnPointerReleased;
        _panel.PointerMoved += OnPointerMoved;
        _panel.PointerWheelChanged += OnPointerWheelChanged;
        _panel.PointerCaptureLost += OnPointerCaptureLost;
    }

    public void Stop()
    {
        _running = false;
        _isPointerDown = false;
        _panel.PointerPressed -= OnPointerPressed;
        _panel.PointerReleased -= OnPointerReleased;
        _panel.PointerMoved -= OnPointerMoved;
        _panel.PointerWheelChanged -= OnPointerWheelChanged;
        _panel.PointerCaptureLost -= OnPointerCaptureLost;
    }

    public void SendSystemAction(RemoteSystemAction action) =>
        Send(RemoteControlPacket.SystemKey(action));

    public void SendTextInput(string text)
    {
        if (!string.IsNullOrEmpty(text))
        {
            Send(RemoteControlPacket.TextInput(text));
        }
    }

    public void SendKeyEvent(string key, bool ctrl = false, bool alt = false, bool shift = false, bool meta = false)
    {
        if (!string.IsNullOrWhiteSpace(key))
        {
            Send(RemoteControlPacket.KeyEvent(key, ctrl, alt, shift, meta));
        }
    }

    /// <summary>
    /// Memetakan koordinat pointer lokal panel ke [0..1] sesuai area render Stretch.Uniform
    /// sehingga pillarbox (HP portrait) dan letterbox (PC widescreen) tidak menggeser klik.
    /// </summary>
    public static (float nx, float ny) NormalizeUniform(
        double x,
        double y,
        double panelWidth,
        double panelHeight,
        int frameWidth,
        int frameHeight)
    {
        if (panelWidth <= 0 || panelHeight <= 0) return (0f, 0f);

        double fw = frameWidth > 0 ? frameWidth : panelWidth;
        double fh = frameHeight > 0 ? frameHeight : panelHeight;

        double scale = Math.Min(panelWidth / fw, panelHeight / fh);
        double renderedW = fw * scale;
        double renderedH = fh * scale;
        double left = (panelWidth - renderedW) / 2.0;
        double top = (panelHeight - renderedH) / 2.0;

        float nx = (float)Math.Clamp((x - left) / renderedW, 0.0, 1.0);
        float ny = (float)Math.Clamp((y - top) / renderedH, 0.0, 1.0);
        return (nx, ny);
    }

    /// <summary>
    /// Memetakan VirtualKey ke nama kunci kanonik protokol RemoteControlPacket ("key").
    /// Huruf/angka biasa diketik melalui CharacterReceived untuk menghindari input ganda.
    /// </summary>
    public static bool TryMapVirtualKey(VirtualKey key, bool hasModifier, out string canonicalKey)
    {
        canonicalKey = string.Empty;

        switch (key)
        {
            case VirtualKey.Enter: canonicalKey = "ENTER"; return true;
            case VirtualKey.Back: canonicalKey = "BACKSPACE"; return true;
            case VirtualKey.Tab: canonicalKey = "TAB"; return true;
            case VirtualKey.Escape: canonicalKey = "ESCAPE"; return true;
            case VirtualKey.Delete: canonicalKey = "DELETE"; return true;
            case VirtualKey.Insert: canonicalKey = "INSERT"; return true;
            case VirtualKey.Home: canonicalKey = "HOME"; return true;
            case VirtualKey.End: canonicalKey = "END"; return true;
            case VirtualKey.PageUp: canonicalKey = "PAGE_UP"; return true;
            case VirtualKey.PageDown: canonicalKey = "PAGE_DOWN"; return true;
            case VirtualKey.Up: canonicalKey = "UP"; return true;
            case VirtualKey.Down: canonicalKey = "DOWN"; return true;
            case VirtualKey.Left: canonicalKey = "LEFT"; return true;
            case VirtualKey.Right: canonicalKey = "RIGHT"; return true;
            case VirtualKey.Snapshot: canonicalKey = "PRINT_SCREEN"; return true;
        }

        if (key >= VirtualKey.F1 && key <= VirtualKey.F12)
        {
            canonicalKey = $"F{(int)key - (int)VirtualKey.F1 + 1}";
            return true;
        }

        if (hasModifier)
        {
            if (key == VirtualKey.Space)
            {
                canonicalKey = "SPACE";
                return true;
            }
            if (key >= VirtualKey.A && key <= VirtualKey.Z)
            {
                canonicalKey = ((char)('A' + ((int)key - (int)VirtualKey.A))).ToString();
                return true;
            }
            if (key >= VirtualKey.Number0 && key <= VirtualKey.Number9)
            {
                canonicalKey = ((char)('0' + ((int)key - (int)VirtualKey.Number0))).ToString();
                return true;
            }
            if (key >= VirtualKey.NumberPad0 && key <= VirtualKey.NumberPad9)
            {
                canonicalKey = ((char)('0' + ((int)key - (int)VirtualKey.NumberPad0))).ToString();
                return true;
            }
        }

        return false;
    }

    // ── Mouse / Pointer Handlers ─────────────────────────────────────────────

    private void OnPointerPressed(object sender, PointerRoutedEventArgs e)
    {
        OnCanvasInteracted?.Invoke();

        var pt = e.GetCurrentPoint(_panel);
        var props = pt.Properties;
        var (nx, ny) = Normalize(pt.Position.X, pt.Position.Y);

        if (InputMode == TargetInputMode.DesktopMouse)
        {
            string? button = props.IsRightButtonPressed
                ? "RIGHT"
                : props.IsMiddleButtonPressed
                    ? "MIDDLE"
                    : props.IsLeftButtonPressed
                        ? "LEFT"
                        : null;

            if (button == null) return;

            _heldButton = button;
            _isPointerDown = true;
            _panel.CapturePointer(e.Pointer);
            Send(RemoteControlPacket.MouseMove(nx, ny));
            Send(RemoteControlPacket.MouseButton(nx, ny, button, "DOWN"));
            e.Handled = true;
            return;
        }

        // MobileTouch mode
        if (props.IsRightButtonPressed)
        {
            Send(RemoteControlPacket.SystemKey(RemoteSystemAction.BACK));
            e.Handled = true;
            return;
        }

        if (props.IsMiddleButtonPressed)
        {
            Send(RemoteControlPacket.SystemKey(RemoteSystemAction.HOME));
            e.Handled = true;
            return;
        }

        if (props.IsLeftButtonPressed)
        {
            _downX = pt.Position.X;
            _downY = pt.Position.Y;
            _downTimestamp = Stopwatch.GetTimestamp();
            _heldButton = "LEFT";
            _isPointerDown = true;
            _panel.CapturePointer(e.Pointer);
            e.Handled = true;
        }
    }

    private void OnPointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (!_running || InputMode != TargetInputMode.DesktopMouse) return;

        long now = Stopwatch.GetTimestamp();
        double elapsedMs = (now - _lastMoveTicks) * 1000.0 / Stopwatch.Frequency;
        if (elapsedMs < (_isPointerDown ? 18.0 : 25.0)) return;
        _lastMoveTicks = now;

        var pt = e.GetCurrentPoint(_panel).Position;
        var (nx, ny) = Normalize(pt.X, pt.Y);
        Send(RemoteControlPacket.MouseMove(nx, ny));
    }

    private void OnPointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (!_isPointerDown) return;
        _isPointerDown = false;
        _panel.ReleasePointerCapture(e.Pointer);

        var pt = e.GetCurrentPoint(_panel).Position;
        var (endX, endY) = Normalize(pt.X, pt.Y);

        if (InputMode == TargetInputMode.DesktopMouse)
        {
            Send(RemoteControlPacket.MouseMove(endX, endY));
            Send(RemoteControlPacket.MouseButton(endX, endY, _heldButton, "UP"));
            e.Handled = true;
            return;
        }

        var elapsedMs = (Stopwatch.GetTimestamp() - _downTimestamp) * 1000 / Stopwatch.Frequency;
        var (startX, startY) = Normalize(_downX, _downY);

        double dx = pt.X - _downX;
        double dy = pt.Y - _downY;
        double distance = Math.Sqrt(dx * dx + dy * dy);

        if (distance < 12)
        {
            bool isLong = elapsedMs >= 500;
            Send(RemoteControlPacket.Tap(startX, startY, isLongPress: isLong));
        }
        else
        {
            long duration = Math.Clamp(elapsedMs, 100, 800);
            Send(RemoteControlPacket.Swipe(startX, startY, endX, endY, durationMs: duration));
        }

        e.Handled = true;
    }

    private void OnPointerCaptureLost(object sender, PointerRoutedEventArgs e)
    {
        if (!_isPointerDown) return;
        _isPointerDown = false;
        if (InputMode == TargetInputMode.DesktopMouse)
        {
            var pt = e.GetCurrentPoint(_panel).Position;
            var (nx, ny) = Normalize(pt.X, pt.Y);
            Send(RemoteControlPacket.MouseButton(nx, ny, _heldButton, "UP"));
        }
    }

    private void OnPointerWheelChanged(object sender, PointerRoutedEventArgs e)
    {
        var pt = e.GetCurrentPoint(_panel);
        var delta = pt.Properties.MouseWheelDelta;
        var (cx, cy) = Normalize(pt.Position.X, pt.Position.Y);

        if (InputMode == TargetInputMode.DesktopMouse)
        {
            Send(RemoteControlPacket.MouseScroll(cx, cy, 0f, delta / 120f));
            e.Handled = true;
            return;
        }

        float swipeAmount = (delta > 0 ? 0.25f : -0.25f);
        float startY = Math.Clamp(cy, 0.1f, 0.9f);
        float endY = Math.Clamp(startY + swipeAmount, 0.05f, 0.95f);

        Send(RemoteControlPacket.Swipe(cx, startY, cx, endY, durationMs: 180));
        e.Handled = true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private (float nx, float ny) Normalize(double x, double y) =>
        NormalizeUniform(x, y, _panel.ActualWidth, _panel.ActualHeight, _frameWidth, _frameHeight);

    private void Send(RemoteControlPacket packet) =>
        _ = _sendInput(packet);
}
