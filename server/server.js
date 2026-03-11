const http = require('http');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');
const { WebSocketServer, WebSocket } = require('ws');

const HOST = '0.0.0.0';
const PORT = 8081;

const WEB_DIR = __dirname;
const INDEX_FILE = path.join(WEB_DIR, 'index.html');

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
};

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

function sendJson(res, statusCode, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': '*',
  });
  res.end(body);
}

function serveFile(res, filePath) {
  fs.readFile(filePath, (err, data) => {
    if (err) {
      sendJson(res, 404, { ok: false, error: 'File not found' });
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    res.writeHead(200, {
      'Content-Type': contentType,
      'Content-Length': data.length,
      'Cache-Control': 'no-cache',
      'Access-Control-Allow-Origin': '*',
    });
    res.end(data);
  });
}

const httpServer = http.createServer((req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host}`);

    if (url.pathname === '/health') {
      const snapshot = {};
      for (const [sessionId, s] of sessions.entries()) {
        snapshot[sessionId] = {
          hasProducer: !!s.producer,
          listeners: s.listeners.size,
        };
      }

      return sendJson(res, 200, {
        ok: true,
        host: HOST,
        port: PORT,
        sessions: snapshot,
      });
    }

    let requestedPath = url.pathname;
    if (requestedPath === '/') {
      requestedPath = '/index.html';
    }

    const safePath = path.normalize(requestedPath).replace(/^(\.\.[/\\])+/, '');
    const fullPath = path.join(WEB_DIR, safePath);

    if (!fullPath.startsWith(WEB_DIR)) {
      return sendJson(res, 403, { ok: false, error: 'Forbidden' });
    }

    if (fs.existsSync(fullPath) && fs.statSync(fullPath).isFile()) {
      return serveFile(res, fullPath);
    }

    if (path.basename(fullPath) === 'index.html' && fs.existsSync(INDEX_FILE)) {
      return serveFile(res, INDEX_FILE);
    }

    return sendJson(res, 404, { ok: false, error: 'Not found' });
  } catch (err) {
    log('HTTP error:', err);
    return sendJson(res, 500, { ok: false, error: 'Internal server error' });
  }
});

const wss = new WebSocketServer({ server: httpServer });

const sessions = new Map();

function getSession(sessionId) {
  let session = sessions.get(sessionId);
  if (!session) {
    session = {
      producer: null,
      listeners: new Set(),
    };
    sessions.set(sessionId, session);
  }
  return session;
}

function cleanupSessionIfEmpty(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return;
  if (!s.producer && s.listeners.size === 0) {
    sessions.delete(sessionId);
  }
}

function broadcastTextToListeners(sessionId, message) {
  const s = sessions.get(sessionId);
  if (!s) return;

  for (const ws of s.listeners) {
    if (ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(message);
      } catch (e) {
        log(`[${sessionId}] send text to listener failed:`, e.message);
      }
    }
  }
}

function broadcastBinaryToListeners(sessionId, data) {
  const s = sessions.get(sessionId);
  if (!s) return;

  let sent = 0;
  for (const ws of s.listeners) {
    if (ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(data, { binary: true });
        sent++;
      } catch (e) {
        log(`[${sessionId}] send binary to listener failed:`, e.message);
      }
    }
  }
  return sent;
}

wss.on('connection', (ws, req) => {
  let url;
  try {
    url = new URL(req.url, `http://${req.headers.host}`);
  } catch (e) {
    ws.close(1008, 'Bad URL');
    return;
  }

  const pathname = url.pathname;
  const sessionId = (url.searchParams.get('session') || 'default').trim() || 'default';
  const remoteIp =
    req.headers['x-forwarded-for'] ||
    req.socket.remoteAddress ||
    'unknown';

  ws.isAlive = true;
  ws.role = 'unknown';
  ws.sessionId = sessionId;

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  if (pathname === '/out') {
    const session = getSession(sessionId);

    if (session.producer && session.producer !== ws) {
      try {
        session.producer.close(1012, 'Replaced by new producer');
      } catch (_) {}
    }

    session.producer = ws;
    ws.role = 'producer';

    log(`[${sessionId}] producer connected from ${remoteIp}`);

    try {
      ws.send(JSON.stringify({
        type: 'welcome',
        role: 'producer',
        session: sessionId,
      }));
    } catch (_) {}

    broadcastTextToListeners(sessionId, JSON.stringify({
      type: 'producer_status',
      online: true,
      session: sessionId,
    }));

    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        const count = broadcastBinaryToListeners(sessionId, data);
        return;
      }

      const text = data.toString();
      if (text === 'PING') {
        try {
          ws.send('PONG');
        } catch (_) {}
        return;
      }

      broadcastTextToListeners(sessionId, JSON.stringify({
        type: 'producer_text',
        session: sessionId,
        text,
      }));
    });

    ws.on('close', (code, reason) => {
      const s = sessions.get(sessionId);
      if (s && s.producer === ws) {
        s.producer = null;
      }

      log(`[${sessionId}] producer disconnected code=${code} reason=${reason || ''}`);

      broadcastTextToListeners(sessionId, JSON.stringify({
        type: 'producer_status',
        online: false,
        session: sessionId,
      }));

      cleanupSessionIfEmpty(sessionId);
    });

    ws.on('error', (err) => {
      log(`[${sessionId}] producer error:`, err.message);
    });

    return;
  }

  if (pathname === '/listen') {
    const session = getSession(sessionId);
    session.listeners.add(ws);
    ws.role = 'listener';

    log(`[${sessionId}] listener connected from ${remoteIp} total=${session.listeners.size}`);

    try {
      ws.send(JSON.stringify({
        type: 'welcome',
        role: 'listener',
        session: sessionId,
        producerOnline: !!session.producer,
        listeners: session.listeners.size,
      }));
    } catch (_) {}

    ws.on('message', (data, isBinary) => {
      if (!isBinary) {
        const text = data.toString();
        if (text === 'PING') {
          try {
            ws.send('PONG');
          } catch (_) {}
        }
      }
    });

    ws.on('close', (code, reason) => {
      const s = sessions.get(sessionId);
      if (s) {
        s.listeners.delete(ws);
      }

      log(
        `[${sessionId}] listener disconnected code=${code} reason=${reason || ''} total=${s ? s.listeners.size : 0}`
      );

      cleanupSessionIfEmpty(sessionId);
    });

    ws.on('error', (err) => {
      log(`[${sessionId}] listener error:`, err.message);
    });

    return;
  }

  ws.close(1008, 'Unsupported path');
});

const heartbeat = setInterval(() => {
  for (const client of wss.clients) {
    if (client.isAlive === false) {
      try {
        client.terminate();
      } catch (_) {}
      continue;
    }

    client.isAlive = false;
    try {
      client.ping();
    } catch (_) {}
  }
}, 15000);

wss.on('close', () => {
  clearInterval(heartbeat);
});

httpServer.listen(PORT, HOST, () => {
  log(`HTTP/WebSocket server running at http://${HOST}:${PORT}`);
  log(`Open on LAN: http://<IP_MAY_TINH>:${PORT}`);
  log(`Health check: http://127.0.0.1:${PORT}/health`);
  log(`Producer path : ws://<IP_MAY_TINH>:${PORT}/out?session=default`);
  log(`Listener path : ws://<IP_MAY_TINH>:${PORT}/listen?session=default`);
});