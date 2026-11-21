using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using MultideviceRemote.Input;
using MultideviceRemote.Model;
using Windows.ApplicationModel.DataTransfer;
using Windows.System;
using Windows.UI.Core;

namespace MultideviceRemote.Views;

/// <summary>
/// View untuk sesi remote aktif dari Windows Controller:
///  - SwapChainPanel: surface video dari SIPSorcery via VideoFrameRenderer
///  - InputCapture: mouse & keyboard target-aware dengan rasio aspek presisi
///  - Live Keyboard: CharacterReceived + PreviewKeyDown + Clipboard Paste
///  - Auto mode: otomatis Mode HP (portrait) / Mode PC (landscape), dapat di-override manual
/// </summary>
public sealed partial class RemoteView : UserControl
{
    private InputCapture? _inputCapture;
    private bool _keyboardCaptureEnabled = true;
    private bool _userOverrodeMode;
    private TargetInputMode _currentMode = TargetInputMode.MobileTouch;
    private int _lastFrameWidth;
    private int _lastFrameHeight;

    public SwapChainPanel SwapChainPanel => VideoPanel;
    public event EventHandler? OnDisconnectRequested;

    public RemoteView()
    {
        InitializeComponent();
        PreviewKeyDown += OnPreviewKeyDown;
        CharacterReceived += OnCharacterReceived;
    }

    public void StartInputCapture(Func<RemoteControlPacket, Task> sendInput)
    {
        ConnectingOverlay.Visibility = Visibility.Collapsed;
        StatusText.Text = "🟢 Tersambung";

        _userOverrodeMode = false;
        _keyboardCaptureEnabled = true;

        _inputCapture = new InputCapture(VideoPanel, sendInput);
        _inputCapture.SetFrameResolution(_lastFrameWidth, _lastFrameHeight);
        _inputCapture.OnCanvasInteracted += () => DispatcherQueue.TryEnqueue(() => Focus(FocusState.Programmatic));

        if (_lastFrameWidth > 0 && _lastFrameHeight > 0)
        {
            ApplyInputMode(_lastFrameHeight > _lastFrameWidth ? TargetInputMode.MobileTouch : TargetInputMode.DesktopMouse);
        }
        else
        {
            ApplyInputMode(TargetInputMode.MobileTouch);
        }

        UpdateKeyboardButtonUi();
        _inputCapture.Start();
        Focus(FocusState.Programmatic);
    }

    public void StopInputCapture()
    {
        _inputCapture?.Stop();
        _inputCapture = null;
        _lastFrameWidth = 0;
        _lastFrameHeight = 0;
        _userOverrodeMode = false;
        ConnectingOverlay.Visibility = Visibility.Visible;
        StatusText.Text = "🔴 Terputus";
    }

    public void UpdateFrameResolution(int width, int height)
    {
        _lastFrameWidth = width;
        _lastFrameHeight = height;
        _inputCapture?.SetFrameResolution(width, height);

        if (!_userOverrodeMode && width > 0 && height > 0)
        {
            var autoMode = height > width ? TargetInputMode.MobileTouch : TargetInputMode.DesktopMouse;
            ApplyInputMode(autoMode);
        }
    }

    public void UpdateStatus(string text) => StatusText.Text = text;

    // ── Toolbar Button Handlers ───────────────────────────────────────────────

    private void ModeToggleButton_Click(object sender, RoutedEventArgs e)
    {
        _userOverrodeMode = true;
        var next = _currentMode == TargetInputMode.DesktopMouse
            ? TargetInputMode.MobileTouch
            : TargetInputMode.DesktopMouse;
        ApplyInputMode(next);
        Focus(FocusState.Programmatic);
    }

    private void KeyboardToggleButton_Click(object sender, RoutedEventArgs e)
    {
        _keyboardCaptureEnabled = !_keyboardCaptureEnabled;
        UpdateKeyboardButtonUi();
        Focus(FocusState.Programmatic);
    }

    private void PasteButton_Click(object sender, RoutedEventArgs e)
    {
        _ = PasteClipboardToTargetAsync();
        Focus(FocusState.Programmatic);
    }

    private void HomeButton_Click(object sender, RoutedEventArgs e)
    {
        _inputCapture?.SendSystemAction(RemoteSystemAction.HOME);
        Focus(FocusState.Programmatic);
    }

    private void BackButton_Click(object sender, RoutedEventArgs e)
    {
        _inputCapture?.SendSystemAction(RemoteSystemAction.BACK);
        Focus(FocusState.Programmatic);
    }

    private void RecentsButton_Click(object sender, RoutedEventArgs e)
    {
        _inputCapture?.SendSystemAction(RemoteSystemAction.RECENTS);
        Focus(FocusState.Programmatic);
    }

    private void DisconnectButton_Click(object sender, RoutedEventArgs e) =>
        OnDisconnectRequested?.Invoke(this, EventArgs.Empty);

    // ── Keyboard Capture Handlers ─────────────────────────────────────────────

    private void OnPreviewKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (_inputCapture is null || !_keyboardCaptureEnabled) return;

        var (ctrl, alt, shift, meta) = GetModifiers();
        if (ctrl && !alt && !shift && !meta &&
            e.Key == VirtualKey.V &&
            _inputCapture.InputMode == TargetInputMode.MobileTouch)
        {
            _ = PasteClipboardToTargetAsync();
            e.Handled = true;
            return;
        }

        if (InputCapture.TryMapVirtualKey(e.Key, ctrl || alt || meta, out string canonicalKey))
        {
            _inputCapture.SendKeyEvent(canonicalKey, ctrl, alt, shift, meta);
            e.Handled = true;
        }
    }

    private void OnCharacterReceived(UIElement sender, CharacterReceivedRoutedEventArgs e)
    {
        if (_inputCapture is null || !_keyboardCaptureEnabled) return;

        var (ctrl, alt, _, meta) = GetModifiers();
        if (ctrl || alt || meta) return;

        char ch = e.Character;
        if (ch >= ' ' && ch != '\x7f')
        {
            _inputCapture.SendTextInput(ch.ToString());
            e.Handled = true;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void ApplyInputMode(TargetInputMode mode)
    {
        _currentMode = mode;
        if (_inputCapture is not null)
        {
            _inputCapture.InputMode = mode;
        }
        ModeToggleButton.Content = mode == TargetInputMode.DesktopMouse ? "🖥️ Mode PC" : "📱 Mode HP";
    }

    private void UpdateKeyboardButtonUi()
    {
        KeyboardToggleButton.Content = _keyboardCaptureEnabled ? "⌨️ Ketik: ON" : "⌨️ Ketik: OFF";
        KeyboardToggleButton.Style = TryGetStyle(_keyboardCaptureEnabled ? "AccentButtonStyle" : "DefaultButtonStyle");
    }

    private static Style? TryGetStyle(string key) =>
        Application.Current.Resources.TryGetValue(key, out var val) ? val as Style : null;

    private static (bool ctrl, bool alt, bool shift, bool meta) GetModifiers()
    {
        static bool IsDown(VirtualKey vk) =>
            InputKeyboardSource.GetKeyStateForCurrentThread(vk).HasFlag(CoreVirtualKeyStates.Down);

        return (
            ctrl: IsDown(VirtualKey.Control),
            alt: IsDown(VirtualKey.Menu),
            shift: IsDown(VirtualKey.Shift),
            meta: IsDown(VirtualKey.LeftWindows) || IsDown(VirtualKey.RightWindows)
        );
    }

    private async Task PasteClipboardToTargetAsync()
    {
        if (_inputCapture is null) return;
        try
        {
            var package = Clipboard.GetContent();
            if (package.Contains(StandardDataFormats.Text))
            {
                string text = await package.GetTextAsync();
                if (!string.IsNullOrEmpty(text))
                {
                    _inputCapture.SendTextInput(text);
                }
            }
        }
        catch
        {
            // Clipboard sementara bisa terkunci oleh aplikasi lain
        }
    }
}
