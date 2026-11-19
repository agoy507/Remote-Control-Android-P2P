# 📱🖥️ Multidevice Remote (MDRemote)

> Solusi remote control peer-to-peer (P2P) berlatensi ultra-rendah dengan enkripsi end-to-end (E2EE AES-256-GCM), penanganan NAT otomatis melalui WebRTC STUN/TURN, dan dukungan multiplatform (Android Host & Controller, Windows PC Controller).

---

## 📑 Daftar Isi

- [Arsitektur Sistem](#-arsitektur-sistem)
- [Komponen Ekosistem](#-komponen-ekosistem)
  - [1. Android App (`app/`)](#1-android-app-app)
  - [2. Windows PC Client (`client-windows/`)](#2-windows-pc-client-client-windows)
  - [3. Server Infrastruktur (`server/`)](#3-server-infrastruktur-server)
- [Protokol & Keamanan](#-protokol--keamanan)
  - [Signaling Token Authorization](#signaling-token-authorization)
  - [Ephemeral HMAC-SHA1 TURN Credentials](#ephemeral-hmac-sha1-turn-credentials)
  - [E2EE Handshake & Enkripsi AES-256-GCM](#e2ee-handshake--enkripsi-aes-256-gcm)
  - [Spesifikasi Paket Kontrol (`RemoteControlPacket`)](#spesifikasi-paket-kontrol-remotecontrolpacket)
- [Panduan Instalasi & Build](#-panduan-instalasi--build)
  - [Android Client](#android-client)
  - [Windows PC Client](#windows-pc-client)
  - [Server Deployment](#server-deployment)
- [Konfigurasi & Checklist Koneksi](#-konfigurasi--checklist-koneksi)
- [Troubleshooting & FAQ](#-troubleshooting--faq)

---

## 🏛️ Arsitektur Sistem

```mermaid
flowchart TD
    subgraph ClientLayer["🖥️ / 📱 Client Endpoints"]
        PC["Windows PC Client\n(WinUI 3 + SIPSorcery)"]
        AND_CTRL["Android Controller\n(Jetpack Compose + WebRTC)"]
        AND_HOST["Android Host\n(MediaProjection + AccessibilityService)"]
    end

    subgraph ServerInfra["☁️ Server Infrastructure (PC B / Oracle VPS)"]
        CF["Cloudflare Tunnel\n(remote.agoy507.my.id)"]
        CAD["Caddy Reverse Proxy\n(127.0.0.1:18081)"]
        SIG["Node.js Signaling Server\n(:8080 internal)"]
        TURN["coturn STUN/TURN\n(turn.agoy507.my.id:3478)"]
    end

    PC <-->|WSS Auth ?token=...| CF
    AND_CTRL <-->|WSS Auth ?token=...| CF
    AND_HOST <-->|WSS Auth ?token=...| CF
    CF --> CAD --> SIG

    SIG -.->|Ephemeral ICE Config\nHMAC-SHA1| PC
    SIG -.->|Ephemeral ICE Config\nHMAC-SHA1| AND_CTRL
    SIG -.->|Ephemeral ICE Config\nHMAC-SHA1| AND_HOST

    PC <==>|WebRTC P2P Direct (Host/Srflx)| AND_HOST
    AND_CTRL <==>|WebRTC P2P Direct (Host/Srflx)| AND_HOST
    PC <-.->|Relay Fallback (Symmetric NAT)| TURN
    AND_HOST <-.->|Relay Fallback (Symmetric NAT)| TURN
```

---

## 🧩 Komponen Ekosistem

### 1. Android App (`app/`)
- **Package**: `com.agoy507.mdremote`
- **Stack**: Kotlin, Jetpack Compose, Material 3, Coroutines & StateFlow.
- **Fitur Utama**:
  - **Host Mode**: Menangkap layar via Android `MediaProjection` API, melakukan hardware encoding H.264, dan mengeksekusi gesture sentuhan/navigasi via `AccessibilityService`.
  - **Controller Mode**: Menampilkan video stream layar host dengan latensi ultra rendah dan menerjemahkan input layar sentuh menjadi perintah kontrol.
  - **Pengaturan Server Dinamis**: Konfigurasi URL signaling, token otorisasi, dan uji konektivitas langsung di aplikasi.
  - **Keamanan Pin / Password Sesi**: Setiap host memiliki password sesi lokal yang diverifikasi via zero-knowledge proof.

### 2. Windows PC Client (`client-windows/`)
- **Solution**: `MultideviceRemote.sln`
- **Stack**: C# 13, .NET 9, **WinUI 3 (Windows App SDK)**, **SIPSorcery WebRTC**, Direct3D11 / Win2D SwapChainPanel.
- **Fitur Utama**:
  - Tampilan modern native Windows 11 Fluent Design.
  - Menerima video H.264 dari Android host dan merendernya di GPU SwapChain.
  - Input capture: Menerjemahkan klik mouse, drag, scroll wheel, dan shortcut keyboard PC ke event kontrol Android yang setara.
  - Mendukung enkripsi AES-256-GCM identik dengan implementasi Android.
  - Mendukung otorisasi token signaling via parameter URL `?token=...`.

### 3. Server Infrastruktur (`server/`)
- **Docker Compose**: Orkestrasi 3 layanan terisolasi:
  1. `vps-caddy-1`: Menangani WebSocket upgrade `/ws` dan endpoint `/health`, beroperasi hanya di localhost (`127.0.0.1:18081`).
  2. `vps-signaling-1`: Broker signaling Node.js, validasi token keamanan (`SERVER_API_TOKEN`), serta penghasil kredensial STUN/TURN otomatis.
  3. `vps-coturn-1`: Server TURN RFC 5766 di port 3478 dan alokasi port media relay UDP `49160-49200`.
- **Cloudflare Tunnel**: Memetakan traffic publik `remote.agoy507.my.id` langsung ke Caddy tanpa membuka port HTTP/HTTPS publik di firewall VPS.

---

## 🔒 Protokol & Keamanan

### Signaling Token Authorization
Server signaling dilindungi oleh shared secret `SERVER_API_TOKEN`:
- Client menyertakan token saat membuka WebSocket handshake:
  - Query parameter: `wss://remote.agoy507.my.id/ws?token=<SECRET_TOKEN>`
  - Atau header: `X-Server-Token: <SECRET_TOKEN>` / `Authorization: Bearer <SECRET_TOKEN>`
- Jika token tidak cocok atau absen saat `SERVER_API_TOKEN` aktif di server, server menolak koneksi dengan pesan error `UNAUTHORIZED` dan menutup soket.

### Ephemeral HMAC-SHA1 TURN Credentials
Client tidak pernah menyimpan password statis untuk relay TURN:
1. Saat client mengirim perintah `register` ke signaling server, server menghitung kredensial TURN dinamis:
   $$\text{Username} = \text{TimestampExpired} : \text{DeviceId}$$
   $$\text{Credential} = \text{Base64}(\text{HMAC-SHA1}(\text{TURN\_SECRET}, \text{Username}))$$
2. Kredensial ini dikirim kembali ke client dalam respons `registered.iceServers`.
3. Kredensial berlaku selama **1 jam (3600 detik)**. Client otomatis meminta perpanjangan sebelum waktu habis.
4. `TURN_SECRET` hanya ada di server (`.env`) dan tidak pernah dikirim ke client.

### E2EE Handshake & Enkripsi AES-256-GCM
Sesi kendali dilindungi secara End-to-End:
- **Key Exchange**: ECDH menggunakan kurva `secp256r1` (NIST P-256).
- **Session Key Derivation**: SHA-256 dari ECDH Shared Secret menghasilkan kunci simetris 256-bit.
- **Wire Format**:
  $$\text{Payload} = \text{Nonce}(12\text{ bytes}) \parallel \text{Tag}(16\text{ bytes}) \parallel \text{Ciphertext}$$
- Menghasilkan perlindungan kerahasiaan (*confidentiality*) dan integritas (*tamper-proof*).

### Spesifikasi Paket Kontrol (`RemoteControlPacket`)
Format paket JSON yang dikirim melalui WebRTC DataChannel (`ordered: true`):
```json
{
  "type": "touch",
  "timestamp": 1716124800000,
  "x": 0.452,
  "y": 0.812,
  "action": 2,
  "keyCode": null,
  "scrollDx": null,
  "scrollDy": null,
  "sessionId": "a1b2c3d4"
}
```

| Type | Field Terkait | Deskripsi |
|---|---|---|
| `"touch"` | `x`, `y` (0.0–1.0), `action` (0=Down, 1=Up, 2=Move) | Sentuhan & gestur layar |
| `"key"` | `keyCode` (Android KeyEvent code, misal: 4=Back, 3=Home) | Tombol navigasi / fisik |
| `"scroll"` | `scrollDx`, `scrollDy` | Roda mouse / pengguliran vertikal-horizontal |
| `"ping"` / `"pong"` | `timestamp`, `sessionId` | Pengukuran RTT dan keepalive kanal data |

---

## 🚀 Panduan Instalasi & Build

### Android Client

#### Prasyarat
- Android SDK (API 34 / Android 14 recommended, min API 26 / Android 8.0)
- Java Development Kit (JDK 17)
- Gradle 9.3+ (Wrapper sudah disertakan)

#### Langkah Build
```powershell
# Clone repositori
git clone https://github.com/agoy507/Remote-Control-Android-P2P.git
cd Remote-Control-Android-P2P

# Konfigurasi SDK lokal (jika belum ada)
echo sdk.dir=C:\Users\%USERNAME%\AppData\Local\Android\Sdk > local.properties

# Build Debug APK
.\gradlew.bat assembleDebug

# Pasang ke perangkat via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

### Windows PC Client

#### Prasyarat
- Windows 10 versi 1809+ atau Windows 11
- [Visual Studio 2022 17.9+](https://visualstudio.microsoft.com/) dengan workload:
  - **.NET Desktop Development**
  - **Windows App SDK C# Templates**
- [.NET 9 SDK](https://dotnet.microsoft.com/download/dotnet/9.0)

#### Langkah Build
```powershell
cd client-windows

# Restore dependensi NuGet
dotnet restore

# Build rilis
dotnet build MultideviceRemote.sln -c Release

# Menjalankan aplikasi
dotnet run --project MultideviceRemote/MultideviceRemote.csproj
```
Atau cukup buka file `client-windows/MultideviceRemote.sln` langsung di Visual Studio dan tekan **F5**.

---

### Server Deployment

#### Prasyarat
- Linux VPS (Ubuntu / Oracle Linux / Debian)
- Docker & Docker Compose v2
- Cloudflare Tunnel (`cloudflared`) terpasang untuk tunneling WSS

#### Langkah Deploy
```bash
# Masuk ke direktori server
cd server

# Salin konfigurasi environment
cp .env.example .env

# Generate secret acak untuk TURN
TURN_SECRET=$(openssl rand -hex 32)
sed -i "s/ganti_dengan_secret_hmac_yang_sama_dengan_coturn/$TURN_SECRET/" .env

# Buat SERVER_API_TOKEN (opsional tapi sangat disarankan)
SERVER_TOKEN=$(openssl rand -hex 8)
sed -i "s/SERVER_API_TOKEN=/SERVER_API_TOKEN=$SERVER_TOKEN/" .env
echo "Token Anda: $SERVER_TOKEN"

# Buka firewall untuk port STUN/TURN
chmod +x setup-firewall.sh
sudo ./setup-firewall.sh

# Jalankan kontainer
docker compose up -d --build

# Verifikasi status
curl -i https://remote.agoy507.my.id/health
```

---

## ⚙️ Konfigurasi & Checklist Koneksi

Gunakan nilai default produksi berikut di antarmuka aplikasi:

| Setting | Parameter | Nilai Produksi |
|---|---|---|
| **Signaling Server** | `signalingUrl` | `wss://remote.agoy507.my.id/ws` |
| **Auth Token** | `serverApiToken` | *(Isi token dari server)* |
| **STUN Address** | *(Dikonfigurasi otomatis)* | `turn.agoy507.my.id:3478` |
| **TURN Relay** | *(Dikonfigurasi otomatis)* | `turn.agoy507.my.id:3478?transport=udp` |

### Prosedur Koneksi Antar Perangkat:
1. **Device A (Android Host)**:
   - Buka aplikasi → pastikan indikator signaling berwarna hijau (**Registered**).
   - Tentukan password sesi atau gunakan PIN acak yang digenerate.
   - Catat **Device ID** yang tertera (contoh: `A1B-C2D-3E4`).
   - Aktifkan toggle **Mulai Host** dan izinkan perekaman layar + izin Accessibility.
2. **Device B (Controller - Android atau Windows PC)**:
   - Buka aplikasi → masukkan Device ID target (`A1B-C2D-3E4`).
   - Masukkan PIN / password sesi Device A.
   - Tekan **Hubungkan**.
   - Setelah negosiasi WebRTC selesai, layar HP Host akan tampil dan dapat dikendalikan seketika.

---

## ❓ Troubleshooting & FAQ

#### 1. Status signaling tetap "Menghubungkan..." atau "Error"?
- Pastikan token diisi dengan benar jika server mengaktifkan `SERVER_API_TOKEN`.
- Periksa status server dengan mengakses URL `https://remote.agoy507.my.id/health` di browser. Hasil yang benar adalah status `200 OK` dengan informasi active peers.

#### 2. Layar tidak muncul atau koneksi stuck di "Menegoisasi Rute"?
- Pastikan port UDP `3478` dan `49160-49200` pada VPS tidak diblokir oleh Security List cloud (misal OCI Ingress Rules atau AWS Security Groups).
- Periksa apakah host mengizinkan izin *MediaProjection* saat dialog sistem muncul.

#### 3. Sentuhan tidak merespons di HP Host?
- Pastikan layanan Aksesibilitas (**Accessibility Service**) untuk **Multidevice Remote** telah aktif di *Settings > Accessibility* perangkat Android Host.

---

## 📄 Lisensi
Hak Cipta (c) 2026 Agoy507. Lisensi di bawah [MIT License](LICENSE).
