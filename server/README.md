# Multidevice Remote — Server Infrastructure

## Topologi Production

```
Client (Android/PC)
        │
        │ wss://
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

| Container | Image | Expose |
|---|---|---|
| `vps-caddy-1` | `caddy:2-alpine` | `127.0.0.1:18081` (loopback only) |
| `vps-signaling-1` | Node.js custom | Internal Docker saja (`signaling:8080`) |
| `vps-coturn-1` | `coturn/coturn:4.6.3-alpine` | `0.0.0.0:3478` + `0.0.0.0:49160-49200/udp` |

## Cara Deploy di PC B

```bash
cd server

# 1. Buat file .env dari template
cp .env.example .env

# 2. Isi TURN_SECRET dengan secret yang sama dengan 17 Nov
nano .env

# 3. Jalankan semua container
docker compose up -d --build

# 4. Verifikasi
curl https://remote.agoy507.my.id/health
```

## Firewall PC B (firewalld)

```bash
# Buka port untuk STUN/TURN
sudo firewall-cmd --permanent --zone=public --add-port=3478/tcp
sudo firewall-cmd --permanent --zone=public --add-port=3478/udp
sudo firewall-cmd --permanent --zone=public --add-port=49160-49200/udp
sudo firewall-cmd --reload
```

## Config Client

| Setting | Value |
|---|---|
| Signaling URL | `wss://remote.agoy507.my.id/ws` |
| Health URL | `https://remote.agoy507.my.id/health` |
| STUN | `stun:turn.agoy507.my.id:3478` |
| TURN | `turn:turn.agoy507.my.id:3478?transport=udp` |
| TURN Username | `desktopremote` |
| TURN Credential | *dari HMAC server (otomatis dari signaling)* |

## Keamanan

- **Signaling**: tidak terekspos langsung ke internet — hanya lewat Cloudflare Tunnel (TLS termination di CF)
- **TURN credential**: ephemeral HMAC-SHA1, TTL 1 jam
- **coturn**: blokir relay ke private subnet (10.x, 172.16.x, 192.168.x, 127.x)
- **Zero-knowledge**: server signaling tidak menyimpan password sesi
- **E2EE**: AES-256-GCM di atas DTLS-SRTP WebRTC
