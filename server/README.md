# Multidevice Remote — Server Infrastructure

## Topologi Production

```
Client (Android / Windows PC)
        │
        │ wss:// (dengan ?token= atau header X-Server-Token)
        ▼
remote.agoy507.my.id  ──► Cloudflare Tunnel ──► 127.0.0.1:18081 (Caddy)
                                                         │
                                              ┌──────────┴──────────┐
                                              │   Docker Network     │
                                              │   172.18.0.0/16     │
                                              │                      │
                                     vps-caddy-1   vps-signaling-1  │
                                     :18081         :8080 (internal) │
                                              └──────────────────────┘

turn.agoy507.my.id → 168.110.216.221 (PC B, Oracle)
        │
        ├── 3478/tcp + 3478/udp   (STUN / TURN)
        └── 49160–49200/udp       (Relay media TURN)
```

## Containers

| Container | Image | Expose | Peran |
|---|---|---|---|
| `vps-caddy-1` | `caddy:2-alpine` | `127.0.0.1:18081` (loopback only) | Reverse proxy WSS `/ws` & HTTP `/health`, TLS terminator upstream |
| `vps-signaling-1` | Node.js custom | Internal Docker saja (`signaling:8080`) | WebSocket broker, autentikasi token, generator kredensial TURN ephemeral |
| `vps-coturn-1` | `coturn/coturn:4.6.3-alpine` | `0.0.0.0:3478` + `0.0.0.0:49160-49200/udp` | STUN NAT traversal & TURN media relay via HMAC secret |

## Cara Deploy di PC B

```bash
cd server

# 1. Buat file .env dari template
cp .env.example .env

# 2. Isi TURN_SECRET dan SERVER_API_TOKEN
nano .env

# 3. Jalankan semua container
docker compose up -d --build

# 4. Verifikasi
curl -i https://remote.agoy507.my.id/health
```

## Firewall PC B (firewalld / iptables)

Gunakan script otomatis `setup-firewall.sh` atau eksekusi manual:

```bash
# Buka port untuk STUN/TURN & UDP Relay
sudo firewall-cmd --permanent --zone=public --add-port=3478/tcp
sudo firewall-cmd --permanent --zone=public --add-port=3478/udp
sudo firewall-cmd --permanent --zone=public --add-port=49160-49200/udp
sudo firewall-cmd --reload
```

## Config Client

| Parameter | Nilai Production | Keterangan |
|---|---|---|
| **Signaling URL** | `wss://remote.agoy507.my.id/ws` | Jalur kontrol WebSocket |
| **Token Otorisasi (`SERVER_API_TOKEN`)** | *(Tersimpan di `/home/opc/.config/mdremote-server-token.txt`)* | Wajib diisi jika server mengaktifkan token auth (`?token=...` atau header `X-Server-Token` / `Bearer`) |
| **STUN & TURN Server** | `turn.agoy507.my.id:3478` | Dinegosiasikan **otomatis** oleh signaling saat registrasi |
| **TURN Username & Password** | **Ephemeral HMAC-SHA1** | Dibuat otomatis per sesi (TTL 1 jam). Client **tidak** menyimpan password statis! |

## Keamanan

- **Signaling Private**: Tidak terekspos langsung ke IP publik — hanya dapat diakses melalui Cloudflare Tunnel (TLS termination di edge Cloudflare).
- **Token Authorization**: Menghalangi pihak ketiga yang tidak berwenang untuk connect atau memantau daftar perangkat aktif.
- **Ephemeral TURN Credentials**: Kredensial relay dibuat dengan HMAC-SHA1 berbasis waktu kadaluarsa (TTL 1 jam) sesuai RFC 5766 REST API spec.
- **Anti-Intrusion / Anti-SSRF**: `coturn` memblokir relay ke subnet internal/private (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`).
- **Zero-Knowledge Signaling**: Server signaling hanya berperan sebagai relayer sinyal SDP/ICE dan verifikasi token; kunci enkripsi sesi (E2EE) tidak pernah melewati server secara terbuka.
- **End-to-End Encryption (E2EE)**: Komunikasi input dan kontrol dienkripsi menggunakan AES-256-GCM dengan pertukaran kunci ECDH P-256.
