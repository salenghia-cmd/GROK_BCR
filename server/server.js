const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const url = require('url');
const WebSocket = require('ws');

const HOST = '0.0.0.0';
const HTTP_PORT = Number(process.env.PORT || 8081);
const HTTPS_PORT = Number(process.env.HTTPS_PORT || 8443);

const WEB_ROOT = __dirname;
const INDEX_FILE = path.join(WEB_ROOT, 'index.html');
const CERTS_DIR = path.join(WEB_ROOT, 'certs');
const TLS_KEY_FILE = process.env.TLS_KEY_FILE || path.join(CERTS_DIR, 'key.pem');
const TLS_CERT_FILE = process.env.TLS_CERT_FILE || path.join(CERTS_DIR, 'cert.pem');

if (!fs.existsSync(TLS_KEY_FILE)) {
  console.error(`[fatal] Missing TLS key: ${TLS_KEY_FILE}`);
  process.exit(1);
}
if (!fs.existsSync(TLS_CERT_FILE)) {
  console.error(`[fatal] Missing TLS cert: ${TLS_CERT_FILE}`);
  process.exit(1);
}

const tlsOptions = {
  key: fs.readFileSync(TLS_KEY_FILE),
  cert: fs.readFileSync(TLS_CERT_FILE),
};

const sessions = new Map();

function normalizeMeta(input = {}) {
  return {
    sampleRate: Number(input.sampleRate) > 0 ? Number(input.sampleRate) : 16000,
    channels: Number(input.channels) > 0 ? Number(input.channels) : 1,
    bits: Number(input.bits) > 0 ? Number(input.bits) : 16,
    format: String(input.format || 'pcm_s16le'),
    deviceId: input.deviceId ? String(input.deviceId) : 'android',
    playbackMode: input.playbackMode ? String(input.playbackMode) : 'earpiece',
    injectMode: input.injectMode ? String(input.injectMode) : 'vendor_incall',
  };
}

function createSession(sessionId) {
  return {
    handset: null,
    webs: new Set(),
    meta: normalizeMeta(),
    lastState: null,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
}

function getSession(sessionId) {
  if (!sessions.has(sessionId)) {
    sessions.set(sessionId, createSession(sessionId));
  }
  return sessions.get(sessionId);
}

function cleanupSession(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return;

  if (!s.handset && s.webs.size === 0) {
    sessions.delete(sessionId);
    console.log(`[cleanup] removed empty session ${sessionId}`);
  }
}

function sendJson(ws, obj) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function broadcastJson(peers, obj) {
  const text = JSON.stringify(obj);
  for (const ws of peers) {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(text);
    }
  }
}

function sessionMetaMessage(sessionId, session) {
  return {
    type: 'media_info',
    session: sessionId,
    ...session.meta,
  };
}

function notifyMediaInfo(sessionId, session, targetWs = null) {
  const payload = sessionMetaMessage(sessionId, session);

  if (targetWs) {
    sendJson(targetWs, payload);
    return;
  }

  if (session.handset) {
    sendJson(session.handset, payload);
  }
  broadcastJson(session.webs, payload);
}

function getRedirectHost(req) {
  const hostHeader = req.headers.host || '';
  return hostHeader.split(':')[0] || 'localhost';
}

function handleHttpsRequest(req, res) {
  const parsed = url.parse(req.url);
  let pathname = parsed.pathname || '/';

  if (pathname === '/' || pathname === '/index.htm') {
    pathname = '/index.html';
  }

  if (pathname === '/index.html') {
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
    });
    fs.createReadStream(INDEX_FILE).pipe(res);
    return;
  }

  if (pathname === '/healthz') {
    res.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
    });
    res.end(
      JSON.stringify({
        ok: true,
        sessions: sessions.size,
        https: true,
        httpRedirect: true,
        httpsPort: HTTPS_PORT,
        httpPort: HTTP_PORT,
      })
    );
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Not found');
}

const httpServer = http.createServer((req, res) => {
  const host = getRedirectHost(req);
  const location = `https://${host}:${HTTPS_PORT}${req.url || '/'}`;
  res.writeHead(301, { Location: location });
  res.end();
});

const httpsServer = https.createServer(tlsOptions, handleHttpsRequest);

const wss = new WebSocket.Server({
  noServer: true,
  perMessageDeflate: false,
  maxPayload: 1024 * 1024,
});

httpsServer.on('upgrade', (req, socket, head) => {
  const parsed = url.parse(req.url, true);

  if (parsed.pathname !== '/ws') {
    socket.destroy();
    return;
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws, req) => {
  const parsed = url.parse(req.url, true);
  const querySession = String(parsed.query.session || '').trim();

  ws.role = null;
  ws.sessionId = querySession || null;
  ws.isAlive = true;

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      if (!ws.role || !ws.sessionId) return;

      const session = getSession(ws.sessionId);
      session.updatedAt = Date.now();

      if (ws.role === 'handset') {
        for (const peer of session.webs) {
          if (peer.readyState === WebSocket.OPEN) {
            peer.send(data, { binary: true });
          }
        }
      } else if (ws.role === 'web') {
        if (session.handset && session.handset.readyState === WebSocket.OPEN) {
          session.handset.send(data, { binary: true });
        }
      }
      return;
    }

    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch (e) {
      sendJson(ws, { type: 'error', message: 'invalid json' });
      return;
    }

    if (msg.type === 'hello') {
      const role = msg.role === 'handset' ? 'handset' : 'web';
      const sessionId = String(msg.session || ws.sessionId || '').trim();

      if (!sessionId) {
        sendJson(ws, { type: 'error', message: 'missing session' });
        return;
      }

      ws.role = role;
      ws.sessionId = sessionId;

      const session = getSession(sessionId);
      session.updatedAt = Date.now();

      if (role === 'handset') {
        if (session.handset && session.handset !== ws) {
          try {
            session.handset.close(4000, 'replaced by new handset');
          } catch (_) {}
        }

        session.handset = ws;
        session.meta = normalizeMeta({
          sampleRate: msg.sampleRate,
          channels: msg.channels,
          bits: msg.bits,
          format: msg.format,
          deviceId: msg.deviceId,
          playbackMode: msg.playbackMode,
          injectMode: msg.injectMode,
        });
      } else {
        session.webs.add(ws);
      }

      sendJson(ws, {
        type: 'hello_ack',
        role,
        session: sessionId,
        secure: true,
        ...session.meta,
      });

      if (session.lastState) {
        sendJson(ws, session.lastState);
      }

      if (role === 'web' && session.handset) {
        sendJson(ws, {
          type: 'peer',
          peer: 'handset',
          connected: true,
          session: sessionId,
        });
        notifyMediaInfo(sessionId, session, ws);
      }

      if (role === 'handset') {
        broadcastJson(session.webs, {
          type: 'peer',
          peer: 'handset',
          connected: true,
          session: sessionId,
        });
        notifyMediaInfo(sessionId, session);
      }

      console.log(
        `[hello] role=${role} session=${sessionId} ${session.meta.sampleRate}Hz/${session.meta.channels}ch/${session.meta.bits}bit ${session.meta.format}`
      );
      return;
    }

    if (!ws.sessionId) return;

    const session = getSession(ws.sessionId);
    session.updatedAt = Date.now();

    if (msg.type === 'ping') {
      sendJson(ws, { type: 'pong', session: ws.sessionId, ts: Date.now() });
      return;
    }

    if (msg.type === 'pong') {
      return;
    }

    if (msg.type === 'state') {
      session.lastState = msg;

      const peers = [];
      if (session.handset && session.handset !== ws) {
        peers.push(session.handset);
      }
      for (const peer of session.webs) {
        if (peer !== ws) {
          peers.push(peer);
        }
      }
      broadcastJson(peers, msg);
      return;
    }
  });

  ws.on('close', () => {
    const sessionId = ws.sessionId;
    if (!sessionId) return;

    const session = getSession(sessionId);

    if (ws.role === 'handset' && session.handset === ws) {
      session.handset = null;
      broadcastJson(session.webs, {
        type: 'peer',
        peer: 'handset',
        connected: false,
        session: sessionId,
      });
    }

    if (ws.role === 'web') {
      session.webs.delete(ws);
    }

    cleanupSession(sessionId);
  });

  ws.on('error', (err) => {
    console.error('[ws error]', err.message);
  });
});

setInterval(() => {
  for (const client of wss.clients) {
    if (client.isAlive === false) {
      client.terminate();
      continue;
    }

    client.isAlive = false;
    try {
      client.ping();
    } catch (_) {}
  }
}, 15000);

httpServer.listen(HTTP_PORT, HOST, () => {
  console.log(`HTTP redirect listening on http://${HOST}:${HTTP_PORT}/`);
});

httpsServer.listen(HTTPS_PORT, HOST, () => {
  console.log(`HTTPS server listening on https://${HOST}:${HTTPS_PORT}/index.html`);
  console.log(`WSS endpoint: wss://<HOST>:${HTTPS_PORT}/ws?session=<id>`);
  console.log(`Health: https://<HOST>:${HTTPS_PORT}/healthz`);
});