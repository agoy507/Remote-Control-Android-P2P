import http from 'node:http';
import crypto from 'node:crypto';
import express from 'express';
import { WebSocketServer, WebSocket } from 'ws';
import 'dotenv/config';

const PORT = Number(process.env.PORT || 8080);
const TURN_SECRET = process.env.TURN_SECRET || 'change_this_turn_hmac_secret_in_production';
const TURN_URLS = (process.env.TURN_URLS || 'turn:localhost:3478?transport=udp,turn:localhost:3478?transport=tcp')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);
const STUN_URLS = (process.env.STUN_URLS || 'stun:localhost:3478,stun:stun.l.google.com:19302')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);
const SERVER_API_TOKEN = process.env.SERVER_API_TOKEN || '';
const TURN_TTL_SECONDS = Number(process.env.TURN_TTL_SECONDS || 3600);
const MAX_AUTH_FAILURES_PER_WINDOW = Number(process.env.MAX_AUTH_FAILURES || 5);
const AUTH_WINDOW_MS = Number(process.env.AUTH_WINDOW_MS || 60_000);

const app = express();
app.use(express.json({ limit: '64kb' }));

// In-memory registry of active devices (zero-knowledge: no session passwords stored!)
const devices = new Map(); // normalizedDeviceId -> { ws, deviceId, alias, registeredAt, lastSeen }
const authFailures = new Map(); // `${clientIp}:${targetId}` -> { count, windowStart }

function normalizeDeviceId(id) {
  return String(id || '').replace(/[^0-9A-Za-z]/g, '').toUpperCase();
}

function generateTurnCredentials(userIdentifier) {
  const unixTimestamp = Math.floor(Date.now() / 1000) + TURN_TTL_SECONDS;
  const username = `${unixTimestamp}:${userIdentifier}`;
  const hmac = crypto.createHmac('sha1', TURN_SECRET);
  hmac.update(username);
  const credential = hmac.digest('base64');

  const iceServers = [];
  if (STUN_URLS.length > 0) {
    iceServers.push({ urls: STUN_URLS });
  }
  if (TURN_URLS.length > 0) {
    iceServers.push({
      urls: TURN_URLS,
      username,
      credential,
    });
  }
  return { iceServers, ttlSeconds: TURN_TTL_SECONDS };
}

function isAuthorizedHttp(req) {
  if (!SERVER_API_TOKEN) return true;
  const header = req.headers.authorization || '';
  return header === `Bearer ${SERVER_API_TOKEN}`;
}

function checkRateLimit(clientIp, targetId) {
  const key = `${clientIp}:${targetId}`;
  const now = Date.now();
  const entry = authFailures.get(key);
  if (!entry) return false;
  if (now - entry.windowStart > AUTH_WINDOW_MS) {
    authFailures.delete(key);
    return false;
  }
  return entry.count >= MAX_AUTH_FAILURES_PER_WINDOW;
}

function recordAuthFailure(clientIp, targetId) {
  const key = `${clientIp}:${targetId}`;
  const now = Date.now();
  const entry = authFailures.get(key);
  if (!entry || now - entry.windowStart > AUTH_WINDOW_MS) {
    authFailures.set(key, { count: 1, windowStart: now });
  } else {
    entry.count += 1;
  }
}

app.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    service: 'kendalip2p-signaling',
    activePeers: devices.size,
    uptimeSeconds: Math.floor(process.uptime()),
    turnConfigured: TURN_URLS.length > 0,
  });
});

app.get('/ice-servers', (req, res) => {
  if (!isAuthorizedHttp(req)) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  const peerId = normalizeDeviceId(req.query.deviceId || 'anon') || 'anon';
  const creds = generateTurnCredentials(peerId);
  res.json(creds);
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 256 * 1024 });

function sendJson(ws, payload) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

wss.on('connection', (ws, req) => {
  const clientIp = req.headers['x-forwarded-for']?.split(',')[0]?.trim() || req.socket.remoteAddress || 'unknown';
  ws.isAlive = true;
  ws.deviceId = null;

  if (SERVER_API_TOKEN) {
    const reqUrl = new URL(req.url || '/ws', 'http://localhost');
    const token = reqUrl.searchParams.get('token') || req.headers['x-server-token'] || '';
    if (token !== SERVER_API_TOKEN) {
      sendJson(ws, { type: 'error', code: 'UNAUTHORIZED', message: 'Token server tidak valid.' });
      ws.close(4001, 'Unauthorized');
      return;
    }
  }

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString('utf8'));
    } catch {
      sendJson(ws, { type: 'error', code: 'BAD_JSON', message: 'Format pesan JSON tidak valid.' });
      return;
    }

    const type = String(msg.type || '');
    if (type === 'ping') {
      sendJson(ws, { type: 'pong', ts: msg.ts || Date.now() });
      return;
    }

    if (type === 'register') {
      const deviceId = normalizeDeviceId(msg.deviceId);
      if (deviceId.length < 6 || deviceId.length > 24) {
        sendJson(ws, { type: 'error', code: 'INVALID_ID', message: 'ID perangkat harus 6-24 karakter.' });
        return;
      }

      const existing = devices.get(deviceId);
      if (existing && existing.ws !== ws && existing.ws.readyState === WebSocket.OPEN) {
        sendJson(existing.ws, { type: 'replaced', message: 'ID perangkat aktif di koneksi baru.' });
        existing.ws.close(4002, 'Replaced by new connection');
      }

      ws.deviceId = deviceId;
      devices.set(deviceId, {
        ws,
        deviceId,
        alias: String(msg.alias || '').slice(0, 48),
        registeredAt: Date.now(),
        lastSeen: Date.now(),
      });

      const ice = generateTurnCredentials(deviceId);
      sendJson(ws, {
        type: 'registered',
        deviceId,
        iceServers: ice.iceServers,
        ttlSeconds: ice.ttlSeconds,
        serverTime: Date.now(),
      });
      return;
    }

    if (!ws.deviceId) {
      sendJson(ws, { type: 'error', code: 'NOT_REGISTERED', message: 'Perangkat belum terdaftar pada signaling.' });
      return;
    }

    // Forward opaque E2EE handshake & WebRTC signaling envelopes between peers
    const relayTypes = new Set([
      'e2ee_hello',
      'e2ee_ack',
      'e2ee_reject',
      'webrtc_offer',
      'webrtc_answer',
      'webrtc_ice',
      'session_end',
    ]);

    if (!relayTypes.has(type)) {
      sendJson(ws, { type: 'error', code: 'UNKNOWN_TYPE', message: `Tipe pesan tidak dikenal: ${type}` });
      return;
    }

    const targetId = normalizeDeviceId(msg.targetId);
    if (!targetId) {
      sendJson(ws, { type: 'error', code: 'MISSING_TARGET', message: 'Target ID perangkat wajib diisi.' });
      return;
    }

    if (type === 'e2ee_hello' && checkRateLimit(clientIp, targetId)) {
      sendJson(ws, {
        type: 'e2ee_reject',
        fromId: targetId,
        sessionId: msg.sessionId || '',
        reason: 'RATE_LIMITED',
        message: 'Terlalu banyak percobaan gagal. Coba lagi dalam 1 menit.',
      });
      return;
    }

    if (type === 'e2ee_reject' && msg.reason === 'AUTH_FAILED') {
      recordAuthFailure(msg.clientIpHint || clientIp, ws.deviceId);
    }

    const targetPeer = devices.get(targetId);
    if (!targetPeer || targetPeer.ws.readyState !== WebSocket.OPEN) {
      sendJson(ws, {
        type: 'peer_offline',
        targetId,
        sessionId: msg.sessionId || '',
        message: 'Perangkat target sedang offline atau belum mengaktifkan layanan.',
      });
      return;
    }

    targetPeer.lastSeen = Date.now();
    sendJson(targetPeer.ws, {
      ...msg,
      fromId: ws.deviceId,
      relayedAt: Date.now(),
    });
  });

  ws.on('close', () => {
    if (ws.deviceId) {
      const current = devices.get(ws.deviceId);
      if (current && current.ws === ws) {
        devices.delete(ws.deviceId);
      }
    }
  });
});

// Keep-alive sweep every 25s to clean dead mobile sockets
const heartbeatInterval = setInterval(() => {
  for (const [id, peer] of devices.entries()) {
    if (!peer.ws.isAlive) {
      peer.ws.terminate();
      devices.delete(id);
      continue;
    }
    peer.ws.isAlive = false;
    peer.ws.ping();
  }
}, 25_000);

function shutdown() {
  clearInterval(heartbeatInterval);
  wss.close(() => {
    server.close(() => process.exit(0));
  });
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[KendaliP2P Signaling] Listening on 0.0.0.0:${PORT}`);
});
