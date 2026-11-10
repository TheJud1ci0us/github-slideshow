/**
 * LingoPod purchase check (Cloud Run / Cloud Functions, Node 20).
 *
 * The app sends its Play purchases and a Play Integrity token. This function asks Google
 * whether the purchases are real and active, and whether the request comes from the genuine
 * app installed from Play on a real device, then returns a short-lived "Pro pass" signed with
 * a private key only this server has. The app accepts Pro only with a valid signature, so a
 * patched app or a fake purchase gets nothing.
 *
 * Environment:
 *   PACKAGE_NAME      app.lingopod
 *   PASS_PRIVATE_KEY  the pass signing key (PKCS#8 PEM), from Secret Manager
 *   PASS_DAYS         how long a pass stays valid offline (default 7)
 * The service account running it needs access to the Play Console (see store/server-setup.md).
 */
const crypto = require('node:crypto');
const functions = require('@google-cloud/functions-framework');
const { google } = require('googleapis');

const PACKAGE = process.env.PACKAGE_NAME || 'app.lingopod';
const PASS_DAYS = Number(process.env.PASS_DAYS || 7);
const DAY = 24 * 3600 * 1000;

const auth = new google.auth.GoogleAuth({ scopes: ['https://www.googleapis.com/auth/androidpublisher', 'https://www.googleapis.com/auth/playintegrity'] });
const publisher = google.androidpublisher({ version: 'v3', auth });
const integrity = google.playintegrity({ version: 'v1', auth });

/** The pass: base64url(JSON) and its ECDSA P-256 / SHA-256 signature (DER, base64url). */
function sign(payload) {
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const sig = crypto.sign('sha256', Buffer.from(body), process.env.PASS_PRIVATE_KEY).toString('base64url');
  return { pass: body, sig };
}

/** Is the request from the genuine LingoPod, from Play, on a real device, for this nonce? */
async function checkIntegrity(token, nonce) {
  const { data } = await integrity.v1.decodeIntegrityToken({ packageName: PACKAGE, requestBody: { integrityToken: token } });
  const p = data.tokenPayloadExternal || {};
  const req = p.requestDetails || {};
  const app = p.appIntegrity || {};
  const device = (p.deviceIntegrity || {}).deviceRecognitionVerdict || [];
  if (req.requestPackageName !== PACKAGE) return 'wrong package';
  if (req.nonce !== nonce) return 'wrong nonce';
  if (Math.abs(Date.now() - Number(req.timestampMillis)) > 10 * 60 * 1000) return 'old request';
  if (app.appRecognitionVerdict !== 'PLAY_RECOGNIZED') return 'app not recognised';
  if (!device.includes('MEETS_DEVICE_INTEGRITY')) return 'device not trusted';
  return null;
}

/** Until when a purchase gives Pro (ms), or 0. */
async function activeUntil(p) {
  if (p.type === 'inapp') {
    const { data } = await publisher.purchases.products.get({ packageName: PACKAGE, productId: p.productId, token: p.token });
    return data.purchaseState === 0 ? Number.MAX_SAFE_INTEGER : 0; // 0 = purchased (not refunded)
  }
  const { data } = await publisher.purchases.subscriptionsv2.get({ packageName: PACKAGE, token: p.token });
  const ok = ['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED'].includes(data.subscriptionState);
  const expiry = Math.max(0, ...(data.lineItems || []).map((l) => Date.parse(l.expiryTime) || 0));
  return ok && expiry > Date.now() ? expiry : 0; // cancelled = still Pro until the paid period ends
}

functions.http('verify', async (req, res) => {
  try {
    if (req.method !== 'POST') return res.status(405).send('POST only');
    const { purchases = [], integrityToken, nonce, device } = req.body || {};
    if (!integrityToken || !nonce || !device) return res.status(400).json({ error: 'missing fields' });
    const problem = await checkIntegrity(integrityToken, nonce);
    if (problem) return res.status(403).json({ error: problem });
    let until = 0;
    for (const p of purchases.slice(0, 10)) {
      try { until = Math.max(until, await activeUntil(p)); } catch (e) { /* unknown token: ignore */ }
    }
    const now = Date.now();
    const payload = { pkg: PACKAGE, device, pro: until > now, iat: now, exp: Math.min(until, now + PASS_DAYS * DAY) || now + DAY };
    return res.json(sign(payload));
  } catch (e) {
    console.error(e);
    return res.status(500).json({ error: 'server error' });
  }
});

module.exports = { sign };
