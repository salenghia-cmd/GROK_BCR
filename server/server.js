const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');
const WebSocket = require('ws');

const HOST = '0.0.0.0';
const PORT = Number(process.env.PORT || 8081);
const WEB_ROOT = __dirname;
const INDEX_FILE = path.join(WEB_ROOT, 'index.html');

const sessions = new Map();

function getSession(sessionId) {
  if (!sessions.has(sessionId)) {
    sessions.set(sessionId, {
      handset: null,
      webs: new Set(),
      meta: {
        sampleRate: 8000,
        channels: 1,
        bits: 16,
        format: 'pcm_s16le',
      },
      lastState: null,
      createdAt: Date.now(),
    });
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

const server = http.createServer((req, res) => {
  const parsed = url.parse(req.url);
  let pathname = parsed.pathname || '/';
  if (pathname === '/' || pathname === '/index.htm') pathname = '/index.html';

  if (pathname === '/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    fs.createReadStream(INDEX_FILE).pipe(res);
    return;
  }

  if (pathname === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: true, sessions: sessions.size }));
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Not found');
});

const wss = new WebSocket.Server({
  noServer: true,
  perMessageDeflate: false,
  maxPayload: 1024 * 1024,
});

server.on('upgrade', (req, socket, head) => {
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

      if (ws.role === 'handset') {
        for (const peer of session.webs) {
          if (peer.readyState === WebSocket.OPEN) peer.send(data, { binary: true });
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

      if (role === 'handset') {
        if (session.handset && session.handset !== ws) {
          try { session.handset.close(4000, 'replaced by new handset'); } catch (_) {}
        }
        session.handset = ws;
        session.meta.sampleRate = Number(msg.sampleRate || session.meta.sampleRate || 8000);
        session.meta.channels = Number(msg.channels || session.meta.channels || 1);
        session.meta.bits = Number(msg.bits || session.meta.bits || 16);
        session.meta.format = String(msg.format || session.meta.format || 'pcm_s16le');
      } else {
        session.webs.add(ws);
      }

      sendJson(ws, {
        type: 'hello_ack',
        role,
        session: sessionId,
        sampleRate: session.meta.sampleRate,
        channels: session.meta.channels,
        bits: session.meta.bits,
        format: session.meta.format,
      });

      if (session.lastState) sendJson(ws, session.lastState);
      if (role === 'web' && session.handset) {
        sendJson(ws, { type: 'peer', peer: 'handset', connected: true, session: sessionId });
      }
      if (role === 'handset' && session.webs.size > 0) {
        broadcastJson(session.webs, { type: 'peer', peer: 'handset', connected: true, session: sessionId });
      }

      console.log(`[hello] role=${role} session=${sessionId}`);
      return;
    }

    if (!ws.sessionId) return;
    const session = getSession(ws.sessionId);

    if (msg.type === 'ping') {
      sendJson(ws, { type: 'pong', session: ws.sessionId, ts: Date.now() });
      return;
    }

    if (msg.type === 'pong') return;

    if (msg.type === 'state') {
      session.lastState = msg;
      const peers = [];
      if (session.handset && session.handset !== ws) peers.push(session.handset);
      for (const peer of session.webs) if (peer !== ws) peers.push(peer);
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
      broadcastJson(session.webs, { type: 'peer', peer: 'handset', connected: false, session: sessionId });
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

server.listen(PORT, HOST, () => {
  console.log(`HTTP/WebSocket server listening on http://${HOST}:${PORT}/index.html`);
  console.log(`WebSocket endpoint: ws://<LAN-IP>:${PORT}/ws?session=<session-id>`);
});