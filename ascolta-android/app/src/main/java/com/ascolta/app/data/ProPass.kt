package com.ascolta.app.data

import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The "Pro pass": a small statement signed by LingoPod's purchase-check server ("this device
 * has Pro until …"). Only the server has the private key; the app checks the signature with the
 * public key built into it, so a patched flag or a made-up pass is refused (pure, unit-tested).
 *
 * Format: base64url(JSON) + "." + base64url(ECDSA P-256 / SHA-256 signature, DER).
 */
object ProPass {
    data class Claims(val pkg: String, val device: String, val pro: Boolean, val expiresAt: Long)

    /** The pass's claims if the signature is right, it is for this app and this device, and it hasn't expired. */
    fun check(token: String?, publicKeyBase64: String, packageName: String, device: String, now: Long): Claims? = runCatching {
        if (token.isNullOrBlank()) return null
        val (body, sig) = token.split('.').takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return null
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(body.toByteArray()) }
        if (!verifier.verify(Base64.getUrlDecoder().decode(sig))) return null
        val json = JSONObject(String(Base64.getUrlDecoder().decode(body)))
        val claims = Claims(json.getString("pkg"), json.getString("device"), json.getBoolean("pro"), json.getLong("exp"))
        claims.takeIf { it.pkg == packageName && it.device == device && it.expiresAt > now }
    }.getOrNull()
}
