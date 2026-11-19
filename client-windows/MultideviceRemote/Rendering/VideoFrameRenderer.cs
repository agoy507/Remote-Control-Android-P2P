using Microsoft.UI.Xaml.Controls;

namespace MultideviceRemote.Rendering;

/// <summary>
/// Placeholder renderer untuk Phase 1.
/// Phase 2: Implementasi D3D11 / Win2D rendering dari SIPSorcery VideoTrack frames.
/// </summary>
public sealed class VideoFrameRenderer : IDisposable
{
    private readonly SwapChainPanel _panel;
    private bool _disposed;

    public VideoFrameRenderer(SwapChainPanel panel)
    {
        _panel = panel;
    }

    /// <summary>
    /// Dipanggil oleh WebRtcManager saat ada frame baru dari VideoTrack.
    /// Phase 2: decode H264 dan render via Direct3D 11 / Win2D.
    /// </summary>
    public void OnVideoFrame(byte[] frameData, int width, int height)
    {
        // TODO Phase 2: implement D3D11 rendering
        // 1. Decode H264 frame → NV12/YUV
        // 2. Upload ke D3D11 texture
        // 3. Present via SwapChain
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        // TODO Phase 2: release D3D11 resources
    }
}
