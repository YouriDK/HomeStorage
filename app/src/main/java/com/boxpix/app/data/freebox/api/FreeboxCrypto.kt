package com.boxpix.app.data.freebox.api

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object FreeboxCrypto {

    /**
     * Session password required by POST login/session: lowercase hex of
     * HMAC-SHA1(key = app_token, message = challenge).
     */
    fun sessionPassword(appToken: String, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(appToken.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val digest = mac.doFinal(challenge.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
