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

    private static async Task DispatchTapAsync(float xNorm, float yNorm, bool isLongPress)
    {
        var (ax, ay) = ToAbsolute(xNorm, yNorm);
        uint downFlag = isLongPress ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_LEFTDOWN;
        uint upFlag = isLongPress ? MOUSEEVENTF_RIGHTUP : MOUSEEVENTF_LEFTUP;

        SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
        SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | downFlag);
        await Task.Delay(20);
        SendMouse(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | upFlag);
    }

    private static async Task DispatchSwipeAsync(float startX, float startY, float endX, float endY, long durationMs)
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
        SendMouse(ex, ey, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_LEFTUP);
    }

    private static void DispatchText(string text)
    {
        if (string.IsNullOrEmpty(text)) return;

        var inputs = new List<INPUT>(text.Length * 2);
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
            else
            {
                inputs.Add(MakeUnicodeKeyInput(c, isKeyUp: false));
                inputs.Add(MakeUnicodeKeyInput(c, isKeyUp: true));
            }
        }

        if (inputs.Count > 0)
        {
            var arr = inputs.ToArray();
            SendInput((uint)arr.Length, arr, InputStructSize);
        }
    }

    private static void DispatchSystemKey(string action)
    {
        switch (action?.Trim().ToUpperInvariant())
        {
            case "HOME":
                SendKeySequence(
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: false),
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: true));
                break;

            case "BACK":
                SendKeySequence(
                    MakeVirtualKeyInput(VK_MENU, isKeyUp: false),
                    MakeVirtualKeyInput(VK_LEFT, isKeyUp: false),
                    MakeVirtualKeyInput(VK_LEFT, isKeyUp: true),
                    MakeVirtualKeyInput(VK_MENU, isKeyUp: true));
                break;

            case "RECENTS":
                SendKeySequence(
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: false),
                    MakeVirtualKeyInput(VK_TAB, isKeyUp: false),
                    MakeVirtualKeyInput(VK_TAB, isKeyUp: true),
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: true));
                break;

            case "NOTIFICATIONS":
                SendKeySequence(
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: false),
                    MakeVirtualKeyInput(VK_N, isKeyUp: false),
                    MakeVirtualKeyInput(VK_N, isKeyUp: true),
                    MakeVirtualKeyInput(VK_LWIN, isKeyUp: true));
                break;

            case "LOCK_SCREEN":
                LockWorkStation();
                break;
        }
    }

    private static (int ax, int ay) ToAbsolute(float xNorm, float yNorm)
    {
        int ax = (int)Math.Round(Math.Clamp(xNorm, 0f, 1f) * 65535f);
        int ay = (int)Math.Round(Math.Clamp(yNorm, 0f, 1f) * 65535f);
        return (ax, ay);
    }

    private static void SendMouse(int dx, int dy, uint mouseData, uint flags)
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
        SendInput(1, new[] { input }, InputStructSize);
    }

    private static void SendKeySequence(params INPUT[] inputs)
    {
        SendInput((uint)inputs.Length, inputs, InputStructSize);
    }

    private static void AddVirtualKeyTap(List<INPUT> inputs, ushort vk)
    {
        inputs.Add(MakeVirtualKeyInput(vk, isKeyUp: false));
        inputs.Add(MakeVirtualKeyInput(vk, isKeyUp: true));
    }

    private static INPUT MakeVirtualKeyInput(ushort vk, bool isKeyUp) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion
        {
            ki = new KEYBDINPUT
            {
                wVk = vk,
                wScan = 0,
                dwFlags = isKeyUp ? KEYEVENTF_KEYUP : 0u,
                time = 0,
                dwExtraInfo = IntPtr.Zero
            }
        }
    };

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
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;

    private const ushort VK_BACK = 0x08;
    private const ushort VK_TAB = 0x09;
    private const ushort VK_RETURN = 0x0D;
    private const ushort VK_MENU = 0x12; // Alt
    private const ushort VK_LEFT = 0x25;
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

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool LockWorkStation();
}
