package io.github.ayatinene.sonycompanion.pairing

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Transient credentials: never persist or include in logs, exceptions, saved state or data-class copies. */
class PairingCredentials private constructor(val ssid: String, val password: String) {
    override fun toString() = "PairingCredentials([redacted])"
    companion object {
        fun create(ssid: String, password: String): PairingCredentials {
            require(ssid.toByteArray(Charsets.UTF_8).size in 1..32 && ssid.isNotBlank() && ssid.none { it.isISOControl() }) { "配对信息中的 SSID 无效" }
            require(password.length in 8..63 && password.all { it.code in 32..126 } && password.isNotBlank()) { "配对信息中的 WPA2 密码无效" }
            return PairingCredentials(ssid, password)
        }
    }
}

/** Independently implemented from Imaging Edge 7.8.6 QrDataUtil: only the verified W01 version. */
object SonyQrParser {
    fun parse(raw: String): PairingCredentials {
        require(raw.length <= 2048 && raw.startsWith("W01:")) { "不支持此二维码，请扫描相机智能遥控界面的二维码" }
        val fields = linkedMapOf<String, String>()
        val token = StringBuilder()
        var escaped = false
        fun field() {
            val value = token.toString(); token.setLength(0)
            require(value.length >= 2 && value[1] == ':') { "相机二维码字段格式错误" }
            val key = value.substring(0, 1)
            require(key in setOf("S", "P", "C", "M") && key !in fields) { "相机二维码包含未知或重复字段" }
            fields[key] = value.substring(2)
        }
        for (ch in raw.substring(4)) {
            when {
                escaped -> { require(ch in "\\;,:") { "相机二维码转义格式错误" }; token.append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == ';' -> field()
                else -> token.append(ch)
            }
        }
        require(!escaped && token.isEmpty()) { "相机二维码不完整" }
        val prefix = fields["S"].orEmpty()
        val camera = fields["C"].orEmpty()
        require(prefix.length == 4 && prefix.isNotBlank() && camera.isNotBlank()) { "相机二维码缺少热点信息" }
        // M is not used as a BSSID or authentication token without verified semantics.
        return PairingCredentials.create("DIRECT-$prefix:$camera", fields["P"].orEmpty())
    }
}

/** Sony's NDEF MIME payload, not WSC: two tag bytes, two binary decimal length bytes, value.
 * APK evidence: NdefDescription/EnumNdefInfo. A real ILCE-7RM2 tag is still required for hardware validation. */
object SonyNfcParser {
    const val MIME = "application/x-sony-pmm"
    fun parse(type: String, payload: ByteArray): PairingCredentials {
        require(type == MIME) { "不支持此 NFC 标签；请扫描相机二维码" }
        require(payload.size in 1..8192) { "NFC 配对数据大小无效" }
        val fields = linkedMapOf<Int, ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            require(payload.size - offset >= 4) { "NFC 配对数据不完整" }
            fun byte(i: Int) = payload[offset + i].toInt() and 255
            val tag = byte(0) * 256 + byte(1)
            val length = byte(2) * 10 + byte(3)
            offset += 4
            require(length <= payload.size - offset && tag !in fields) { "NFC 配对字段损坏或重复" }
            fields[tag] = payload.copyOfRange(offset, offset + length)
            offset += length
        }
        require(fields[0x0001]?.contentEquals(byteArrayOf(0x10)) == true) { "不支持此 Sony NFC 版本" }
        fields[0x1004]?.let { require(it.contentEquals(byteArrayOf(1))) { "相机 NFC 未提供可用 Wi-Fi 配置，请先打开智能遥控" } }
        fun text(tag: Int): String {
            val bytes = fields[tag] ?: throw IllegalArgumentException("NFC 标签缺少 SSID 或密码，请扫描相机二维码")
            return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: Exception) { throw IllegalArgumentException("NFC 配对文本编码无效") }
        }
        return PairingCredentials.create(text(0x1000), text(0x1001))
    }
}
