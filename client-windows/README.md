# Multidevice Remote — Windows PC Client

PC Controller untuk Multidevice Remote. Menerima stream layar Android via WebRTC dan mengirim input mouse/keyboard.

## Prasyarat

- Windows 10 1809+ (Windows 11 direkomendasikan)
- [Visual Studio 2022 17.9+](https://visualstudio.microsoft.com/) dengan workload **Windows App SDK**
- [.NET 9 SDK](https://dotnet.microsoft.com/download/dotnet/9.0)

## Build

```powershell
cd client-windows
dotnet restore
dotnet build MultideviceRemote.sln -c Release
```

Atau buka `MultideviceRemote.sln` di Visual Studio 2022.

## Konfigurasi

Saat pertama kali buka app:

1. **Device ID Saya** — ID unik PC kamu (auto-generate, bisa diubah)
2. **Device ID Target** — Device ID Android host yang ingin dikontrol
3. **Pengaturan Server** (expand) — Isi:
   - **URL Signaling**: `wss://remote.agoy507.my.id/ws`
   - **Token Otorisasi**: nilai dari `SERVER_API_TOKEN` di server

Token dikirim sebagai `?token=...` di URL WebSocket — sama dengan cara kerja Android client.

## Arsitektur

```
client-windows/MultideviceRemote/
├── Crypto/           E2EE AES-256-GCM (mirror Android)
├── Model/            RemoteControlPacket, ServerEndpointConfig, AppSettings
├── Network/          SignalingClient (WebSocket + token auth)
├── WebRtc/           SessionCoordinator (SIPSorcery peer connection)
├── Input/            InputCapture (mouse/keyboard → RemoteControlPacket)
├── Rendering/        VideoFrameRenderer (D3D11 — Phase 2)
└── Views/            ConnectView + RemoteView (WinUI 3 XAML)
```

## Roadmap

| Phase | Status | Detail |
|---|---|---|
| 1 — Signaling + Auth | ✅ Scaffold | WinUI3, SignalingClient, token support |
| 2 — WebRTC + Video | 🔧 TODO | SIPSorcery peer, H264 render via D3D11 |
| 3 — Input DataChannel | 🔧 TODO | Mouse/keyboard global hook |
| 4 — E2EE ECDH | 🔧 TODO | Mirror Android AES-256-GCM handshake |
| 5 — UI Polish | 🔧 TODO | Settings, reconnect, fullscreen |
