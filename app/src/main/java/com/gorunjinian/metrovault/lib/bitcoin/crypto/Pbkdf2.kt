package com.gorunjinian.metrovault.lib.bitcoin.crypto


object Pbkdf2 {

    fun withHmacSha512(password: ByteArray, salt: ByteArray, count: Int, dkLen: Int): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(String(password).toCharArray(), salt, count, dkLen * 8)
        val skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return skf.generateSecret(spec).encoded
    }

}
