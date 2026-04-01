package com.example.tinybar.util

import java.security.MessageDigest
import java.util.Locale

object TiebaSignUtil {
    private const val SIGN_SUFFIX = "tiebaclient!!!"

    fun sign(params: Map<String, Any>): Map<String, String> {
        val normalized = params.mapValues { it.value.toString() }

        val raw = normalized
            .toSortedMap()
            .entries
            .joinToString(separator = "") { "${it.key}=${it.value}" }

        val sign = md5(raw + SIGN_SUFFIX).uppercase(Locale.ROOT)
        return normalized + ("sign" to sign)
    }

    private fun md5(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}