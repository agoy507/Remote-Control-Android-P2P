using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;
using MultideviceRemote.Model;

namespace MultideviceRemote.Host;

/// <summary>
/// Menyuntikkan input mouse dan keyboard ke sistem Windows via Win32 SendInput.
/// Digunakan oleh Mode Host saat menerima RemoteControlPacket dari pengendali.
/// </summary>
public sealed class WindowsInputInjector
{
    private readonly ILogger<WindowsInputInjector> _logger;
    private readonly SemaphoreSlim _gestureGate = new(1, 1);

    private int _heldLeft;
    private int _heldRight;
    private int _heldMiddle;

    public static int InputStructSize => Marshal.SizeOf<INPUT>();

    public WindowsInputInjector()
    {
        _logger = App.LoggerFactory.CreateLogger<WindowsInputInjector>();
    }

    public void Execute(RemoteControlPacket packet)
    {
        switch (packet)
        {
            case TouchTapPacket tap:
                _ = RunSerializedAsync(() => DispatchTapAsync(tap.XNorm, tap.YNorm, tap.IsLongPress));
                break;

            case TouchSwipePacket swipe:
                _ = RunSerializedAsync(() => DispatchSwipeAsync(swipe.StartX, swipe.StartY, swipe.EndX, swipe.EndY, swipe.DurationMs));
                break;

            case MouseMovePacket move:
                _ = RunSerializedAsync(() =>
                {
                    DispatchMouseMove(move.XNorm, move.YNorm);
                    return Task.CompletedTask;
                });
                break;

            case MouseButtonPacket btn:
                _ = RunSerializedAsync(() => DispatchMouseButtonAsync(btn.XNorm, btn.YNorm, btn.Button, btn.Action));
                break;

            case MouseScrollPacket scroll:
                _ = RunSerializedAsync(() =>
                {
                    DispatchScroll(scroll.XNorm, scroll.YNorm, scroll.DeltaX, scroll.DeltaY);
                    return Task.CompletedTask;
                });
                break;

            case KeyEventPacket key:
                _ = RunSerializedAsync(() =>
                {
                    DispatchKeyEvent(key.Key, key.Ctrl, key.Alt, key.Shift, key.Meta);
                    return Task.CompletedTask;
                });
                break;

            case TextInputPacket text:
                _ = RunSerializedAsync(() =>
                {
                    DispatchText(text.Text);
                    return Task.CompletedTask;
                });
                break;

            case SystemKeyPacket sys:
                _ = RunSerializedAsync(() =>
                {
                    DispatchSystemKey(sys.Action);
                    return Task.CompletedTask;
                });
                break;
        }
    }

    /// <summary>Lepaskan tombol mouse yang masih tertahan saat sesi terputus agar tidak menempel di Windows.</summary>
    public void ReleaseHeldButtons()
    {
        if (Interlocked.Exchange(ref _heldLeft, 0) != 0)
        {
            SendMouse(0, 0, 0, MOUSEEVENTF_LEFTUP);
        }
        if (Interlocked.Exchange(ref _heldRight, 0) != 0)
        {
            SendMouse(0, 0, 0, MOUSEEVENTF_RIGHTUP);
        }
        if (Interlocked.Exchange(ref _heldMiddle, 0) != 0)
        {
            SendMouse(0, 0, 0, MOUSEEVENTF_MIDDLEUP);
        }
    }

    private async Task RunSerializedAsync(Func<Task> action)
    {
        await _gestureGate.WaitAsync();
        try
        {
            await action();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Gagal menyuntikkan input Windows");
        }
        finally
        {
            _gestureGate.Release();
        }
    }

    private async Task DispatchTapAsync(float xNorm, float yNorm, bool isLongPress)
    {
        var (ax, ay) = ToAbsolute(xNorm, yNorm);
        uint downFlag = isLongPress ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_LEFTDOWN;
        uint upFlag = isLongPress ? MOUSEEVENTF_RIGHTUP : MOUSEEVENTF_LEFTUP;

        SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
        SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | downFlag);
        await Task.Delay(20);
        SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | upFlag);
    }

    private async Task DispatchSwipeAsync(float startX, float startY, float endX, float endY, long durationMs)
    {
        // Wheel scroll signature dari Windows controller (InputCapture.OnPointerWheelChanged)
        if (Math.Abs(endX - startX) < 0.005f && durationMs == 180)
        {
            var (wx, wy) = ToAbsolute(startX, startY);
            SendMouse(wx, wy, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
            int wheelDelta = endY > startY ? 120 : -120;
            SendMouse(wx, wy, unchecked((uint)wheelDelta), MOUSEEVENTF_WHEEL);
            return;
        }

        const int steps = 10;
        long totalMs = Math.Clamp(durationMs, 80L, 600L);
        int stepDelayMs = Math.Max(4, (int)(totalMs / steps));

        var (sx, sy) = ToAbsolute(startX, startY);
        var (ex, ey) = ToAbsolute(endX, endY);

        SendMouse(sx, sy, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
        Interlocked.Exchange(ref _heldLeft, 1);
        SendMouse(sx, sy, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_LEFTDOWN);

        for (int i = 1; i < steps; i++)
        {
            await Task.Delay(stepDelayMs);
            float t = (float)i / steps;
            var (ix, iy) = ToAbsolute(
                startX + (endX - startX) * t,
                startY + (endY - startY) * t);
            SendMouse(ix, iy, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
        }

        await Task.Delay(stepDelayMs);
        SendMouse(ex, ey, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
        Interlocked.Exchange(ref _heldLeft, 0);
        SendMouse(ex, ey, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_LEFTUP);
    }

    private void DispatchMouseMove(float xNorm, float yNorm)
    {
        var (ax, ay) = ToAbsolute(xNorm, yNorm);
        SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
    }

    private async Task DispatchMouseButtonAsync(float xNorm, float yNorm, string button, string action)
    {
        var (ax, ay) = ToAbsolute(xNorm, yNorm);
        string btnKey = button?.Trim().ToUpperInvariant() ?? "LEFT";
        var (downFlag, upFlag) = btnKey switch
        {
            "RIGHT" => (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
            "MIDDLE" => (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
            _ => (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP)
        };

        string actKey = action?.Trim().ToUpperInvariant() ?? "CLICK";
        switch (actKey)
        {
            case "DOWN":
                SetHeldFlag(btnKey, 1);
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | downFlag);
                break;

            case "UP":
                SetHeldFlag(btnKey, 0);
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | upFlag);
                break;

            case "DOUBLE_CLICK":
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | downFlag);
                await Task.Delay(15);
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | upFlag);
                await Task.Delay(45);
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | downFlag);
                await Task.Delay(15);
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | upFlag);
                break;

            default: // CLICK
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | downFlag);
                await Task.Delay(15);
                SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | upFlag);
                break;
        }
    }

    private void DispatchScroll(float xNorm, float yNorm, float deltaX, float deltaY)
    {
        var (ax, ay) = ToAbsolute(xNorm, yNorm);
        SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);

        if (Math.Abs(deltaY) > 0.001f)
        {
            int wheelDeltaY = (int)Math.Round(deltaY * 120f);
            if (wheelDeltaY != 0)
            {
                SendMouse(ax, ay, unchecked((uint)wheelDeltaY), MOUSEEVENTF_WHEEL);
            }
        }

        if (Math.Abs(deltaX) > 0.001f)
        {
            int wheelDeltaX = (int)Math.Round(deltaX * 120f);
            if (wheelDeltaX != 0)
            {
                SendMouse(ax, ay, unchecked((uint)wheelDeltaX), MOUSEEVENTF_HWHEEL);
            }
        }
    }

    private void DispatchKeyEvent(string key, bool ctrl, bool alt, bool shift, bool meta)
    {
        if (string.IsNullOrWhiteSpace(key)) return;

        if (!TryResolveVirtualKey(key, out ushort vk, out bool isExtended))
        {
            if (key.Length == 1 && !ctrl && !alt && !meta)
            {
                DispatchText(key);
            }
            return;
        }

        bool useCtrl = ctrl && vk != VK_CONTROL;
        bool useAlt = alt && vk != VK_MENU;
        bool useShift = shift && vk != VK_SHIFT;
        bool useMeta = meta && vk != VK_LWIN;

        var inputs = new List<INPUT>(10);
        if (useCtrl) inputs.Add(MakeVirtualKeyInput(VK_CONTROL, isKeyUp: false));
        if (useAlt) inputs.Add(MakeVirtualKeyInput(VK_MENU, isKeyUp: false));
        if (useShift) inputs.Add(MakeVirtualKeyInput(VK_SHIFT, isKeyUp: false));
        if (useMeta) inputs.Add(MakeVirtualKeyInput(VK_LWIN, isKeyUp: false, isExtended: true));

        inputs.Add(MakeVirtualKeyInput(vk, isKeyUp: false, isExtended: isExtended));
        inputs.Add(MakeVirtualKeyInput(vk, isKeyUp: true, isExtended: isExtended));

        if (useMeta) inputs.Add(MakeVirtualKeyInput(VK_LWIN, isKeyUp: true, isExtended: true));
        if (useShift) inputs.Add(MakeVirtualKeyInput(VK_SHIFT, isKeyUp: true));
        if (useAlt) inputs.Add(MakeVirtualKeyInput(VK_MENU, isKeyUp: true));
        if (useCtrl) inputs.Add(MakeVirtualKeyInput(VK_CONTROL, isKeyUp: true));

        SendInputsChecked(inputs.ToArray());
    }

    public static bool TryResolveVirtualKey(string key, out ushort vk, out bool isExtended)
    {
        vk = 0;
        isExtended = false;
        if (string.IsNullOrWhiteSpace(key)) return false;

        string trimmed = key.Trim();
        string norm = trimmed.ToUpperInvariant();
        switch (norm)
        {
            case "ENTER":
            case "RETURN":
                vk = VK_RETURN;
                return true;
            case "BACKSPACE":
            case "BACK":
                vk = VK_BACK;
                return true;
            case "TAB":
                vk = VK_TAB;
                return true;
            case "ESCAPE":
            case "ESC":
                vk = VK_ESCAPE;
                return true;
            case "SPACE":
                vk = VK_SPACE;
                return true;
            case "DELETE":
            case "DEL":
                vk = VK_DELETE;
                isExtended = true;
                return true;
            case "INSERT":
            case "INS":
                vk = VK_INSERT;
                isExtended = true;
                return true;
            case "HOME":
                vk = VK_HOME;
                isExtended = true;
                return true;
            case "END":
                vk = VK_END;
                isExtended = true;
                return true;
            case "PAGE_UP":
            case "PGUP":
                vk = VK_PRIOR;
                isExtended = true;
                return true;
            case "PAGE_DOWN":
            case "PGDN":
                vk = VK_NEXT;
                isExtended = true;
                return true;
            case "UP":
                vk = VK_UP;
                isExtended = true;
                return true;
            case "DOWN":
                vk = VK_DOWN;
                isExtended = true;
                return true;
            case "LEFT":
                vk = VK_LEFT;
                isExtended = true;
                return true;
            case "RIGHT":
                vk = VK_RIGHT;
                isExtended = true;
                return true;
            case "PRINT_SCREEN":
            case "PRTSC":
            case "PRINTSCREEN":
                vk = VK_SNAPSHOT;
                isExtended = true;
                return true;
            case "WIN":
            case "SUPER":
            case "META":
            case "LWIN":
                vk = VK_LWIN;
                isExtended = true;
                return true;
            case "CTRL":
            case "CONTROL":
            case "LCTRL":
                vk = VK_CONTROL;
                return true;
            case "ALT":
            case "MENU":
            case "LALT":
                vk = VK_MENU;
                return true;
            case "SHIFT":
            case "LSHIFT":
                vk = VK_SHIFT;
                return true;
        }

        if (norm.Length >= 2 && norm[0] == 'F' && int.TryParse(norm.AsSpan(1), out int fNum) && fNum is >= 1 and <= 12)
        {
            vk = (ushort)(0x70 + (fNum - 1));
            return true;
        }

        if (trimmed.Length == 1)
        {
            char c = trimmed[0];
            if (c is >= 'A' and <= 'Z' || c is >= 'a' and <= 'z')
            {
                vk = (ushort)char.ToUpperInvariant(c);
                return true;
            }
            if (c is >= '0' and <= '9')
            {
                vk = (ushort)c;
                return true;
            }

            short scanResult = VkKeyScanExW(c, GetKeyboardLayout(0));
            if (scanResult != -1)
            {
                byte low = (byte)(scanResult & 0xFF);
                if (low != 0xFF)
                {
                    vk = low;
                    return true;
                }
            }
        }

        return false;
    }

    private void DispatchText(string text)
    {
        if (string.IsNullOrEmpty(text)) return;

        bool capsLockOn = (GetKeyState(VK_CAPITAL) & 0x0001) != 0;
        IntPtr layout = GetKeyboardLayout(0);
        var inputs = new List<INPUT>(text.Length * 4);

        for (int i = 0; i < text.Length; i++)
        {
            char c = text[i];
            if (c == '\r')
            {
                AddVirtualKeyTap(inputs, VK_RETURN);
                if (i + 1 < text.Length && text[i + 1] == '\n') i++;
            }
            else if (c == '\n')
            {
                AddVirtualKeyTap(inputs, VK_RETURN);
            }
            else if (c == '\b')
            {
                AddVirtualKeyTap(inputs, VK_BACK);
            }
            else if (c == '\t')
            {
                AddVirtualKeyTap(inputs, VK_TAB);
            }
            else if (c >= 0x20 && c <= 0x7E)
            {
                short vkScan = VkKeyScanExW(c, layout);
                byte lowVk = (byte)(vkScan & 0xFF);
                if (vkScan != -1 && lowVk != 0 && lowVk != 0xFF)
                {
                    byte shiftState = (byte)((vkScan >> 8) & 0xFF);
                    bool needShift = ((shiftState & 1) != 0) ^ (char.IsLetter(c) && capsLockOn);
                    bool needCtrl = (shiftState & 2) != 0;
                    bool needAlt = (shiftState & 4) != 0;

                    if (needCtrl) inputs.Add(MakeVirtualKeyInput(VK_CONTROL, isKeyUp: false));
                    if (needAlt) inputs.Add(MakeVirtualKeyInput(VK_MENU, isKeyUp: false));
                    if (needShift) inputs.Add(MakeVirtualKeyInput(VK_SHIFT, isKeyUp: false));

                    inputs.Add(MakeVirtualKeyInput(lowVk, isKeyUp: false));
                    inputs.Add(MakeVirtualKeyInput(lowVk, isKeyUp: true));

                    if (needShift) inputs.Add(MakeVirtualKeyInput(VK_SHIFT, isKeyUp: true));
                    if (needAlt) inputs.Add(MakeVirtualKeyInput(VK_MENU, isKeyUp: true));
                    if (needCtrl) inputs.Add(MakeVirtualKeyInput(VK_CONTROL, isKeyUp: true));
                }
                else
                {
                    inputs.Add(MakeUnicodeKeyInput(c, isKeyUp: false));
                    inputs.Add(MakeUnicodeKeyInput(c, isKeyUp: true));
                }
            }
            else if (char.IsHighSurrogate(c) && i + 1 < text.Length && char.IsLowSurrogate(text[i + 1]))
            {
                char low = text[i + 1];
                inputs.Add(MakeUnicodeKeyInput(c, isKeyUp: false));
                inputs.Add(MakeUnicodeKeyInput(low, isKeyUp: false));
                inputs.Add(MakeUnicodeKeyInput(c, isKeyUp: true));
                inputs.Add(MakeUnicodeKeyInput(low, isKeyUp: true));
                i++;
            }
            else
            {
                inputs.Add(MakeUnicodeKeyInput(c, isKeyUp: false));
                inputs.Add(MakeUnicodeKeyInput(c, isKeyUp: true));
            }
        }

        if (inputs.Count > 0)
        {
            SendInputsChecked(inputs.ToArray());
        }
    }

    private void DispatchSystemKey(string action)
    {
        switch (action?.Trim().ToUpperInvariant())
        {
            case "HOME":
                SendKeySequence(
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: false, isExtended: true),
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: true, isExtended: true));
                break;

            case "BACK":
                SendKeySequence(
                    MakeVirtualKeyInput(VK_MENU, isKeyUp: false),
                    MakeVirtualKeyInput(VK_LEFT, isKeyUp: false, isExtended: true),
                    MakeVirtualKeyInput(VK_LEFT, isKeyUp: true, isExtended: true),
                    MakeVirtualKeyInput(VK_MENU, isKeyUp: true));
                break;

            case "RECENTS":
                SendKeySequence(
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: false, isExtended: true),
                    MakeVirtualKeyInput(VK_TAB, isKeyUp: false),
                    MakeVirtualKeyInput(VK_TAB, isKeyUp: true),
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: true, isExtended: true));
                break;

            case "NOTIFICATIONS":
                SendKeySequence(
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: false, isExtended: true),
                    MakeVirtualKeyInput(VK_N, isKeyUp: false),
                    MakeVirtualKeyInput(VK_N, isKeyUp: true),
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: true, isExtended: true));
                break;

            case "LOCK_SCREEN":
                LockWorkStation();
                break;
        }
    }

    private void SetHeldFlag(string btnKey, int value)
    {
        switch (btnKey)
        {
            case "RIGHT":
                Interlocked.Exchange(ref _heldRight, value);
                break;
            case "MIDDLE":
                Interlocked.Exchange(ref _heldMiddle, value);
                break;
            default:
                Interlocked.Exchange(ref _heldLeft, value);
                break;
        }
    }

    private static (int ax, int ay) ToAbsolute(float xNorm, float yNorm)
    {
        int ax = (int)Math.Round(Math.Clamp(xNorm, 0f, 1f) * 65535f);
        int ay = (int)Math.Round(Math.Clamp(yNorm, 0f, 1f) * 65535f);
        return (ax, ay);
    }

    private void SendMouse(int dx, int dy, uint mouseData, uint flags)
    {
        var input = new INPUT
        {
            type = INPUT_MOUSE,
            U = new InputUnion
            {
                mi = new MOUSEINPUT
                {
                    dx = dx,
                    dy = dy,
                    mouseData = mouseData,
                    dwFlags = flags,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };
        SendInputsChecked(new[] { input });
    }

    private void SendKeySequence(params INPUT[] inputs)
    {
        SendInputsChecked(inputs);
    }

    private void SendInputsChecked(INPUT[] inputs)
    {
        if (inputs.Length == 0) return;
        uint sent = SendInput((uint)inputs.Length, inputs, InputStructSize);
        if (sent == 0)
        {
            int err = Marshal.GetLastWin32Error();
            _logger.LogWarning("Win32 SendInput gagal (injected=0/{Total}, win32Err={Err}). Kemungkinan jendela aktif memiliki privilege lebih tinggi (UIPI).", inputs.Length, err);
        }
    }

    private static void AddVirtualKeyTap(List<INPUT> inputs, ushort vk)
    {
        inputs.Add(MakeVirtualKeyInput(vk, isKeyUp: false));
        inputs.Add(MakeVirtualKeyInput(vk, isKeyUp: true));
    }

    private static INPUT MakeVirtualKeyInput(ushort vk, bool isKeyUp, bool isExtended = false)
    {
        uint scan = MapVirtualKeyW(vk, MAPVK_VK_TO_VSC);
        uint flags = (isKeyUp ? KEYEVENTF_KEYUP : 0u) | (isExtended ? KEYEVENTF_EXTENDEDKEY : 0u);
        return new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = vk,
                    wScan = (ushort)(scan & 0xFF),
                    dwFlags = flags,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };
    }

    private static INPUT MakeUnicodeKeyInput(char ch, bool isKeyUp) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion
        {
            ki = new KEYBDINPUT
            {
                wVk = 0,
                wScan = ch,
                dwFlags = KEYEVENTF_UNICODE | (isKeyUp ? KEYEVENTF_KEYUP : 0u),
                time = 0,
                dwExtraInfo = IntPtr.Zero
            }
        }
    };

    // ── Win32 User32 P/Invoke ─────────────────────────────────────────────────

    private const uint INPUT_MOUSE = 0;
    private const uint INPUT_KEYBOARD = 1;

    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint MOUSEEVENTF_HWHEEL = 0x1000;
    private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

    private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;
    private const uint MAPVK_VK_TO_VSC = 0;

    private const ushort VK_BACK = 0x08;
    private const ushort VK_TAB = 0x09;
    private const ushort VK_RETURN = 0x0D;
    private const ushort VK_SHIFT = 0x10;
    private const ushort VK_CONTROL = 0x11;
    private const ushort VK_MENU = 0x12; // Alt
    private const ushort VK_CAPITAL = 0x14;
    private const ushort VK_ESCAPE = 0x1B;
    private const ushort VK_SPACE = 0x20;
    private const ushort VK_PRIOR = 0x21;
    private const ushort VK_NEXT = 0x22;
    private const ushort VK_END = 0x23;
    private const ushort VK_HOME = 0x24;
    private const ushort VK_LEFT = 0x25;
    private const ushort VK_UP = 0x26;
    private const ushort VK_RIGHT = 0x27;
    private const ushort VK_DOWN = 0x28;
    private const ushort VK_SNAPSHOT = 0x2C;
    private const ushort VK_INSERT = 0x2D;
    private const ushort VK_DELETE = 0x2E;
    private const ushort VK_N = 0x4E;
    private const ushort VK_LWIN = 0x5B;

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public HARDWAREINPUT hi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct HARDWAREINPUT
    {
        public uint uMsg;
        public ushort wParamL;
        public ushort wParamH;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern short VkKeyScanExW(char ch, IntPtr dwhkl);

    [DllImport("user32.dll")]
    private static extern IntPtr GetKeyboardLayout(uint idThread);

    [DllImport("user32.dll")]
    private static extern uint MapVirtualKeyW(uint uCode, uint uMapType);

    [DllImport("user32.dll")]
    private static extern short GetKeyState(int nVirtKey);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool LockWorkStation();
}
