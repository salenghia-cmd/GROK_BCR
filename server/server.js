const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer, WebSocket } = require('ws');

const HOST = '0.0.0.0';
const PORT = 8081;

// TRỎ ĐÚNG THƯ MỤC WEB CỦA BẠN
const WEB_DIR = 'D:\\Chuong_Trinh_Computer\\DienThoaiKetNoiMayTinh\\signaling\\web';
const INDEX_FILE = path.join(WEB_DIR, 'index.html');

function sendFile(res, filePath, contentType) {
  fs.readFile(filePath, (err, data) => {
    if (err) {
      console.error('Read file error:', filePath, err.message);
      res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Read file error: ' + filePath);
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(data);
  });
}

function getContentType(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  switch (ext) {
    case '.html': return 'text/html; charset=utf-8';
    case '.js': return 'application/javascript; charset=utf-8';
    case '.css': return 'text/css; charset=utf-8';
    case '.json': return 'application/json; charset=utf-8';
    case '.png': return 'image/png';
    case '.jpg':
    case '.jpeg': return 'image/jpeg';
    case '.svg': return 'image/svg+xml';
    case '.ico': return 'image/x-icon';
    default: return 'application/octet-stream';
  }
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  let pathname = url.pathname;

  if (pathname === '/' || pathname === '/index.html') {
    return sendFile(res, INDEX_FILE, 'text/html; charset=utf-8');
  }

  if (pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    return res.end(JSON.stringify({ ok: true }));
  }

  // phục vụ file tĩnh trong thư mục WEB_DIR
  const safePath = path.normalize(path.join(WEB_DIR, pathname));
  if (!safePath.startsWith(path.normalize(WEB_DIR))) {
    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('Forbidden');
  }

  fs.stat(safePath, (err, stat) => {
    if (!err && stat.isFile()) {
      return sendFile(res, safePath, getContentType(safePath));
    }

    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
  });
});

const wss = new WebSocketServer({ noServer: true });

const sessions = new Map();

function getSession(sessionId) {
  if (!sessions.has(sessionId)) {
    sessions.set(sessionId, {
      producer: null,
      listeners: new Set(),
      meta: null,
    });
  }
  return sessions.get(sessionId);
}

function cleanupSession(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return;
  if (!s.producer && s.listeners.size === 0) {
    sessions.delete(sessionId);
  }
}

function broadcastToListeners(sessionId, data, isBinary = false) {
  const s = sessions.get(sessionId);
  if (!s) return;

  for (const ws of s.listeners) {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(data, { binary: isBinary });
    }
  }
}

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const pathname = url.pathname;
  const sessionId = url.searchParams.get('session') || 'default';

  if (pathname !== '/out' && pathname !== '/listen') {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    socket.destroy();
    return;
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    ws._role = pathname === '/out' ? 'producer' : 'listener';
    ws._sessionId = sessionId;
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws) => {
  const sessionId = ws._sessionId;
  const role = ws._role;
  const session = getSession(sessionId);

  console.log(`[WS] ${role} connected | session=${sessionId}`);

  if (role === 'producer') {
    if (session.producer && session.producer.readyState === WebSocket.OPEN) {
      try {
        session.producer.close(4000, 'Replaced by new producer');
      } catch (_) {}
    }

    session.producer = ws;

    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        broadcastToListeners(sessionId, data, true);
        return;
      }

      const text = data.toString('utf8');
      session.meta = text;
      broadcastToListeners(sessionId, text, false);
    });

    ws.on('close', () => {
      if (session.producer === ws) session.producer = null;
      cleanupSession(sessionId);
    });

    ws.on('error', (err) => {
      console.error('Producer WS error:', err);
    });

    return;
  }

  session.listeners.add(ws);

  if (session.meta && ws.readyState === WebSocket.OPEN) {
    ws.send(session.meta);
  }

  ws.on('close', () => {
    session.listeners.delete(ws);
    cleanupSession(sessionId);
  });

  ws.on('error', (err) => {
    console.error('Listener WS error:', err);
  });
});

server.listen(PORT, HOST, () => {
  console.log(`HTTP server : http://0.0.0.0:${PORT}`);
  console.log(`WEB DIR     : ${WEB_DIR}`);
  console.log(`INDEX FILE  : ${INDEX_FILE}`);
  console.log(`WS producer : ws://0.0.0.0:${PORT}/out?session=default`);
  console.log(`WS listener : ws://0.0.0.0:${PORT}/listen?session=default`);
});