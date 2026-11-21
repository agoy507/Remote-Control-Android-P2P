using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;
using SIPSorceryMedia.Abstractions;
using SIPSorceryMedia.Encoders;

namespace MultideviceRemote.Host;

/// <summary>
/// Menangkap layar desktop Windows utama via Win32 GDI (termasuk kursor)
/// dan meng-encode menjadi frame VP8 menggunakan SIPSorceryMedia.Encoders.
/// </summary>
public sealed class DesktopScreenCapturer : IDisposable
{
    private readonly ILogger<DesktopScreenCapturer> _logger;
    private readonly object _encoderLock = new();
    private readonly object _stateLock = new();

    private VpxVideoEncoder _encoder;
    private CancellationTokenSource? _cts;
    private Task? _captureTask;
    private bool _disposed;

    private string _qualityPreset;
    private int _maxLongEdge;
    private int _fps;
    private uint _targetKbps;
    private volatile bool _forceNextKeyFrame = true;

    private int _lastWidth;
    private int _lastHeight;

    public event Action<uint, byte[]>? OnEncodedVideoFrame;

    public DesktopScreenCapturer(string qualityPreset = "BALANCED")
    {
        _logger = App.LoggerFactory.CreateLogger<DesktopScreenCapturer>();
        _qualityPreset = qualityPreset;
        ApplyPresetValues(qualityPreset, out _maxLongEdge, out _fps, out _targetKbps);
        _encoder = new VpxVideoEncoder { TargetKbps = _targetKbps };
    }

    public List<VideoFormat> GetVideoSourceFormats()
    {
        lock (_encoderLock)
        {
            return new List<VideoFormat>(_encoder.SupportedFormats);
        }
    }

    public void Start()
    {
        lock (_stateLock)
        {
            if (_disposed || _cts != null) return;
            _cts = new CancellationTokenSource();
            _forceNextKeyFrame = true;
            var token = _cts.Token;
            _captureTask = Task.Run(() => CaptureLoopAsync(token), token);
            _logger.LogInformation("DesktopScreenCapturer dimulai ({Preset}, maxEdge={Edge}, fps={Fps}, kbps={Kbps})",
                _qualityPreset, _maxLongEdge, _fps, _targetKbps);
        }
    }

    public void Stop()
    {
        Task? taskToWait;
        CancellationTokenSource? ctsToCancel;
        lock (_stateLock)
        {
            ctsToCancel = _cts;
            taskToWait = _captureTask;
            _cts = null;
            _captureTask = null;
        }

        if (ctsToCancel != null)
        {
            try { ctsToCancel.Cancel(); } catch { /* ignore */ }
            if (taskToWait != null && !taskToWait.IsCompleted)
            {
                try { taskToWait.Wait(TimeSpan.FromSeconds(2)); } catch { /* ignore */ }
            }
            ctsToCancel.Dispose();
        }
    }

    public void UpdateQualityPreset(string preset)
    {
        ApplyPresetValues(preset, out int edge, out int fps, out uint kbps);
        lock (_encoderLock)
        {
            if (_disposed) return;
            _qualityPreset = preset;
            _maxLongEdge = edge;
            _fps = fps;
            if (_targetKbps != kbps)
            {
                _targetKbps = kbps;
                RecreateEncoderLocked();
            }
            _forceNextKeyFrame = true;
        }
        _logger.LogInformation("Kualitas host diubah ke {Preset} (maxEdge={Edge}, fps={Fps}, kbps={Kbps})",
            preset, edge, fps, kbps);
    }

    public void ForceKeyFrame()
    {
        _forceNextKeyFrame = true;
        lock (_encoderLock)
        {
            if (!_disposed)
            {
                _encoder.ForceKeyFrame();
            }
        }
    }

    private void RecreateEncoderLocked()
    {
        _encoder.Dispose();
        _encoder = new VpxVideoEncoder { TargetKbps = _targetKbps };
        _encoder.ForceKeyFrame();
    }

    private static void ApplyPresetValues(string preset, out int maxLongEdge, out int fps, out uint targetKbps)
    {
        switch (preset?.Trim().ToUpperInvariant())
        {
            case "ULTRA_LOW_LATENCY":
                maxLongEdge = 1280;
                fps = 30;
                targetKbps = 2500;
                break;
            case "DATA_SAVER":
                maxLongEdge = 960;
                fps = 15;
                targetKbps = 800;
                break;
            default: // BALANCED
                maxLongEdge = 1280;
                fps = 24;
                targetKbps = 1500;
                break;
        }
    }

    private async Task CaptureLoopAsync(CancellationToken ct)
    {
        IntPtr dstMemDC = IntPtr.Zero;
        IntPtr dstBitmap = IntPtr.Zero;
        IntPtr dstOldBitmap = IntPtr.Zero;
        IntPtr bitsPtr = IntPtr.Zero;

        IntPtr srcMemDC = IntPtr.Zero;
        IntPtr srcBitmap = IntPtr.Zero;
        IntPtr srcOldBitmap = IntPtr.Zero;

        int allocatedSrcW = 0;
        int allocatedSrcH = 0;
        int allocatedDstW = 0;
        int allocatedDstH = 0;

        byte[] bgraBuffer = Array.Empty<byte>();
        byte[] i420Buffer = Array.Empty<byte>();
        long frameCounter = 0;
        bool captureFailureLogged = false;

        void ReleaseTargetSurface()
        {
            if (srcMemDC != IntPtr.Zero && srcOldBitmap != IntPtr.Zero)
            {
                SelectObject(srcMemDC, srcOldBitmap);
                srcOldBitmap = IntPtr.Zero;
            }
            if (srcBitmap != IntPtr.Zero)
            {
                DeleteObject(srcBitmap);
                srcBitmap = IntPtr.Zero;
            }
            if (srcMemDC != IntPtr.Zero)
            {
                DeleteDC(srcMemDC);
                srcMemDC = IntPtr.Zero;
            }

            if (dstMemDC != IntPtr.Zero && dstOldBitmap != IntPtr.Zero)
            {
                SelectObject(dstMemDC, dstOldBitmap);
                dstOldBitmap = IntPtr.Zero;
            }
            if (dstBitmap != IntPtr.Zero)
            {
                DeleteObject(dstBitmap);
                dstBitmap = IntPtr.Zero;
                bitsPtr = IntPtr.Zero;
            }
            if (dstMemDC != IntPtr.Zero)
            {
                DeleteDC(dstMemDC);
                dstMemDC = IntPtr.Zero;
            }

            allocatedSrcW = 0;
            allocatedSrcH = 0;
            allocatedDstW = 0;
            allocatedDstH = 0;
        }

        try
        {
            while (!ct.IsCancellationRequested)
            {
                long frameStartTicks = Stopwatch.GetTimestamp();

                int srcW = GetSystemMetrics(SM_CXSCREEN);
                int srcH = GetSystemMetrics(SM_CYSCREEN);
                int fpsSnapshot;
                int maxEdgeSnapshot;

                lock (_encoderLock)
                {
                    fpsSnapshot = Math.Max(1, _fps);
                    maxEdgeSnapshot = _maxLongEdge;
                }

                if (srcW > 0 && srcH > 0)
                {
                    ComputeTargetDimensions(srcW, srcH, maxEdgeSnapshot, out int targetW, out int targetH);
                    bool needsScale = targetW != srcW || targetH != srcH;

                    if (srcW != allocatedSrcW || srcH != allocatedSrcH ||
                        targetW != allocatedDstW || targetH != allocatedDstH)
                    {
                        ReleaseTargetSurface();
                        if (!CreateCaptureSurfaces(
                                srcW, srcH, targetW, targetH, needsScale,
                                out dstMemDC, out dstBitmap, out dstOldBitmap, out bitsPtr,
                                out srcMemDC, out srcBitmap, out srcOldBitmap))
                        {
                            await Task.Delay(250, ct);
                            continue;
                        }
                        allocatedSrcW = srcW;
                        allocatedSrcH = srcH;
                        allocatedDstW = targetW;
                        allocatedDstH = targetH;
                        bgraBuffer = new byte[targetW * targetH * 4];
                        i420Buffer = new byte[targetW * targetH * 3 / 2];
                    }

                    bool captured = CaptureScreenToDib(dstMemDC, srcMemDC, srcW, srcH, targetW, targetH);
                    if (!captured)
                    {
                        if (!captureFailureLogged)
                        {
                            _logger.LogWarning("CaptureScreenToDib gagal (kemungkinan layar terkunci / UAC / desktop terisolasi).");
                            captureFailureLogged = true;
                        }
                    }
                    else if (bitsPtr != IntPtr.Zero)
                    {
                        captureFailureLogged = false;
                        Marshal.Copy(bitsPtr, bgraBuffer, 0, bgraBuffer.Length);
                        ConvertBgraToI420(bgraBuffer, targetW, targetH, i420Buffer);

                        byte[]? encoded = null;
                        lock (_encoderLock)
                        {
                            if (!_disposed)
                            {
                                if (_lastWidth != targetW || _lastHeight != targetH)
                                {
                                    if (_lastWidth != 0 && _lastHeight != 0)
                                    {
                                        RecreateEncoderLocked();
                                    }
                                    _lastWidth = targetW;
                                    _lastHeight = targetH;
                                    _forceNextKeyFrame = true;
                                }

                                bool periodicKey = frameCounter % (fpsSnapshot * 2) == 0;
                                if (_forceNextKeyFrame || periodicKey)
                                {
                                    _encoder.ForceKeyFrame();
                                    _forceNextKeyFrame = false;
                                }

                                encoded = _encoder.EncodeVideo(
                                    targetW,
                                    targetH,
                                    i420Buffer,
                                    VideoPixelFormatsEnum.I420,
                                    VideoCodecsEnum.VP8);
                            }
                        }

                        if (encoded is { Length: > 0 })
                        {
                            uint durationRtpUnits = (uint)(90_000 / fpsSnapshot);
                            OnEncodedVideoFrame?.Invoke(durationRtpUnits, encoded);
                        }

                        frameCounter++;
                    }
                }

                double targetFrameMs = 1000.0 / fpsSnapshot;
                double elapsedMs = (Stopwatch.GetTimestamp() - frameStartTicks) * 1000.0 / Stopwatch.Frequency;
                int sleepMs = (int)Math.Floor(targetFrameMs - elapsedMs);
                if (sleepMs > 0)
                {
                    await Task.Delay(sleepMs, ct);
                }
            }
        }
        catch (OperationCanceledException) { /* normal stop */ }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error pada loop capture layar desktop");
        }
        finally
        {
            ReleaseTargetSurface();
        }
    }

    private static void ComputeTargetDimensions(int srcW, int srcH, int maxLongEdge, out int targetW, out int targetH)
    {
        int longEdge = Math.Max(srcW, srcH);
        double scale = longEdge > maxLongEdge ? (double)maxLongEdge / longEdge : 1.0;

        int w = (int)Math.Round(srcW * scale);
        int h = (int)Math.Round(srcH * scale);

        // Enforce even dimensions >= 16 and never upscale past source
        w = Math.Clamp(w & ~1, 16, srcW & ~1);
        h = Math.Clamp(h & ~1, 16, srcH & ~1);

        targetW = Math.Max(16, w);
        targetH = Math.Max(16, h);
    }

    private bool CreateCaptureSurfaces(
        int srcWidth,
        int srcHeight,
        int targetWidth,
        int targetHeight,
        bool needsScale,
        out IntPtr dstMemDC,
        out IntPtr dstBitmap,
        out IntPtr dstOldBitmap,
        out IntPtr bitsPtr,
        out IntPtr srcMemDC,
        out IntPtr srcBitmap,
        out IntPtr srcOldBitmap)
    {
        dstMemDC = IntPtr.Zero;
        dstBitmap = IntPtr.Zero;
        dstOldBitmap = IntPtr.Zero;
        bitsPtr = IntPtr.Zero;
        srcMemDC = IntPtr.Zero;
        srcBitmap = IntPtr.Zero;
        srcOldBitmap = IntPtr.Zero;

        IntPtr screenDC = GetDC(IntPtr.Zero);
        if (screenDC == IntPtr.Zero) return false;

        try
        {
            dstMemDC = CreateCompatibleDC(screenDC);
            if (dstMemDC == IntPtr.Zero) return false;

            var bmi = new BITMAPINFO
            {
                bmiHeader = new BITMAPINFOHEADER
                {
                    biSize = (uint)Marshal.SizeOf<BITMAPINFOHEADER>(),
                    biWidth = targetWidth,
                    biHeight = -targetHeight, // top-down DIB
                    biPlanes = 1,
                    biBitCount = 32,
                    biCompression = BI_RGB
                }
            };

            dstBitmap = CreateDIBSection(dstMemDC, ref bmi, DIB_RGB_COLORS, out bitsPtr, IntPtr.Zero, 0);
            if (dstBitmap == IntPtr.Zero || bitsPtr == IntPtr.Zero)
            {
                if (dstBitmap != IntPtr.Zero) DeleteObject(dstBitmap);
                DeleteDC(dstMemDC);
                dstMemDC = IntPtr.Zero;
                dstBitmap = IntPtr.Zero;
                return false;
            }

            dstOldBitmap = SelectObject(dstMemDC, dstBitmap);

            if (needsScale)
            {
                srcMemDC = CreateCompatibleDC(screenDC);
                srcBitmap = srcMemDC != IntPtr.Zero ? CreateCompatibleBitmap(screenDC, srcWidth, srcHeight) : IntPtr.Zero;
                if (srcMemDC == IntPtr.Zero || srcBitmap == IntPtr.Zero)
                {
                    if (srcBitmap != IntPtr.Zero) DeleteObject(srcBitmap);
                    if (srcMemDC != IntPtr.Zero) DeleteDC(srcMemDC);
                    SelectObject(dstMemDC, dstOldBitmap);
                    DeleteObject(dstBitmap);
                    DeleteDC(dstMemDC);
                    srcMemDC = IntPtr.Zero;
                    srcBitmap = IntPtr.Zero;
                    dstMemDC = IntPtr.Zero;
                    dstBitmap = IntPtr.Zero;
                    dstOldBitmap = IntPtr.Zero;
                    bitsPtr = IntPtr.Zero;
                    return false;
                }
                srcOldBitmap = SelectObject(srcMemDC, srcBitmap);
            }

            return true;
        }
        finally
        {
            ReleaseDC(IntPtr.Zero, screenDC);
        }
    }

    private static bool CaptureScreenToDib(
        IntPtr dstMemDC,
        IntPtr srcMemDC,
        int srcW,
        int srcH,
        int targetW,
        int targetH)
    {
        IntPtr screenDC = GetDC(IntPtr.Zero);
        if (screenDC == IntPtr.Zero) return false;

        try
        {
            bool bltOk;
            if (targetW == srcW && targetH == srcH || srcMemDC == IntPtr.Zero)
            {
                bltOk = BitBlt(dstMemDC, 0, 0, targetW, targetH, screenDC, 0, 0, SRCCOPY);
            }
            else
            {
                bltOk = BitBlt(srcMemDC, 0, 0, srcW, srcH, screenDC, 0, 0, SRCCOPY);
                if (bltOk)
                {
                    SetStretchBltMode(dstMemDC, HALFTONE);
                    SetBrushOrgEx(dstMemDC, 0, 0, IntPtr.Zero);
                    bltOk = StretchBlt(dstMemDC, 0, 0, targetW, targetH, srcMemDC, 0, 0, srcW, srcH, SRCCOPY);
                }
            }

            if (bltOk)
            {
                DrawScaledCursor(dstMemDC, srcW, srcH, targetW, targetH);
            }
            return bltOk;
        }
        finally
        {
            ReleaseDC(IntPtr.Zero, screenDC);
        }
    }

    private static void DrawScaledCursor(IntPtr memDC, int srcW, int srcH, int targetW, int targetH)
    {
        var ci = new CURSORINFO { cbSize = Marshal.SizeOf<CURSORINFO>() };
        if (!GetCursorInfo(ref ci) || ci.flags != CURSOR_SHOWING || ci.hCursor == IntPtr.Zero)
            return;

        int hotX = 0;
        int hotY = 0;
        if (GetIconInfo(ci.hCursor, out ICONINFO ii))
        {
            hotX = ii.xHotspot;
            hotY = ii.yHotspot;
            if (ii.hbmMask != IntPtr.Zero) DeleteObject(ii.hbmMask);
            if (ii.hbmColor != IntPtr.Zero) DeleteObject(ii.hbmColor);
        }

        double scaleX = (double)targetW / srcW;
        double scaleY = (double)targetH / srcH;
        int drawX = (int)Math.Round((ci.ptScreenPos.X - hotX) * scaleX);
        int drawY = (int)Math.Round((ci.ptScreenPos.Y - hotY) * scaleY);

        int cursorSizeX = Math.Max(12, (int)Math.Round(32 * scaleX));
        int cursorSizeY = Math.Max(12, (int)Math.Round(32 * scaleY));
        DrawIconEx(memDC, drawX, drawY, ci.hCursor, cursorSizeX, cursorSizeY, 0, IntPtr.Zero, DI_NORMAL);
    }

    /// <summary>Konversi top-down BGRA32 ke planar YUV I420 dengan BT.601 integer math dan rata-rata blok 2x2 untuk U/V.</summary>
    public static void ConvertBgraToI420(byte[] bgra, int width, int height, byte[] i420)
    {
        int ySize = width * height;
        int uvStride = width / 2;
        int uOffset = ySize;
        int vOffset = ySize + uvStride * (height / 2);
        int bgraStride = width * 4;

        for (int y = 0; y < height; y++)
        {
            int rowBase = y * bgraStride;
            int yRowBase = y * width;
            bool isEvenRow = (y & 1) == 0;
            int nextRowBase = isEvenRow && (y + 1 < height) ? rowBase + bgraStride : rowBase;
            int uvRowBase = (y >> 1) * uvStride;

            for (int x = 0; x < width; x++)
            {
                int px = rowBase + (x << 2);
                int b = bgra[px];
                int g = bgra[px + 1];
                int r = bgra[px + 2];

                int yVal = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                i420[yRowBase + x] = (byte)Math.Clamp(yVal, 0, 255);

                if (isEvenRow && (x & 1) == 0 && (x + 1 < width))
                {
                    int p01 = px + 4;
                    int p10 = nextRowBase + (x << 2);
                    int p11 = p10 + 4;

                    int bAvg = (b + bgra[p01] + bgra[p10] + bgra[p11]) >> 2;
                    int gAvg = (g + bgra[p01 + 1] + bgra[p10 + 1] + bgra[p11 + 1]) >> 2;
                    int rAvg = (r + bgra[p01 + 2] + bgra[p10 + 2] + bgra[p11 + 2]) >> 2;

                    int uVal = ((-38 * rAvg - 74 * gAvg + 112 * bAvg + 128) >> 8) + 128;
                    int vVal = ((112 * rAvg - 94 * gAvg - 18 * bAvg + 128) >> 8) + 128;

                    int uvIdx = uvRowBase + (x >> 1);
                    i420[uOffset + uvIdx] = (byte)Math.Clamp(uVal, 0, 255);
                    i420[vOffset + uvIdx] = (byte)Math.Clamp(vVal, 0, 255);
                }
            }
        }
    }

    public void Dispose()
    {
        Stop();
        lock (_encoderLock)
        {
            if (_disposed) return;
            _disposed = true;
            _encoder.Dispose();
        }
    }

    // ── Win32 GDI P/Invoke ────────────────────────────────────────────────────

    private const int SM_CXSCREEN = 0;
    private const int SM_CYSCREEN = 1;
    private const uint SRCCOPY = 0x00CC0020;
    private const int HALFTONE = 4;
    private const uint DIB_RGB_COLORS = 0;
    private const uint BI_RGB = 0;
    private const uint CURSOR_SHOWING = 0x00000001;
    private const uint DI_NORMAL = 0x0003;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CURSORINFO
    {
        public int cbSize;
        public uint flags;
        public IntPtr hCursor;
        public POINT ptScreenPos;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ICONINFO
    {
        [MarshalAs(UnmanagedType.Bool)]
        public bool fIcon;
        public int xHotspot;
        public int yHotspot;
        public IntPtr hbmMask;
        public IntPtr hbmColor;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAPINFOHEADER
    {
        public uint biSize;
        public int biWidth;
        public int biHeight;
        public ushort biPlanes;
        public ushort biBitCount;
        public uint biCompression;
        public uint biSizeImage;
        public int biXPelsPerMeter;
        public int biYPelsPerMeter;
        public uint biClrUsed;
        public uint biClrImportant;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAPINFO
    {
        public BITMAPINFOHEADER bmiHeader;
        public uint bmiColors;
    }

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);

    [DllImport("user32.dll")]
    private static extern IntPtr GetDC(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorInfo(ref CURSORINFO pci);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetIconInfo(IntPtr hIcon, out ICONINFO piconinfo);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DrawIconEx(
        IntPtr hdc, int xLeft, int yTop, IntPtr hIcon,
        int cxWidth, int cyWidth, uint istepIfAniCur, IntPtr hbrFlickerFreeDraw, uint diFlags);

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateCompatibleDC(IntPtr hdc);

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateCompatibleBitmap(IntPtr hdc, int cx, int cy);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeleteDC(IntPtr hdc);

    [DllImport("gdi32.dll")]
    private static extern IntPtr SelectObject(IntPtr hdc, IntPtr h);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeleteObject(IntPtr ho);

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateDIBSection(
        IntPtr hdc, ref BITMAPINFO pbmi, uint usage, out IntPtr ppvBits, IntPtr hSection, uint offset);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool BitBlt(
        IntPtr hdc, int x, int y, int cx, int cy, IntPtr hdcSrc, int x1, int y1, uint rop);

    [DllImport("gdi32.dll")]
    private static extern int SetStretchBltMode(IntPtr hdc, int mode);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetBrushOrgEx(IntPtr hdc, int x, int y, IntPtr lppt);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool StretchBlt(
        IntPtr hdcDest, int xDest, int yDest, int wDest, int hDest,
        IntPtr hdcSrc, int xSrc, int ySrc, int wSrc, int hSrc, uint rop);
}
