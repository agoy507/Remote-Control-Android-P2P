# KendaliP2P Container Server (Node.js Signaling + coturn STUN/TURN)

Paket container ini mengelola negosiasi WebRTC Peer-to-Peer secara aman (*zero-knowledge*) dan menyediakan relay STUN/TURN `coturn` dengan kredensial sementara berbasis HMAC-SHA1.

## Cara Menjalankan (Docker Compose)

1. Masuk ke folder `server/` dan buat file `.env`:
   ```bash
   cd server
   cp .env.example .env
   ```
2. Edit `.env` dan isi:
   - `PUBLIC_HOST`: Domain atau IP publik/LAN server Anda.
   - `PUBLIC_IP`: IPv4 publik/LAN server Anda untuk `coturn`.
   - `TURN_SECRET`: String acak minimal 32 karakter (`openssl rand -hex 32`).
   - `SERVER_API_TOKEN`: *(Opsional)* Token otorisasi server jika ingin membatasi akses server signaling.
3. Jalankan container:
   ```bash
   docker compose up -d --build
   ```
4. Verifikasi kesehatan server:
   ```bash
   curl http://localhost:8080/health
   ```

## Port Firewall yang Dibutuhkan
- `8080/tcp` (atau `443/tcp` di belakang Reverse Proxy TLS seperti Caddy/Nginx untuk `wss://`)
- `3478/udp` & `3478/tcp` (STUN / TURN)
- `5349/tcp` (TURNS)
- `49152-49252/udp` (Rentang port media relay TURN)

## Keamanan Zero-Knowledge
- Server signaling **tidak pernah** menerima atau menyimpan kata sandi sesi pengguna.
- Jabat tangan kunci **ECDH P-256** dan verifikasi kata sandi berbasis HMAC dilakukan langsung antar perangkat Android.
- Seluruh SDP, ICE candidate, dan perintah kontrol DataChannel dibungkus enkripsi **AES-256-GCM** tambahan di atas WebRTC DTLS-SRTP.
