package io.github.ayatinene.sonycompanion.protocol

import java.io.EOFException
import java.io.InputStream

/** Sony Camera Remote API framing: 8 byte common + 128 byte payload header.
 * Frame-information packets are consumed, never sent to a JPEG decoder.
 */
class LiveViewReader(private val input: InputStream) {
    fun next(): ByteArray? {
        while (true) {
            val first = input.read()
            if (first < 0) return null
            if (first != 255) continue
            val common = read(7)
            val header = read(128)
            require(header.take(4).map { it.toInt() and 255 } == listOf(0x24,0x35,0x68,0x79)) { "实时取景数据头错误" }
            val count = ((header[4].toInt() and 255) shl 16) or ((header[5].toInt() and 255) shl 8) or (header[6].toInt() and 255)
            require(count in 0..(16 * 1024 * 1024)) { "实时取景帧过大" }
            val jpeg = read(count)
            read(header[7].toInt() and 255)
            if (common[0].toInt() == 1) return jpeg
        }
    }
    private fun read(n: Int): ByteArray {
        val bytes = ByteArray(n); var offset = 0
        while (offset < n) {
            val count = input.read(bytes, offset, n - offset)
            if (count < 0) throw EOFException("实时取景数据中断")
            if (count == 0) continue
            offset += count
        }
        return bytes
    }
}
