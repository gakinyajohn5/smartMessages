import express from 'express';
import Database from 'better-sqlite3';
import crypto from 'node:crypto';
import rateLimit from 'express-rate-limit';

const ADMIN_KEY = process.env.ADMIN_KEY || '';
if (ADMIN_KEY.length < 16) {
  console.error('Set ADMIN_KEY to a secret of at least 16 characters.');
  process.exit(1);
}

const db = new Database(process.env.DB_PATH || 'data.db');
db.pragma('journal_mode = WAL');
db.exec(`
  CREATE TABLE IF NOT EXISTS licenses (
    code TEXT PRIMARY KEY,
    device_id TEXT,
    note TEXT,
    revoked INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    activated_at INTEGER
  );
  CREATE TABLE IF NOT EXISTS topups (
    code TEXT PRIMARY KEY,
    license_code TEXT NOT NULL REFERENCES licenses(code),
    amount_cents INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    redeemed_at INTEGER,
    redeemed_device TEXT
  );
`);

// 32 characters, no 0/O/1/I so codes are easy to read out and type.
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
function newCode() {
  const bytes = crypto.randomBytes(12);
  let s = '';
  for (const b of bytes) s += ALPHABET[b % 32];
  return `${s.slice(0, 4)}-${s.slice(4, 8)}-${s.slice(8, 12)}`;
}
const normalize = (c) => String(c ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '');
const pretty = (n) => `${n.slice(0, 4)}-${n.slice(4, 8)}-${n.slice(8, 12)}`;
// Codes are stored as typed-friendly "XXXX-XXXX-XXXX"; lookups use the normalized form.
const keyOf = (input) => {
  const n = normalize(input);
  return n.length === 12 ? pretty(n) : null;
};
const validDevice = (d) => typeof d === 'string' && d.length >= 8 && d.length <= 128;

const app = express();
app.disable('x-powered-by');
if (process.env.TRUST_PROXY) app.set('trust proxy', 1);
app.use(express.json({ limit: '4kb' }));

// ---------- Admin (you) ----------
function adminOnly(req, res, next) {
  const given = Buffer.from(String(req.get('x-admin-key') ?? ''));
  const want = Buffer.from(ADMIN_KEY);
  if (given.length !== want.length || !crypto.timingSafeEqual(given, want)) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
}
const admin = express.Router();
admin.use(adminOnly);

// Create activation codes to sell: { count?: 1-100, note?: "Buyer name" }
admin.post('/licenses', (req, res) => {
  const count = Math.min(Math.max(parseInt(req.body?.count ?? 1, 10) || 1, 1), 100);
  const note = String(req.body?.note ?? '').slice(0, 100);
  const ins = db.prepare('INSERT INTO licenses (code, note, created_at) VALUES (?, ?, ?)');
  const codes = [];
  db.transaction(() => {
    for (let i = 0; i < count; i++) {
      const c = newCode();
      ins.run(c, note, Date.now());
      codes.push(c);
    }
  })();
  res.json({ codes });
});

// Issue top-up codes for one buyer: { licenseCode, amount (Ksh game money), count?: 1-50 }
admin.post('/topups', (req, res) => {
  const lic = keyOf(req.body?.licenseCode);
  const amount = Number(req.body?.amount);
  const count = Math.min(Math.max(parseInt(req.body?.count ?? 1, 10) || 1, 1), 50);
  if (!lic || !db.prepare('SELECT 1 FROM licenses WHERE code = ?').get(lic)) {
    return res.status(404).json({ error: 'Unknown license code' });
  }
  if (!Number.isFinite(amount) || amount <= 0 || amount > 100_000_000) {
    return res.status(400).json({ error: 'Invalid amount' });
  }
  const cents = Math.round(amount * 100);
  const ins = db.prepare(
    'INSERT INTO topups (code, license_code, amount_cents, created_at) VALUES (?, ?, ?, ?)');
  const codes = [];
  db.transaction(() => {
    for (let i = 0; i < count; i++) {
      const c = newCode();
      ins.run(c, lic, cents, Date.now());
      codes.push(c);
    }
  })();
  res.json({ licenseCode: lic, amount, codes });
});

// Let a buyer move to a new phone.
admin.post('/licenses/:code/unbind', (req, res) => {
  const r = db.prepare('UPDATE licenses SET device_id = NULL, activated_at = NULL WHERE code = ?')
    .run(keyOf(req.params.code) ?? '');
  res.status(r.changes ? 200 : 404).json(r.changes ? { ok: true } : { error: 'Unknown license code' });
});

admin.post('/licenses/:code/revoke', (req, res) => {
  const r = db.prepare('UPDATE licenses SET revoked = 1 WHERE code = ?')
    .run(keyOf(req.params.code) ?? '');
  res.status(r.changes ? 200 : 404).json(r.changes ? { ok: true } : { error: 'Unknown license code' });
});

admin.get('/licenses', (_req, res) => {
  const rows = db.prepare(`
    SELECT l.code, l.note, l.revoked, l.created_at, l.activated_at,
           (l.device_id IS NOT NULL) AS bound,
           COALESCE(SUM(CASE WHEN t.redeemed_at IS NOT NULL THEN t.amount_cents END), 0) AS redeemed_cents,
           COALESCE(SUM(CASE WHEN t.redeemed_at IS NULL THEN t.amount_cents END), 0) AS pending_cents
    FROM licenses l LEFT JOIN topups t ON t.license_code = l.code
    GROUP BY l.code ORDER BY l.created_at DESC LIMIT 500`).all();
  res.json({ licenses: rows });
});
app.use('/admin', admin);

// ---------- App (buyers' phones) ----------
const api = express.Router();
api.use(rateLimit({ windowMs: 15 * 60 * 1000, limit: 30, standardHeaders: true, legacyHeaders: false,
  message: { error: 'Too many attempts. Try again in a few minutes.' } }));

// Binds a license to the first phone that uses it. Same phone again = fine.
api.post('/activate', (req, res) => {
  const code = keyOf(req.body?.code);
  const device = req.body?.deviceId;
  if (!code || !validDevice(device)) return res.status(400).json({ error: 'Enter a valid activation code' });

  const result = db.transaction(() => {
    const lic = db.prepare('SELECT * FROM licenses WHERE code = ?').get(code);
    if (!lic || lic.revoked) return { status: 404, body: { error: 'That code is not valid' } };
    if (lic.device_id && lic.device_id !== device) {
      return { status: 403, body: { error: 'This code is already used on another phone' } };
    }
    if (!lic.device_id) {
      db.prepare('UPDATE licenses SET device_id = ?, activated_at = ? WHERE code = ?')
        .run(device, Date.now(), code);
    }
    return { status: 200, body: { ok: true, licenseCode: code } };
  })();
  res.status(result.status).json(result.body);
});

// Redeems a top-up code for the phone that owns the license. One use only.
api.post('/redeem', (req, res) => {
  const licCode = keyOf(req.body?.licenseCode);
  const topCode = keyOf(req.body?.code);
  const device = req.body?.deviceId;
  if (!licCode || !topCode || !validDevice(device)) {
    return res.status(400).json({ error: 'Enter a valid top-up code' });
  }
  const result = db.transaction(() => {
    const lic = db.prepare('SELECT * FROM licenses WHERE code = ?').get(licCode);
    if (!lic || lic.revoked || lic.device_id !== device) {
      return { status: 403, body: { error: 'This phone is not activated' } };
    }
    const t = db.prepare('SELECT * FROM topups WHERE code = ? AND license_code = ?').get(topCode, licCode);
    if (!t) return { status: 404, body: { error: 'That code is not valid' } };
    if (t.redeemed_at) {
      // Same phone retrying after a lost response: report it so the app can recover.
      if (t.redeemed_device === device) {
        return { status: 200, body: { ok: true, amountCents: t.amount_cents, replay: true } };
      }
      return { status: 409, body: { error: 'That code has already been used' } };
    }
    db.prepare('UPDATE topups SET redeemed_at = ?, redeemed_device = ? WHERE code = ?')
      .run(Date.now(), device, topCode);
    return { status: 200, body: { ok: true, amountCents: t.amount_cents } };
  })();
  res.status(result.status).json(result.body);
});
app.use('/api', api);

app.get('/health', (_req, res) => res.json({ ok: true }));

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`Pesa Board server on :${port}`));
