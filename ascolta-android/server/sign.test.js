// The pass format the app checks (ProPass.kt): base64url JSON, ECDSA P-256 SHA-256 DER signature.
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
test('a signed pass verifies with the public key and fails when changed', () => {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  process.env.PASS_PRIVATE_KEY = privateKey.export({ type: 'pkcs8', format: 'pem' });
  const { sign } = require('./index.js');
  const { pass, sig } = sign({ pro: true, exp: 1 });
  assert.ok(crypto.verify('sha256', Buffer.from(pass), publicKey, Buffer.from(sig, 'base64url')));
  const forged = Buffer.from(JSON.stringify({ pro: true, exp: 9e15 })).toString('base64url');
  assert.ok(!crypto.verify('sha256', Buffer.from(forged), publicKey, Buffer.from(sig, 'base64url')));
});
