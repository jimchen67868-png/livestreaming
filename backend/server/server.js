const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const rooms = new Map(); // id -> {id,title,host,created,viewers:Set,hostWs}
const send = (res, code, obj) => {
  res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
  res.end(JSON.stringify(obj));
};
const pub = r => ({ id: r.id, title: r.title, host: r.host, viewers: r.viewers.size });
const everyone = r => [...r.viewers, ...(r.hostWs ? [r.hostWs] : [])];
const broadcast = (r, msg) => everyone(r).forEach(c => c.readyState === 1 && c.send(JSON.stringify(msg)));

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/rooms')
    return send(res, 200, [...rooms.values()].filter(r => r.hostWs).map(pub));
  if (req.method === 'POST' && req.url === '/rooms') {
    let body = '';
    req.on('data', d => { body += d; if (body.length > 4096) req.destroy(); });
    req.on('end', () => {
      try {
        const { title, host } = JSON.parse(body);
        const id = crypto.randomBytes(4).toString('hex');
        rooms.set(id, { id, title: String(title || 'Live').slice(0, 60), host: String(host || 'guest').slice(0, 20),
          created: Date.now(), viewers: new Set(), hostWs: null });
        send(res, 201, { id });
      } catch { send(res, 400, { error: 'bad request' }); }
    });
    return;
  }
  send(res, 404, { error: 'not found' });
});

const wss = new WebSocketServer({ server });
wss.on('connection', (ws, req) => {
  const q = new URL(req.url, 'http://x').searchParams;
  const room = rooms.get(q.get('room'));
  const user = (q.get('user') || 'guest').slice(0, 20);
  const isHost = q.get('host') === '1';
  if (!room) return ws.close(4004, 'no room');
  ws.isAlive = true;
  ws.on('pong', () => (ws.isAlive = true));
  if (isHost) room.hostWs = ws; else room.viewers.add(ws);
  broadcast(room, { type: 'viewers', count: room.viewers.size });

  ws.on('message', raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'chat' && typeof m.text === 'string' && m.text.trim())
      broadcast(room, { type: 'chat', user, text: m.text.slice(0, 200) });
    else if (m.type === 'gift')
      broadcast(room, { type: 'gift', user, gift: String(m.gift).slice(0, 8) });
  });
  ws.on('close', () => {
    if (isHost) { broadcast(room, { type: 'end' }); rooms.delete(room.id); }
    else { room.viewers.delete(ws); broadcast(room, { type: 'viewers', count: room.viewers.size }); }
  });
});

setInterval(() => {
  wss.clients.forEach(c => { if (!c.isAlive) return c.terminate(); c.isAlive = false; c.ping(); });
  for (const r of rooms.values()) if (!r.hostWs && Date.now() - r.created > 30000) rooms.delete(r.id);
}, 15000);

server.listen(3000, () => console.log('API on :3000'));
