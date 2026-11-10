package com.ipvio.stream

/**
 * MR TV 2 (2.0.6): decodes the built-in server address. The build (app/build.gradle.kts) XORs the address with a random
 * per-build key and an index pattern and stores both as hex, so the address is not a readable string in the APK.
 * This only stops casual digging; the app has to know the address to connect.
 */
object ServerObf {
    fun unhex(s: String): ByteArray {
        val t = s.trim()
        if (t.length % 2 != 0) return ByteArray(0)
        return ByteArray(t.length / 2) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun encode(plain: String, key: ByteArray): String {
        val d = plain.toByteArray(Charsets.UTF_8)
        return hex(ByteArray(d.size) { i -> (d[i].toInt() xor key[i % key.size].toInt() xor (i * 31 and 0xFF)).toByte() })
    }

    fun decode(data: String, key: String): String {
        val d = unhex(data)
        val k = unhex(key)
        if (d.isEmpty() || k.isEmpty()) return ""
        return String(ByteArray(d.size) { i -> (d[i].toInt() xor k[i % k.size].toInt() xor (i * 31 and 0xFF)).toByte() }, Charsets.UTF_8)
    }
}
