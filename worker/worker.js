// Cloudflare Worker: issues LiveKit tokens and lists live rooms.
// Variables to set in Cloudflare (Settings > Variables and Secrets):
//   LK_KEY, LK_SECRET (secret), LK_WS = wss://YOUR.livekit.cloud, LK_HOST = https://YOUR.livekit.cloud
const enc = new TextEncoder();
const b64u = (buf) => {
  let s = '';
  for (const b of new Uint8Array(buf)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};
async function sign(env, claims) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64u(enc.encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' })));
  const body = b64u(enc.encode(JSON.stringify({ iss: env.LK_KEY, nbf: now - 30, exp: now + 3600, ...claims })));
  const key = await crypto.subtle.importKey('raw', enc.encode(env.LK_SECRET),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(head + '.' + body));
  return head + '.' + body + '.' + b64u(sig);
}
async function twirp(env, method, grant, payload) {
  const r = await fetch(`${env.LK_HOST}/twirp/livekit.RoomService/${method}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + (await sign(env, { video: grant })) },
    body: JSON.stringify(payload),
  });
  if (!r.ok) throw new Error(method + ' failed: ' + r.status);
  return r.json();
}
const json = (o, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { 'Content-Type': 'application/json' } });

export default {
  async fetch(req, env) {
    const u = new URL(req.url);
    try {
      if (req.method === 'GET' && u.pathname === '/rooms') {
        const d = await twirp(env, 'ListRooms', { roomList: true }, {});
        const out = (d.rooms || [])
          .filter((r) => (r.numPublishers ?? r.num_publishers ?? 0) > 0)
          .map((r) => {
            let m = {};
            try { m = JSON.parse(r.metadata || '{}'); } catch {}
            const n = r.numParticipants ?? r.num_participants ?? 1;
            return { id: r.name, title: m.title || 'Live', host: m.host || 'host', viewers: Math.max(0, n - 1) };
          });
        return json(out);
      }
      if (req.method === 'POST' && u.pathname === '/rooms') {
        const { title, host } = await req.json();
        const id = crypto.randomUUID().slice(0, 8);
        const t = String(title || 'Live').slice(0, 60);
        const h = String(host || 'guest').slice(0, 20);
        await twirp(env, 'CreateRoom', { roomCreate: true },
          { name: id, metadata: JSON.stringify({ title: t, host: h }), empty_timeout: 30 });
        const token = await sign(env, { sub: 'host-' + id, name: h,
          video: { room: id, roomJoin: true, canPublish: true, canSubscribe: true, canPublishData: true } });
        return json({ id, url: env.LK_WS, token });
      }
      if (req.method === 'GET' && u.pathname === '/token') {
        const room = u.searchParams.get('room');
        const name = (u.searchParams.get('user') || 'guest').slice(0, 20);
        if (!room) return json({ error: 'room required' }, 400);
        const token = await sign(env, { sub: 'v-' + crypto.randomUUID().slice(0, 8), name,
          video: { room, roomJoin: true, canPublish: false, canSubscribe: true, canPublishData: true } });
        return json({ url: env.LK_WS, token });
      }
      return json({ error: 'not found' }, 404);
    } catch (e) {
      return json({ error: String(e.message || e) }, 500);
    }
  },
};
