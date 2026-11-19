using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.Extensions.Logging;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using SIPSorceryMedia.Abstractions;

namespace MultideviceRemote.Rendering;

/// <summary>
/// Renderer video dari SIPSorcery (WindowsVideoEndPoint) ke WriteableBitmap di SwapChainPanel.
/// 
/// Menerima decoded video samples (BGR/RGB/I420/NV12) dari media pipeline
/// dan menampilkannya pada UI control secara thread-safe menggunakan DispatcherQueue.
/// </summary>
public sealed class VideoFrameRenderer : IDisposable
{
    private readonly SwapChainPanel _panel;
    private readonly ILogger<VideoFrameRenderer> _logger;
    private readonly DispatcherQueue _dispatcherQueue;

    private WriteableBitmap? _bitmap;
    private Image? _imageElement;
    private bool _disposed;
    private bool _isRenderingFrame;

    private int _lastWidth;
    private int _lastHeight;

    public VideoFrameRenderer(SwapChainPanel panel)
    {
        _panel = panel;
        _logger = App.LoggerFactory.CreateLogger<VideoFrameRenderer>();
        _dispatcherQueue = DispatcherQueue.GetForCurrentThread()
            ?? DispatcherQueue.GetForCurrentThread();
    }

    /// <summary>
    /// Dipanggil oleh WindowsVideoEndPoint.OnVideoSinkDecodedSample saat ada frame decoded.
    /// </summary>
    public void RenderDecodedSample(byte[] sample, int width, int height, int stride, VideoPixelFormatsEnum pixelFormat)
    {
        if (_disposed || sample == null || sample.Length == 0 || width <= 0 || height <= 0) return;

        // Skip frame jika UI thread masih sibuk merender frame sebelumnya (frame dropping for smooth realtime)
        if (_isRenderingFrame) return;

        // Konversi sampel ke format BGRA8 (WinUI WriteableBitmap native format)
        byte[] bgraData = ConvertToBgra8(sample, width, height, stride, pixelFormat);

        _isRenderingFrame = true;
        _dispatcherQueue?.TryEnqueue(() =>
        {
            try
            {
                if (_disposed) return;

                // Inisialisasi atau resize bitmap jika dimensi berubah
                if (_bitmap == null || _lastWidth != width || _lastHeight != height)
                {
                    SetupBitmap(width, height);
                    _lastWidth = width;
                    _lastHeight = height;
                }

                if (_bitmap != null)
                {
                    using var stream = _bitmap.PixelBuffer.AsStream();
                    stream.Position = 0;
                    stream.Write(bgraData, 0, Math.Min(bgraData.Length, (int)stream.Length));
                    _bitmap.Invalidate();
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error rendering video frame ke WriteableBitmap");
            }
            finally
            {
                _isRenderingFrame = false;
            }
        });
    }

    private void SetupBitmap(int width, int height)
    {
        _bitmap = new WriteableBitmap(width, height);

        if (_imageElement == null)
        {
            _imageElement = new Image
            {
                Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform,
                HorizontalAlignment = Microsoft.UI.Xaml.HorizontalAlignment.Stretch,
                VerticalAlignment = Microsoft.UI.Xaml.VerticalAlignment.Stretch,
            };

            // Tambahkan elemen gambar ke SwapChainPanel
            _panel.Children.Clear();
            _panel.Children.Add(_imageElement);
        }

        _imageElement.Source = _bitmap;
        _logger.LogInformation("Video render surface diperbarui: {Width}x{Height}", width, height);
    }

    /// <summary>
    /// Konversi raw decoded sample (BGR, RGB, I420, NV12) ke BGRA8.
    /// </summary>
    private static byte[] ConvertToBgra8(byte[] src, int width, int height, int stride, VideoPixelFormatsEnum format)
    {
        int pixelCount = width * height;
        byte[] dst = new byte[pixelCount * 4];

        switch (format)
        {
            case VideoPixelFormatsEnum.Bgra:
                // Sudah BGRA: langsung salin
                Array.Copy(src, dst, Math.Min(src.Length, dst.Length));
                return dst;

            case VideoPixelFormatsEnum.Bgr:
            case VideoPixelFormatsEnum.Rgb:
                // 3 bytes per pixel
                int actualStride = stride > 0 ? stride : width * 3;
                bool isBgr = (format == VideoPixelFormatsEnum.Bgr);

                for (int y = 0; y < height; y++)
                {
                    int srcRow = y * actualStride;
                    int dstRow = y * width * 4;

                    for (int x = 0; x < width; x++)
                    {
                        int srcIdx = srcRow + x * 3;
                        int dstIdx = dstRow + x * 4;

                        if (srcIdx + 2 < src.Length && dstIdx + 3 < dst.Length)
                        {
                            if (isBgr)
                            {
                                dst[dstIdx + 0] = src[srcIdx + 0]; // B
                                dst[dstIdx + 1] = src[srcIdx + 1]; // G
                                dst[dstIdx + 2] = src[srcIdx + 2]; // R
                            }
                            else
                            {
                                dst[dstIdx + 0] = src[srcIdx + 2]; // B (swap R & B)
                                dst[dstIdx + 1] = src[srcIdx + 1]; // G
                                dst[dstIdx + 2] = src[srcIdx + 0]; // R
                            }
                            dst[dstIdx + 3] = 255; // Alpha
                        }
                    }
                }
                return dst;

            case VideoPixelFormatsEnum.I420:
                // Planar YUV 4:2:0 -> BGRA
                return ConvertI420ToBgra(src, width, height);

            case VideoPixelFormatsEnum.NV12:
                // Semi-planar YUV 4:2:0 -> BGRA
                return ConvertNv12ToBgra(src, width, height);

            default:
                // Fallback jika format tidak dikenal tetapi ukuran pas untuk 3 atau 4 bytes/pixel
                if (src.Length >= pixelCount * 4)
                {
                    Array.Copy(src, dst, dst.Length);
                }
                else if (src.Length >= pixelCount * 3)
                {
                    for (int i = 0, j = 0; i < pixelCount && j + 2 < src.Length; i++, j += 3)
                    {
                        dst[i * 4 + 0] = src[j + 0];
                        dst[i * 4 + 1] = src[j + 1];
                        dst[i * 4 + 2] = src[j + 2];
                        dst[i * 4 + 3] = 255;
                    }
                }
                return dst;
        }
    }

    private static byte[] ConvertI420ToBgra(byte[] yuv, int width, int height)
    {
        int ySize = width * height;
        int uvSize = (width / 2) * (height / 2);
        int uOffset = ySize;
        int vOffset = ySize + uvSize;

        byte[] bgra = new byte[ySize * 4];

        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int yIdx = y * width + x;
                int uvIdx = (y / 2) * (width / 2) + (x / 2);

                if (yIdx < ySize && uOffset + uvIdx < yuv.Length && vOffset + uvIdx < yuv.Length)
                {
                    int Y = yuv[yIdx] & 0xFF;
                    int U = (yuv[uOffset + uvIdx] & 0xFF) - 128;
                    int V = (yuv[vOffset + uvIdx] & 0xFF) - 128;

                    int R = Math.Clamp((int)(Y + 1.402 * V), 0, 255);
                    int G = Math.Clamp((int)(Y - 0.344136 * U - 0.714136 * V), 0, 255);
                    int B = Math.Clamp((int)(Y + 1.772 * U), 0, 255);

                    int dstIdx = yIdx * 4;
                    bgra[dstIdx + 0] = (byte)B;
                    bgra[dstIdx + 1] = (byte)G;
                    bgra[dstIdx + 2] = (byte)R;
                    bgra[dstIdx + 3] = 255;
                }
            }
        }
        return bgra;
    }

    private static byte[] ConvertNv12ToBgra(byte[] nv12, int width, int height)
    {
        int ySize = width * height;
        byte[] bgra = new byte[ySize * 4];

        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int yIdx = y * width + x;
                int uvIdx = ySize + (y / 2) * width + (x & ~1);

                if (yIdx < ySize && uvIdx + 1 < nv12.Length)
                {
                    int Y = nv12[yIdx] & 0xFF;
                    int U = (nv12[uvIdx] & 0xFF) - 128;
                    int V = (nv12[uvIdx + 1] & 0xFF) - 128;

                    int R = Math.Clamp((int)(Y + 1.402 * V), 0, 255);
                    int G = Math.Clamp((int)(Y - 0.344136 * U - 0.714136 * V), 0, 255);
                    int B = Math.Clamp((int)(Y + 1.772 * U), 0, 255);

                    int dstIdx = yIdx * 4;
                    bgra[dstIdx + 0] = (byte)B;
                    bgra[dstIdx + 1] = (byte)G;
                    bgra[dstIdx + 2] = (byte)R;
                    bgra[dstIdx + 3] = 255;
                }
            }
        }
        return bgra;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        _dispatcherQueue?.TryEnqueue(() =>
        {
            if (_imageElement != null)
            {
                _panel.Children.Remove(_imageElement);
                _imageElement = null;
            }
            _bitmap = null;
        });
    }
}
