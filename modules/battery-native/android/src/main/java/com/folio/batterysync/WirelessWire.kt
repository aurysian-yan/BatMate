package com.folio.batterysync

import java.io.DataInputStream
import java.io.DataOutputStream

// 大小边界先于分配内存，避免无效消息占用后台服务。
internal object WirelessWire {
    const val MAXIMUM_SIZE = 32_768
    fun matchesFingerprint(expected: String, certificate: ByteArray): Boolean {
        val bytes = expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return java.security.MessageDigest.isEqual(bytes, java.security.MessageDigest.getInstance("SHA-256").digest(certificate))
    }
    fun read(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 1..MAXIMUM_SIZE) { "INVALID_FRAME" }
        return ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
    }
    fun write(output: DataOutputStream, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAXIMUM_SIZE) { "INVALID_FRAME" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }
}
