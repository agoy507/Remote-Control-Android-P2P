using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;

namespace MultideviceRemote;

/// <summary>
/// Mengelola ikon System Tray (Notification Area) native Windows menggunakan Shell_NotifyIconW
/// dengan jendela pesan Win32 tersembunyi (tanpa mengganggu message loop WinUI 3).
/// </summary>
public sealed class SystemTrayManager : IDisposable
{
    private const uint WM_NULL = 0x0000;
    private const uint WM_APP = 0x8000;
    private const uint WM_TRAYICON = WM_APP + 1;
    private const uint WM_LBUTTONUP = 0x0202;
    private const uint WM_LBUTTONDBLCLK = 0x0203;
    private const uint WM_RBUTTONUP = 0x0205;
    private const uint WM_CONTEXTMENU = 0x007B;

    private const uint NIM_ADD = 0x00000000;
    private const uint NIM_MODIFY = 0x00000001;
    private const uint NIM_DELETE = 0x00000002;

    private const uint NIF_MESSAGE = 0x00000001;
    private const uint NIF_ICON = 0x00000002;
    private const uint NIF_TIP = 0x00000004;
    private const uint NIF_INFO = 0x00000010;

    private const uint NIIF_INFO = 0x00000001;
    private const uint IMAGE_ICON = 1;
    private const uint LR_LOADFROMFILE = 0x00000010;
    private const uint LR_DEFAULTSIZE = 0x00000040;
    private const int IDI_APPLICATION = 32512;

    private const uint MF_STRING = 0x00000000;
    private const uint MF_SEPARATOR = 0x00000800;
    private const uint TPM_RIGHTBUTTON = 0x0002;
    private const uint TPM_NONOTIFY = 0x0080;
    private const uint TPM_RETURNCMD = 0x0100;

    private const uint CMD_OPEN = 1001;
    private const uint CMD_TOGGLE_HOST = 1002;
    private const uint CMD_COPY_ID = 1003;
    private const uint CMD_EXIT = 1004;

    private readonly ILogger<SystemTrayManager> _logger;
    private readonly string _className;
    private readonly WndProcDelegate _wndProcDelegate;
    private readonly uint _taskbarCreatedMsg;

    private IntPtr _hwnd;
    private IntPtr _hIcon;
    private bool _ownsIcon;
    private bool _iconAdded;
    private bool _disposed;

    private string _tooltip = "Multidevice Remote";
    private bool _isHostRunning;
    private string _deviceIdFormatted = "";

    public event Action? OpenRequested;
    public event Action? ToggleHostRequested;
    public event Action? CopyDeviceIdRequested;
    public event Action? ExitRequested;

    public SystemTrayManager(string? iconPath = null)
    {
        _logger = App.LoggerFactory.CreateLogger<SystemTrayManager>();
        _className = $"MultideviceRemoteTrayWnd_{Guid.NewGuid():N}";
        _wndProcDelegate = WndProc;
        _taskbarCreatedMsg = RegisterWindowMessageW("TaskbarCreated");

        InitializeWindowAndIcon(iconPath);
    }

    public void UpdateState(string tooltip, bool isHostRunning, string deviceIdFormatted)
    {
        if (_disposed) return;

        _tooltip = Truncate(string.IsNullOrWhiteSpace(tooltip) ? "Multidevice Remote" : tooltip, 127);
        _isHostRunning = isHostRunning;
        _deviceIdFormatted = deviceIdFormatted ?? "";

        if (_hwnd == IntPtr.Zero || !_iconAdded) return;

        var nid = CreateBaseNotifyIconData();
        nid.uFlags = NIF_TIP;
        nid.szTip = _tooltip;
        Shell_NotifyIconW(NIM_MODIFY, ref nid);
    }

    public void ShowBalloon(string title, string message)
    {
        if (_disposed || _hwnd == IntPtr.Zero || !_iconAdded) return;

        var nid = CreateBaseNotifyIconData();
        nid.uFlags = NIF_INFO | NIF_TIP;
        nid.szTip = _tooltip;
        nid.szInfoTitle = Truncate(title, 63);
        nid.szInfo = Truncate(message, 255);
        nid.dwInfoFlags = NIIF_INFO;
        Shell_NotifyIconW(NIM_MODIFY, ref nid);
    }

    private void InitializeWindowAndIcon(string? iconPath)
    {
        IntPtr hInstance = GetModuleHandleW(null);

        var wc = new WNDCLASSEXW
        {
            cbSize = (uint)Marshal.SizeOf<WNDCLASSEXW>(),
            lpfnWndProc = Marshal.GetFunctionPointerForDelegate(_wndProcDelegate),
            hInstance = hInstance,
            lpszClassName = _className
        };

        ushort atom = RegisterClassExW(ref wc);
        if (atom == 0)
        {
            _logger.LogWarning("RegisterClassExW gagal untuk SystemTrayManager (err={Err})", Marshal.GetLastWin32Error());
            return;
        }

        // Gunakan hidden top-level window (bukan HWND_MESSAGE) agar broadcast TaskbarCreated diterima
        _hwnd = CreateWindowExW(
            dwExStyle: 0,
            lpClassName: _className,
            lpWindowName: "MultideviceRemoteTray",
            dwStyle: 0,
            x: 0, y: 0, nWidth: 0, nHeight: 0,
            hWndParent: IntPtr.Zero,
            hMenu: IntPtr.Zero,
            hInstance: hInstance,
            lpParam: IntPtr.Zero);

        if (_hwnd == IntPtr.Zero)
        {
            _logger.LogWarning("CreateWindowExW gagal untuk SystemTrayManager (err={Err})", Marshal.GetLastWin32Error());
            UnregisterClassW(_className, hInstance);
            return;
        }

        if (!string.IsNullOrWhiteSpace(iconPath) && File.Exists(iconPath))
        {
            _hIcon = LoadImageW(IntPtr.Zero, iconPath, IMAGE_ICON, 16, 16, LR_LOADFROMFILE | LR_DEFAULTSIZE);
            _ownsIcon = _hIcon != IntPtr.Zero;
        }

        if (_hIcon == IntPtr.Zero)
        {
            _hIcon = LoadIconW(IntPtr.Zero, (IntPtr)IDI_APPLICATION);
            _ownsIcon = false;
        }

        AddTrayIcon();
    }

    private void AddTrayIcon()
    {
        if (_hwnd == IntPtr.Zero) return;

        var nid = CreateBaseNotifyIconData();
        nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        nid.uCallbackMessage = WM_TRAYICON;
        nid.hIcon = _hIcon;
        nid.szTip = _tooltip;

        _iconAdded = Shell_NotifyIconW(NIM_ADD, ref nid);
        if (!_iconAdded)
        {
            _logger.LogWarning("Shell_NotifyIconW(NIM_ADD) gagal.");
        }
    }

    private NOTIFYICONDATAW CreateBaseNotifyIconData() => new()
    {
        cbSize = (uint)Marshal.SizeOf<NOTIFYICONDATAW>(),
        hWnd = _hwnd,
        uID = 1
    };

    private IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == WM_TRAYICON)
        {
            uint mouseMsg = (uint)(lParam.ToInt64() & 0xFFFF);
            if (mouseMsg == WM_LBUTTONUP || mouseMsg == WM_LBUTTONDBLCLK)
            {
                OpenRequested?.Invoke();
            }
            else if (mouseMsg == WM_RBUTTONUP || mouseMsg == WM_CONTEXTMENU)
            {
                ShowContextMenu();
            }
            return IntPtr.Zero;
        }

        if (_taskbarCreatedMsg != 0 && msg == _taskbarCreatedMsg)
        {
            AddTrayIcon();
            return IntPtr.Zero;
        }

        return DefWindowProcW(hWnd, msg, wParam, lParam);
    }

    private void ShowContextMenu()
    {
        if (_hwnd == IntPtr.Zero) return;

        IntPtr hMenu = CreatePopupMenu();
        if (hMenu == IntPtr.Zero) return;

        try
        {
            AppendMenuW(hMenu, MF_STRING, (UIntPtr)CMD_OPEN, "Buka Multidevice Remote");
            SetMenuDefaultItem(hMenu, CMD_OPEN, 0);
            AppendMenuW(hMenu, MF_SEPARATOR, UIntPtr.Zero, null);

            string hostMenuText = _isHostRunning ? "Hentikan Mode Host" : "Aktifkan Mode Host";
            AppendMenuW(hMenu, MF_STRING, (UIntPtr)CMD_TOGGLE_HOST, hostMenuText);

            if (!string.IsNullOrWhiteSpace(_deviceIdFormatted))
            {
                AppendMenuW(hMenu, MF_STRING, (UIntPtr)CMD_COPY_ID, $"Salin Device ID ({_deviceIdFormatted})");
            }

            AppendMenuW(hMenu, MF_SEPARATOR, UIntPtr.Zero, null);
            AppendMenuW(hMenu, MF_STRING, (UIntPtr)CMD_EXIT, "Keluar");

            GetCursorPos(out POINT pt);
            SetForegroundWindow(_hwnd);

            uint cmd = TrackPopupMenuEx(
                hMenu,
                TPM_RIGHTBUTTON | TPM_RETURNCMD | TPM_NONOTIFY,
                pt.X,
                pt.Y,
                _hwnd,
                IntPtr.Zero);

            PostMessageW(_hwnd, WM_NULL, IntPtr.Zero, IntPtr.Zero);

            switch (cmd)
            {
                case CMD_OPEN:
                    OpenRequested?.Invoke();
                    break;
                case CMD_TOGGLE_HOST:
                    ToggleHostRequested?.Invoke();
                    break;
                case CMD_COPY_ID:
                    CopyDeviceIdRequested?.Invoke();
                    break;
                case CMD_EXIT:
                    ExitRequested?.Invoke();
                    break;
            }
        }
        finally
        {
            DestroyMenu(hMenu);
        }
    }

    private static string Truncate(string text, int maxLen) =>
        text.Length <= maxLen ? text : text[..maxLen];

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        if (_hwnd != IntPtr.Zero && _iconAdded)
        {
            var nid = CreateBaseNotifyIconData();
            Shell_NotifyIconW(NIM_DELETE, ref nid);
            _iconAdded = false;
        }

        if (_ownsIcon && _hIcon != IntPtr.Zero)
        {
            DestroyIcon(_hIcon);
            _hIcon = IntPtr.Zero;
            _ownsIcon = false;
        }

        if (_hwnd != IntPtr.Zero)
        {
            DestroyWindow(_hwnd);
            _hwnd = IntPtr.Zero;
            UnregisterClassW(_className, GetModuleHandleW(null));
        }
    }

    // ── Win32 Interop ─────────────────────────────────────────────────────────

    private delegate IntPtr WndProcDelegate(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WNDCLASSEXW
    {
        public uint cbSize;
        public uint style;
        public IntPtr lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
        public IntPtr hIconSm;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NOTIFYICONDATAW
    {
        public uint cbSize;
        public IntPtr hWnd;
        public uint uID;
        public uint uFlags;
        public uint uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string szTip;
        public uint dwState;
        public uint dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string szInfo;
        public uint uTimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string szInfoTitle;
        public uint dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandleW(string? lpModuleName);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClassExW(ref WNDCLASSEXW lpwcx);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnregisterClassW(string lpClassName, IntPtr hInstance);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowExW(
        uint dwExStyle,
        string lpClassName,
        string lpWindowName,
        uint dwStyle,
        int x, int y, int nWidth, int nHeight,
        IntPtr hWndParent,
        IntPtr hMenu,
        IntPtr hInstance,
        IntPtr lpParam);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyWindow(IntPtr hWnd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DefWindowProcW(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint RegisterWindowMessageW(string lpString);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr LoadImageW(IntPtr hInst, string name, uint type, int cx, int cy, uint fuLoad);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr LoadIconW(IntPtr hInstance, IntPtr lpIconName);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr hIcon);

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool Shell_NotifyIconW(uint dwMessage, ref NOTIFYICONDATAW lpData);

    [DllImport("user32.dll")]
    private static extern IntPtr CreatePopupMenu();

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyMenu(IntPtr hMenu);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AppendMenuW(IntPtr hMenu, uint uFlags, UIntPtr uIDNewItem, string? lpNewItem);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetMenuDefaultItem(IntPtr hMenu, uint uItem, uint fByPos);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out POINT lpPoint);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern uint TrackPopupMenuEx(IntPtr hMenu, uint uFlags, int x, int y, IntPtr hWnd, IntPtr lptpm);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PostMessageW(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
}
