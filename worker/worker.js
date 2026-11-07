// Cloudflare Worker: issues LiveKit tokens and lists live rooms.
// Variables to set in Cloudflare (Settings > Variables and Secrets):
//   LK_KEY, LK_SECRET (secret), LK_WS = wss://YOUR.livekit.cloud, LK_HOST = https://YOUR.livekit.cloud
// Accounts: see README (KV binding DB, BREVO_KEY, MAIL_FROM)
//   (Worker > Settings > Bindings > Add > KV namespace). Without it, the app still works as guest only.
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

// Country from Cloudflare (IP based, country level only). '' when unknown.
const ccOf = (req) => {
  const c = String((req.cf && req.cf.country) || '').toUpperCase();
  return /^[A-Z]{2}$/.test(c) && c !== 'XX' ? c : '';
};

const json = (o, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { 'Content-Type': 'application/json' } });

// ---------- accounts: email + password + emailed code (needs KV namespace bound as DB, and BREVO_KEY + MAIL_FROM) ----------
// lumis the host earns per gift (emoji without the variation selector)
const GIFT_LUMIS = { '🎇': 3000, '🐘': 50, '🐱': 40, '🎻': 1000, '🍦': 15, '🧸': 20, '🎠': 15, '💋': 700, '🎡': 30, '🦄': 2000, '🗼': 2000, '🚗': 100, '🎆': 3000, '🌌': 3, '🚕': 150, '🎀': 700, '🎹': 20000, '🍔': 5, '🎧': 80, '🍷': 40, '💍': 200, '🍫': 10, '🦅': 30000, '🍩': 20, '🚢': 2, '🏰': 20000, '🗽': 1500, '🍓': 3, '🌹': 1, '🛸': 8, '🎤': 1000, '📸': 5, '🌷': 100, '🌙': 30, '🌃': 5000, '🎪': 50, '🌈': 1, '🍾': 2, '🔔': 1500, '🐉': 10000, '🏠': 200, '🎈': 50, '🍟': 10000, '🦁': 5, '🚁': 20, '🎂': 5000, '👑': 60, '🚒': 500, '🌅': 30, '🍰': 2, '🌠': 150, '🎮': 10, '🦋': 300, '❤': 10, '🌋': 300, '🍕': 80, '🐶': 3000, '🎸': 60, '🍪': 8, '🚀': 40, '🚤': 100, '☕': 200, '🍀': 15, '🎨': 8, '🌉': 1000, '🚅': 500, '⛴': 30000 };
const NAME_RE = /^[A-Za-z0-9_]{3,16}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const pbkdf2 = async (password, salt) => {
  const k = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  return b64u(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 100000 }, k, 256));
};
// Emails a 6-digit code (Brevo API). At most one per minute per address.
// At most `max` different emails per key (device or network) per `days`. The same email trying again is free.
const dailyLimit = async (env, key, max, email, days) => {
  const k = 'reg:' + key;
  const v = (await env.DB.get(k, 'json')) || { emails: [], exp: Date.now() + days * 86400000 };
  if (v.emails.includes(email)) return true;
  if (v.emails.length >= max) return false;
  v.emails.push(email);
  await env.DB.put(k, JSON.stringify(v), { expirationTtl: Math.max(60, Math.ceil((v.exp - Date.now()) / 1000)) });
  return true;
};
// Returns false (and sends nothing) while an earlier code is still valid (15 minutes); true when a new email was sent.
const sendCode = async (env, email) => {
  if (await env.DB.get('v:' + email)) return false;
  if (!env.BREVO_KEY || !env.MAIL_FROM) throw new Error('Email sending is not set up on the server (BREVO_KEY and MAIL_FROM).');
  if (await env.DB.get('rl:' + email)) throw new Error('Wait a minute before asking for another code');
  const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1000000).padStart(6, '0');
  const r = await fetch('https://api.brevo.com/v3/smtp/email', {
    method: 'POST',
    headers: { 'api-key': env.BREVO_KEY, 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify({ sender: { email: env.MAIL_FROM, name: 'Livestream' }, to: [{ email }],
      subject: 'Your verification code', textContent: 'Your Livestream verification code is ' + code + '. It expires in 15 minutes.' }),
  });
  if (!r.ok) throw new Error('Could not send the email (check BREVO_KEY and that MAIL_FROM is a verified sender)');
  await env.DB.put('v:' + email, JSON.stringify({ code, tries: 0, exp: Date.now() + 900000 }), { expirationTtl: 900 });
  await env.DB.put('rl:' + email, '1', { expirationTtl: 60 });
  return true;
};
const unb64u = (t) => Uint8Array.from(atob(t.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
const newSession = async (env, name) => {
  const p = b64u(enc.encode(JSON.stringify({ u: name, exp: Math.floor(Date.now() / 1000) + 30 * 86400 })));
  return p + '.' + (await hmac(env, 'sess:' + p));
};
// account name of a valid session in the X-Auth header, else null
const userOf = async (req, env) => {
  const t = req.headers.get('X-Auth') || '';
  const i = t.indexOf('.');
  if (i < 1) return null;
  const p = t.slice(0, i);
  if ((await hmac(env, 'sess:' + p)) !== t.slice(i + 1)) return null;
  try {
    const o = JSON.parse(new TextDecoder().decode(unb64u(p)));
    return o.exp > Date.now() / 1000 && o.u ? String(o.u) : null;
  } catch { return null; }
};
// The name to use in a room: the account name if logged in; a guest may not use a registered name (null).
const nameFor = async (req, env, wanted) => {
  const a = await userOf(req, env);
  if (a) return a;
  if (env.DB && (await env.DB.get('u:' + wanted.toLowerCase()))) return null;
  return wanted;
};
const TAKEN = { error: 'This name belongs to an account. Log in (the 👤 button) or choose another name.' };

export default {
  async fetch(req, env) {
    const u = new URL(req.url);
    try {
      if (req.method === 'POST' && ['/register', '/login', '/verify', '/resend'].includes(u.pathname)) {
        if (!env.DB) return json({ error: 'Server has no account storage yet (bind a KV namespace named DB).' }, 503);
        const b = await req.json();
        const email = String(b.email || '').trim().toLowerCase();
        if (!EMAIL_RE.test(email) || email.length > 100) return json({ error: 'Enter a valid email address' }, 400);
        const ek = 'e:' + email;
        const acc = await env.DB.get(ek, 'json');
        if (u.pathname === '/register') {
          const pw = String(b.password || '');
          const name = String(b.name || '').trim();
          if (pw.length < 6 || pw.length > 100) return json({ error: 'Password: at least 6 characters' }, 400);
          if (!NAME_RE.test(name)) return json({ error: 'Display name: 3-16 letters, numbers or _' }, 400);
          if (/^guest\d{4}$/i.test(name)) return json({ error: 'That display name is reserved for guests' }, 400);
          if (acc && acc.verified) return json({ error: 'That email already has an account. Log in instead.' }, 409);
          const owner = await env.DB.get('u:' + name.toLowerCase(), 'json');
          if (owner && owner.email !== email) return json({ error: 'That display name is taken' }, 409);
          if (acc && acc.name && acc.name.toLowerCase() !== name.toLowerCase()) await env.DB.delete('u:' + acc.name.toLowerCase());
          // one new account per device per 30 days (and a loose cap per network per day, which many phones can share)
          const dev = String(b.device || '').slice(0, 64);
          if (!dev) return json({ error: 'Update the app to register' }, 400);
          if (!(await dailyLimit(env, 'd:' + dev, 1, email, 30))) return json({ error: 'Only one account can be registered per 30 days on this phone. Try again later.' }, 429);
          if (!(await dailyLimit(env, 'i:' + (req.headers.get('CF-Connecting-IP') || 'x'), 5, email, 1))) return json({ error: 'Too many accounts were registered from this network today. Try again tomorrow.' }, 429);
          const salt = crypto.getRandomValues(new Uint8Array(16));
          await env.DB.put(ek, JSON.stringify({ name, email, salt: b64u(salt), hash: await pbkdf2(pw, salt), verified: false, created: Date.now() }));
          await env.DB.put('u:' + name.toLowerCase(), JSON.stringify({ name, email }));
          await sendCode(env, email);
          return json({ verify: true });
        }
        if (u.pathname === '/login') {
          if (!acc || (await pbkdf2(String(b.password || ''), unb64u(acc.salt))) !== acc.hash)
            return json({ error: 'Wrong email or password' }, 401);
          if (!acc.verified) { try { await sendCode(env, email); } catch {} return json({ verify: true }); }
          return json({ name: acc.name, token: await newSession(env, acc.name) });
        }
        if (u.pathname === '/resend') {
          if (!acc || acc.verified) return json({ error: 'Nothing to verify for this email' }, 400);
          const lv = await env.DB.get('v:' + email, 'json');
          if (lv && (lv.tries || 0) >= 5)
            return json({ error: 'Too many wrong codes. Try again in ' + Math.max(1, Math.ceil(((lv.exp || Date.now()) - Date.now()) / 60000)) + ' minutes.' }, 429);
          return json({ verify: true, sent: await sendCode(env, email) });
        }
        // /verify
        if (!acc) return json({ error: 'No account for this email' }, 404);
        if (!acc.verified) {
          const vk = 'v:' + email;
          const v = await env.DB.get(vk, 'json');
          if (!v) return json({ error: 'Code expired. Tap "Code expired? Get new code".' }, 400);
          const left = () => Math.max(1, Math.ceil(((v.exp || Date.now()) - Date.now()) / 60000));
          const locked = () => json({ error: 'Too many wrong codes. Try again in ' + left() + ' minutes.' }, 429);
          if ((v.tries || 0) >= 5) return locked();
          if (String(b.code || '').trim() !== v.code) {
            v.tries = (v.tries || 0) + 1;
            // keep the original expiry: a wrong code never extends the 15 minutes
            await env.DB.put(vk, JSON.stringify(v), { expirationTtl: Math.max(60, Math.ceil(((v.exp || Date.now()) - Date.now()) / 1000)) });
            return v.tries >= 5 ? locked() : json({ error: 'Wrong code (' + (5 - v.tries) + ' tries left)' }, 400);
          }
          acc.verified = true;
          await env.DB.put(ek, JSON.stringify(acc));
          await env.DB.delete(vk);
        }
        return json({ name: acc.name, token: await newSession(env, acc.name) });
      }
      if (req.method === 'GET' && u.pathname === '/version') return json({ worker: 'b111', accounts: !!env.DB, email: !!(env.BREVO_KEY && env.MAIL_FROM) });
      // Lumis: the host earns them for each gift a logged-in viewer sends (amounts are fixed here on the server)
      if (req.method === 'POST' && u.pathname === '/gift') {
        if (!env.DB) return json({ error: 'no storage' }, 503);
        const viewer = await userOf(req, env);
        if (!viewer) return json({ error: 'Log in to send gifts' }, 401);
        const b = await req.json();
        const val = GIFT_LUMIS[String(b.g || '').replace(/\uFE0F/g, '')];
        if (!val) return json({ error: 'unknown gift' }, 400);
        const d = await twirp(env, 'ListRooms', { roomList: true }, { names: [String(b.room || '')] });
        const r = (d.rooms || [])[0];
        if (!r) return json({ error: 'no room' }, 404);
        const host = String(parseMeta(r).host || '');
        if (!host || host.toLowerCase() === viewer.toLowerCase()) return json({ error: 'cannot gift this room' }, 400);
        // at most 500 gifts per viewer per day
        const ck = 'gc:' + viewer.toLowerCase();
        const c = (await env.DB.get(ck, 'json')) || { n: 0, exp: Date.now() + 86400000 };
        if (c.n >= 500) return json({ error: 'daily gift limit reached' }, 429);
        c.n++;
        await env.DB.put(ck, JSON.stringify(c), { expirationTtl: Math.max(60, Math.ceil((c.exp - Date.now()) / 1000)) });
        const lk = 'l:' + host.toLowerCase();
        const total = (parseInt(await env.DB.get(lk), 10) || 0) + val;
        await env.DB.put(lk, String(total));
        const sk = 'sp:' + viewer.toLowerCase(); // total lumis this account has spent on gifts
        await env.DB.put(sk, String((parseInt(await env.DB.get(sk), 10) || 0) + val));
        return json({ ok: true, lumis: val });
      }
      if (req.method === 'GET' && u.pathname === '/wallet') {
        const a = await userOf(req, env);
        if (!a) return json({ error: 'not logged in' }, 401);
        return json({ lumis: parseInt(await env.DB.get('l:' + a.toLowerCase()), 10) || 0 });
      }
      // Profile photos (small JPEG, base64) kept in KV per account
      if (req.method === 'POST' && u.pathname === '/avatar') {
        if (!env.DB) return json({ error: 'no storage' }, 503);
        const who = await userOf(req, env);
        if (!who) return json({ error: 'Log in to set a profile photo' }, 401);
        const { img } = await req.json();
        if (typeof img !== 'string' || img.length < 100 || img.length > 30000 || !/^[A-Za-z0-9+/=]+$/.test(img)) return json({ error: 'bad image' }, 400);
        await env.DB.put('a:' + who.toLowerCase(), img);
        return json({ ok: true });
      }
      if (req.method === 'GET' && u.pathname === '/avatar') {
        if (!env.DB) return new Response('', { status: 404 });
        const nm = String(u.searchParams.get('u') || '').toLowerCase().slice(0, 20);
        const v = nm ? await env.DB.get('a:' + nm) : null;
        if (!v) return new Response('', { status: 404 });
        const bin = atob(v);
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new Response(bytes, { headers: { 'Content-Type': 'image/jpeg', 'Cache-Control': 'public, max-age=300' } });
      }
      // ---------- posts: a photo or a short video (8 seconds max, checked in the app) ----------
      if (req.method === 'POST' && u.pathname === '/posts') { // step 1: details + small preview picture
        if (!env.DB) return json({ error: 'no storage' }, 503);
        const who = await userOf(req, env);
        if (!who) return json({ error: 'Log in to post' }, 401);
        const b = await req.json();
        const kind = b.kind === 'video' ? 'video' : (b.kind === 'text' ? 'text' : 'photo'); // text posts have no picture
        const thumb = String(b.thumb || '');
        if (kind === 'text' && !String(b.caption || '').trim()) return json({ error: 'Write something first' }, 400);
        if (kind !== 'text' && (thumb.length < 100 || thumb.length > 40000 || !/^[A-Za-z0-9+/=]+$/.test(thumb))) return json({ error: 'bad preview picture' }, 400);
        const ck = 'pc:' + who.toLowerCase(); // at most 10 posts per day
        const c = (await env.DB.get(ck, 'json')) || { n: 0, exp: Date.now() + 86400000 };
        if (c.n >= 10) return json({ error: 'You can post 10 times per day' }, 429);
        c.n++;
        await env.DB.put(ck, JSON.stringify(c), { expirationTtl: Math.max(60, Math.ceil((c.exp - Date.now()) / 1000)) });
        const id = String(9999999999999 - Date.now()).padStart(13, '0') + Math.random().toString(36).slice(2, 6);
        if (kind === 'text') { // saved right away, there is no file to upload afterwards
          const text = String(b.caption || '').trim().slice(0, 500);
          await env.DB.put('pd:' + id, text, { metadata: { k: 'text' } });
          await env.DB.put('pm:' + id, '', { metadata: { u: who, k: 'text', c: text.slice(0, 100), t: Date.now() } });
          return json({ id });
        }
        await env.DB.put('pt:' + id, thumb);
        await env.DB.put('pp:' + id, JSON.stringify({ user: who, kind, caption: String(b.caption || '').slice(0, 100) }), { expirationTtl: 3600 });
        return json({ id });
      }
      // second opinion for the poster's phone: it only asks when its own check flagged a picture
      if (req.method === 'POST' && u.pathname === '/posts/check') {
        const who = await userOf(req, env);
        if (!who) return json({ error: 'Log in to post' }, 401);
        const b = await req.json();
        const img = String(b.img || '');
        if (img.length > 80000 || !/^[A-Za-z0-9+/=]+$/.test(img)) return json({ confirmed: true });
        const sc = await nudityScore(env, img);
        return json({ confirmed: sc === null ? true : sc >= 0.6, unavailable: sc === null });
      }
      // second opinion for a viewer's phone: it only asks when its own check flagged the post. Confirmed = the post is removed for everyone.
      const pck = u.pathname.match(/^\/post\/(\w+)\/check$/);
      if (req.method === 'POST' && pck) {
        if (!env.DB) return json({ removed: false });
        const pmeta = await env.DB.getWithMetadata('pm:' + pck[1]);
        if (!pmeta.metadata) return json({ removed: false, gone: true });
        if (pmeta.metadata.v) return json({ removed: false, cleared: true }); // the server already looked at this post: not checked again
        const ip = req.headers.get('CF-Connecting-IP') || 'x';
        const rk = 'pv:' + ip;
        const cnt = parseInt(await env.DB.get(rk), 10) || 0;
        if (cnt >= 60) return json({ removed: false });
        await env.DB.put(rk, String(cnt + 1), { expirationTtl: 86400 });
        const id = pck[1];
        const b = await req.json();
        const img = String(b.img || '');
        if (img.length > 80000 || !/^[A-Za-z0-9+/=]+$/.test(img)) return json({ removed: false });
        const sc = await nudityScore(env, img);
        if (sc !== null && sc >= 0.6) {
          await env.DB.delete('pd:' + id);
          await env.DB.delete('pt:' + id);
          await env.DB.delete('pm:' + id);
          return json({ removed: true });
        }
        if (sc !== null) await env.DB.put('pm:' + id, '', { metadata: { ...pmeta.metadata, v: 1 } }); // server looked and it is fine: remember
        return json({ removed: false });
      }
      const pdm = u.pathname.match(/^\/post\/(\w+)\/data$/);
      if (req.method === 'PUT' && pdm) { // step 2: the photo or video itself
        if (!env.DB) return json({ error: 'no storage' }, 503);
        const who = await userOf(req, env);
        if (!who) return json({ error: 'Log in to post' }, 401);
        const id = pdm[1];
        const pend = await env.DB.get('pp:' + id, 'json');
        if (!pend || pend.user !== who) return json({ error: 'upload expired, try again' }, 404);
        const buf = await req.arrayBuffer();
        if (!buf.byteLength || buf.byteLength > (pend.kind === 'video' ? 12000000 : 600000)) return json({ error: 'file too large' }, 413);
        const h = new Uint8Array(buf.slice(0, 12));
        const isJpeg = h[0] === 0xff && h[1] === 0xd8;
        const isMp4 = h[4] === 0x66 && h[5] === 0x74 && h[6] === 0x79 && h[7] === 0x70;
        if (pend.kind === 'photo' ? !isJpeg : !isMp4) return json({ error: 'unsupported file (photo: JPEG, video: MP4)' }, 400);
        await env.DB.put('pd:' + id, buf, { metadata: { k: pend.kind } });
        await env.DB.put('pm:' + id, '', { metadata: { u: pend.user, k: pend.kind, c: pend.caption, t: Date.now() } });
        await env.DB.delete('pp:' + id);
        return json({ ok: true, id });
      }
      if (req.method === 'GET' && u.pathname === '/posts') {
        if (!env.DB) return json([]);
        const l = await env.DB.list({ prefix: 'pm:', limit: 60 }); // newest first (ids count down)
        return json(l.keys.map((k) => ({ id: k.name.slice(3), user: (k.metadata || {}).u || '', kind: (k.metadata || {}).k || 'photo',
          caption: (k.metadata || {}).c || '', ts: (k.metadata || {}).t || 0, v: !!(k.metadata || {}).v })));
      }
      const pgm = u.pathname.match(/^\/post\/(\w+)(\/thumb)?$/);
      if (req.method === 'GET' && pgm) {
        if (!env.DB) return new Response('', { status: 404 });
        const cache = { 'Cache-Control': 'public, max-age=86400' };
        if (pgm[2]) {
          const t = await env.DB.get('pt:' + pgm[1]);
          if (!t) return new Response('', { status: 404 });
          const bin = atob(t);
          const bytes = new Uint8Array(bin.length);
          for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
          return new Response(bytes, { headers: { 'Content-Type': 'image/jpeg', ...cache } });
        }
        const d = await env.DB.getWithMetadata('pd:' + pgm[1], 'arrayBuffer');
        if (!d.value) return new Response('', { status: 404 });
        return new Response(d.value, { headers: { 'Content-Type': (d.metadata || {}).k === 'video' ? 'video/mp4' : ((d.metadata || {}).k === 'text' ? 'text/plain; charset=utf-8' : 'image/jpeg'), ...cache } });
      }
      if (req.method === 'DELETE' && pgm && !pgm[2]) {
        if (!env.DB) return json({ error: 'no storage' }, 503);
        const who = await userOf(req, env);
        if (!who) return json({ error: 'Log in' }, 401);
        const m = await env.DB.getWithMetadata('pm:' + pgm[1]);
        if (!m.metadata || String(m.metadata.u).toLowerCase() !== who.toLowerCase()) return json({ error: 'not your post' }, 403);
        await env.DB.delete('pm:' + pgm[1]);
        await env.DB.delete('pd:' + pgm[1]);
        await env.DB.delete('pt:' + pgm[1]);
        return json({ ok: true });
      }
      // Live cover picture: set on the profile page (not while live), reused for every live of that account
      if (req.method === 'POST' && u.pathname === '/cover') {
        if (!env.DB) return json({ error: 'no storage' }, 503);
        const who = await userOf(req, env);
        if (!who) return json({ error: 'Log in to set a cover' }, 401);
        const { img } = await req.json();
        if (typeof img !== 'string' || img.length < 100 || img.length > 40000 || !/^[A-Za-z0-9+/=]+$/.test(img)) return json({ error: 'bad image' }, 400);
        const d = await twirp(env, 'ListRooms', { roomList: true }, {});
        if ((d.rooms || []).some((r) => String(parseMeta(r).host || '').toLowerCase() === who.toLowerCase()))
          return json({ error: "You can't change the cover while you are live." }, 409);
        const sc = await nudityScore(env, img);
        if (sc !== null && sc >= 0.6) return json({ error: 'This picture was refused' }, 400);
        await env.DB.put('cv:' + who.toLowerCase(), img);
        return json({ ok: true });
      }
      if (req.method === 'GET' && u.pathname === '/cover') {
        if (!env.DB) return new Response('', { status: 404 });
        const nm = String(u.searchParams.get('u') || '').toLowerCase().slice(0, 20);
        const v = nm ? await env.DB.get('cv:' + nm) : null;
        if (!v) return new Response('', { status: 404 });
        const bin = atob(v);
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new Response(bytes, { headers: { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-cache' } });
      }
      // ---------- follow graph kept on the server, so a profile can show follower / following counts ----------
      // KV: fg:<name> = JSON list of the names this account follows, fn:<name> = how many follow this account
      if (req.method === 'POST' && u.pathname === '/follow') {
        if (!env.DB) return json({ error: 'no storage' }, 503);
        const me = await userOf(req, env);
        if (!me) return json({ error: 'Log in' }, 401);
        const b = await req.json();
        const to = String(b.to || '').toLowerCase().slice(0, 20);
        const mk = me.toLowerCase();
        if (!to || to === mk) return json({ error: 'bad name' }, 400);
        const list = (await env.DB.get('fg:' + mk, 'json')) || [];
        const has = list.includes(to);
        if (b.on && !has) {
          if (!(await env.DB.get('u:' + to))) return json({ error: 'No such account' }, 404);
          if (list.length >= 500) return json({ error: 'You can follow up to 500 people' }, 400);
          list.push(to);
          await env.DB.put('fg:' + mk, JSON.stringify(list));
          await env.DB.put('fn:' + to, String((parseInt(await env.DB.get('fn:' + to), 10) || 0) + 1));
        } else if (!b.on && has) {
          await env.DB.put('fg:' + mk, JSON.stringify(list.filter((x) => x !== to)));
          await env.DB.put('fn:' + to, String(Math.max(0, (parseInt(await env.DB.get('fn:' + to), 10) || 0) - 1)));
        }
        return json({ ok: true });
      }
      // one-time upload of the follows that were saved only on the phone
      if (req.method === 'POST' && u.pathname === '/followsync') {
        if (!env.DB) return json({ error: 'no storage' }, 503);
        const me = await userOf(req, env);
        if (!me) return json({ error: 'Log in' }, 401);
        const b = await req.json();
        const mk = me.toLowerCase();
        const list = (await env.DB.get('fg:' + mk, 'json')) || [];
        let added = 0;
        for (const raw of (Array.isArray(b.names) ? b.names : []).slice(0, 100)) {
          const to = String(raw || '').toLowerCase().slice(0, 20);
          if (!to || to === mk || list.includes(to) || list.length >= 500) continue;
          if (!(await env.DB.get('u:' + to))) continue;
          list.push(to);
          added++;
          await env.DB.put('fn:' + to, String((parseInt(await env.DB.get('fn:' + to), 10) || 0) + 1));
        }
        if (added) await env.DB.put('fg:' + mk, JSON.stringify(list));
        return json({ ok: true, added });
      }
      // public profile numbers + that person's latest posts
      if (req.method === 'GET' && u.pathname === '/profile') {
        if (!env.DB) return json({ followers: 0, following: 0, spent: 0, received: 0, postCount: 0, posts: [] });
        const nm = String(u.searchParams.get('u') || '').toLowerCase().slice(0, 20);
        if (!nm) return json({ error: 'name?' }, 400);
        const followers = parseInt(await env.DB.get('fn:' + nm), 10) || 0;
        const following = ((await env.DB.get('fg:' + nm, 'json')) || []).length;
        const spent = parseInt(await env.DB.get('sp:' + nm), 10) || 0;
        const received = parseInt(await env.DB.get('l:' + nm), 10) || 0; // lumis earned as a host
        const l = await env.DB.list({ prefix: 'pm:', limit: 1000 }); // newest first
        const mine = l.keys.filter((k) => String((k.metadata || {}).u || '').toLowerCase() === nm);
        return json({ followers, following, spent, received, postCount: mine.length, posts: mine.slice(0, 12).map((k) => ({
          id: k.name.slice(3), kind: (k.metadata || {}).k || 'photo', caption: (k.metadata || {}).c || '', ts: (k.metadata || {}).t || 0, v: !!(k.metadata || {}).v })) });
      }
      if (req.method === 'GET' && u.pathname === '/me') {
        const a = await userOf(req, env);
        return a ? json({ name: a }) : json({ error: 'not logged in' }, 401);
      }
      if (req.method === 'GET' && u.pathname === '/rooms') {
        const d = await twirp(env, 'ListRooms', { roomList: true }, {});
        const out = (d.rooms || [])
          .filter((r) => (r.numPublishers ?? r.num_publishers ?? 0) > 0)
          .map((r) => {
            let m = {};
            try { m = JSON.parse(r.metadata || '{}'); } catch {}
            const n = r.numParticipants ?? r.num_participants ?? 1;
            return { id: r.name, title: m.title || 'Live', host: m.host || 'host', viewers: Math.max(0, n - 1), thumb: m.thumb || '', type: m.kind === 'audio' ? 'audio' : (m.type || ''), kind: m.kind || 'live', ratio: m.ratio || 'auto', cc: m.cc || '', same: !!m.cc && m.cc === ccOf(req) };
          });
        return json(out);
      }
      if (req.method === 'POST' && u.pathname === '/rooms') {
        const { title, host, kind, ratio } = await req.json();
        const k = kind === 'audio' ? 'audio' : 'live';
        const rr = ['9:16', '16:9', '1:1'].includes(ratio) ? ratio : 'auto';
        const id = crypto.randomUUID().slice(0, 8);
        const t = String(title || 'Live').slice(0, 60);
        const h = await userOf(req, env); // only logged-in accounts can host
        if (!h) return json({ error: 'Log in to go live (the 👤 button on Home).' }, 401);
        await twirp(env, 'CreateRoom', { roomCreate: true },
          { name: id, metadata: JSON.stringify({ title: t, host: h, kind: k, ratio: rr, cc: ccOf(req) }), empty_timeout: 30 });
        const token = await sign(env, { sub: 'host-' + id, name: h,
          video: { room: id, roomJoin: true, canPublish: true, canSubscribe: true, canPublishData: true,
            canPublishSources: k === 'audio' ? ['microphone', 'screen_share', 'screen_share_audio'] : ['camera', 'microphone'] } });
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
        if (['talk', 'group', 'scene', 'pk', 'screen'].includes(type)) m.type = type;
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
        const name = await userOf(req, env); // only logged-in accounts can join a PK as a guest
        if (!name) return json({ error: 'Log in to join a PK (the 👤 button on Home).' }, 401);
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
        const acct = await userOf(req, env);
        const name = acct || (await nameFor(req, env, (u.searchParams.get('user') || 'guest').slice(0, 20)));
        if (name === null) return json(TAKEN, 403);
        if (!room) return json({ error: 'room required' }, 400);
        // guests can watch; only logged-in accounts can send chat and gifts
        const token = await sign(env, { sub: 'v-' + crypto.randomUUID().slice(0, 8), name,
          video: { room, roomJoin: true, canPublish: false, canSubscribe: true, canPublishData: !!acct } });
        return json({ url: env.LK_WS, token });
      }
      return json({ error: 'not found' }, 404);
    } catch (e) {
      return json({ error: String(e.message || e) }, 500);
    }
  },
};
