package com.ascolta.app

import com.ascolta.app.data.FreeLimits
import com.ascolta.app.data.ProPass
import com.ascolta.app.whisper.SpeechModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import java.util.Base64

class ProTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

    /** Signs like the server (server/index.js). */
    private fun pass(json: String, key: java.security.PrivateKey = keys.private): String {
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(body.toByteArray()) }.sign()
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
    }

    @Test
    fun aGenuinePassForThisDeviceIsAccepted() {
        val p = pass("""{"pkg":"app.lingopod","device":"d1","pro":true,"iat":1,"exp":2000}""")
        assertNotNull(ProPass.check(p, publicKey, "app.lingopod", "d1", now = 1000))
    }

    @Test
    fun forgedExpiredOrForeignPassesAreRefused() {
        val good = pass("""{"pkg":"app.lingopod","device":"d1","pro":true,"iat":1,"exp":2000}""")
        // Changed claims with the old signature.
        val forged = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"pkg":"app.lingopod","device":"d1","pro":true,"iat":1,"exp":9999999}""".toByteArray()) + "." + good.substringAfter('.')
        assertNull(ProPass.check(forged, publicKey, "app.lingopod", "d1", now = 1000))
        // Signed by someone else's key.
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertNull(ProPass.check(pass("""{"pkg":"app.lingopod","device":"d1","pro":true,"iat":1,"exp":2000}""", other.private), publicKey, "app.lingopod", "d1", 1000))
        assertNull(ProPass.check(good, publicKey, "app.lingopod", "d1", now = 3000))        // expired
        assertNull(ProPass.check(good, publicKey, "app.lingopod", "another", now = 1000))   // another install
        assertNull(ProPass.check(good, publicKey, "com.copy.app", "d1", now = 1000))       // a repackaged copy
        assertNull(ProPass.check("garbage", publicKey, "app.lingopod", "d1", 1000))
    }

    @Test
    fun freeLimits() {
        // Weeks start on Monday: Sunday 15 Nov and Monday 16 Nov 2026 are in different weeks.
        assertEquals(FreeLimits.weekOf(LocalDate.of(2026, 11, 9)), FreeLimits.weekOf(LocalDate.of(2026, 11, 15)))
        assertEquals(FreeLimits.weekOf(LocalDate.of(2026, 11, 15)) + 7, FreeLimits.weekOf(LocalDate.of(2026, 11, 16)))
        // The Best model is Pro: free users get Balanced instead.
        assertEquals(SpeechModel.SMALL, FreeLimits.allowedModel(SpeechModel.PARAKEET, "it", pro = false))
        assertEquals(SpeechModel.PARAKEET, FreeLimits.allowedModel(SpeechModel.PARAKEET, "it", pro = true))
        assertEquals(SpeechModel.BASE, FreeLimits.allowedModel(SpeechModel.BASE, "it", pro = false))
    }
}
