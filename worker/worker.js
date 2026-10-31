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

// ---------- moderation ----------
const parseMeta = (r) => { try { return JSON.parse(r.metadata || '{}'); } catch { return {}; } };
async function getRoom(env, id) {
  const d = await twirp(env, 'ListRooms', { roomList: true }, { names: [id] });
  return (d.rooms || [])[0];
}
const updateMeta = (env, id, m) =>
  twirp(env, 'UpdateRoomMetadata', { roomAdmin: true, room: id }, { room: id, metadata: JSON.stringify(m) });
async function sendData(env, room, identities, obj) {
  const data = btoa(String.fromCharCode(...enc.encode(JSON.stringify(obj))));
  await twirp(env, 'SendData', { roomAdmin: true, room }, { room, data, kind: 'RELIABLE', destination_identities: identities });
}
async function guestIdentity(env, room) {
  const d = await twirp(env, 'ListParticipants', { roomAdmin: true, room }, { room });
  const p = (d.participants || []).find((x) => String(x.identity).startsWith('guest-'));
  return p ? p.identity : null;
}
// Returns 0..1 (higher = more explicit), or null if moderation is off / unavailable.
async function nudityScore(env, b64) {
  if (!env.SE_USER || !env.SE_SECRET || typeof b64 !== 'string' || !b64) return null;
  try {
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    const form = new FormData();
    form.append('media', new Blob([bytes], { type: 'image/jpeg' }), 'f.jpg');
    form.append('models', 'nudity-2.1');
    form.append('api_user', env.SE_USER);
    form.append('api_secret', env.SE_SECRET);
    const res = await fetch('https://api.sightengine.com/1.0/check.json', { method: 'POST', body: form });
    const d = await res.json();
    if (d.status !== 'success') return null;
    const n = d.nudity || {};
    return Math.max(n.sexual_activity || 0, n.sexual_display || 0, n.erotica || 0);
  } catch { return null; }
}
// score >= 0.8, or a second offence: end it. Otherwise warn.
async function flag(env, id, who, score, m) {
  const key = who === 'guest' ? 'gs' : 'hs';
  const strikes = m[key] || 0;
  const target = who === 'guest' ? await guestIdentity(env, id) : 'host-' + id;
  if (score >= 0.8 || strikes >= 1) {
    if (target) await sendData(env, id, [target], { t: 'ended', why: 'policy' });
    if (who === 'guest') { if (target) await twirp(env, 'RemoveParticipant', { roomAdmin: true, room: id }, { room: id, identity: target }); }
    else await twirp(env, 'DeleteRoom', { roomCreate: true }, { room: id });
    return 'ended';
  }
  m[key] = strikes + 1;
  if (who === 'host') m.thumb = '';
  await updateMeta(env, id, m);
  if (target) await sendData(env, id, [target], { t: 'warn' });
  return 'warned';
}

const hmac = async (env, msg) => {
  const key = await crypto.subtle.importKey('raw', enc.encode(env.LK_SECRET),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return b64u(await crypto.subtle.sign('HMAC', key, enc.encode(msg)));
};
const authed = async (req, env, scope, id) => req.headers.get('X-Key') === (await hmac(env, scope + ':' + id));

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
            return { id: r.name, title: m.title || 'Live', host: m.host || 'host', viewers: Math.max(0, n - 1), thumb: m.thumb || '', type: m.type || '' };
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
        return json({ id, url: env.LK_WS, token, key: await hmac(env, 'host:' + id) });
      }
      const mt = u.pathname.match(/^\/rooms\/([\w-]+)\/thumb$/);
      if (req.method === 'POST' && mt) {
        const id = mt[1];
        if (!(await authed(req, env, 'host', id))) return json({ error: 'forbidden' }, 403);
        const { thumb, type } = await req.json();
        if (typeof thumb !== 'string' || thumb.length > 40000) return json({ error: 'bad thumb' }, 400);
        const r = await getRoom(env, id);
        if (!r) return json({ error: 'no room' }, 404);
        const m = parseMeta(r);
        m.thumb = thumb;
        if (['talk', 'group', 'scene', 'pk'].includes(type)) m.type = type;
        await updateMeta(env, id, m);
        return json({ ok: true });
      }
      // Local detector flagged a frame: confirm with Sightengine.
      const mc = u.pathname.match(/^\/rooms\/([\w-]+)\/check$/);
      if (req.method === 'POST' && mc) {
        const id = mc[1];
        const { img, who } = await req.json();
        const role = who === 'guest' ? 'guest' : 'host';
        if (!(await authed(req, env, role, id))) return json({ error: 'forbidden' }, 403);
        const r = await getRoom(env, id);
        if (!r) return json({ error: 'no room' }, 404);
        const score = await nudityScore(env, img);
        if (score !== null && score >= 0.5)
          return json({ ok: true, clean: false, action: await flag(env, id, role, score, parseMeta(r)) });
        return json({ ok: true, clean: true });
      }
      const mi = u.pathname.match(/^\/rooms\/([\w-]+)\/invite$/);
      if (req.method === 'POST' && mi) {
        const to = mi[1];
        const { room, name } = await req.json();
        const from = String(room);
        if (!(await authed(req, env, 'host', from))) return json({ error: 'forbidden' }, 403);
        const d = await twirp(env, 'ListRooms', { roomList: true }, { names: [from, to] });
        const rf = (d.rooms || []).find((r) => r.name === from);
        const rt = (d.rooms || []).find((r) => r.name === to);
        if (!rf || !rt) return json({ error: 'room gone' }, 404);
        let m = {};
        try { m = JSON.parse(rf.metadata || '{}'); } catch {}
        m.invited = to;
        await twirp(env, 'UpdateRoomMetadata', { roomAdmin: true, room: from },
          { room: from, metadata: JSON.stringify(m) });
        const msg = JSON.stringify({ t: 'pk_invite', room: from, name: String(name || 'host').slice(0, 20) });
        const data = btoa(String.fromCharCode(...enc.encode(msg)));
        await twirp(env, 'SendData', { roomAdmin: true, room: to },
          { room: to, data, kind: 'RELIABLE', destination_identities: ['host-' + to] });
        return json({ ok: true });
      }
      if (req.method === 'GET' && u.pathname === '/guest') {
        const room = u.searchParams.get('room');
        const from = u.searchParams.get('from');
        const name = (u.searchParams.get('user') || 'guest').slice(0, 20);
        const d = await twirp(env, 'ListRooms', { roomList: true }, { names: [room] });
        const r = (d.rooms || [])[0];
        if (!r) return json({ error: 'no room' }, 404);
        let m = {};
        try { m = JSON.parse(r.metadata || '{}'); } catch {}
        if (!from || m.invited !== from) return json({ error: 'not invited' }, 403);
        const token = await sign(env, { sub: 'guest-' + crypto.randomUUID().slice(0, 8), name,
          video: { room, roomJoin: true, canPublish: true, canSubscribe: true, canPublishData: true } });
        return json({ url: env.LK_WS, token, key: await hmac(env, 'guest:' + room) });
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
