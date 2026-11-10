/* Sony USB receiver-registration layout documented by sony-pm-alt / sony-guid-setter.
 * Protocol research: Clemens Fruhwirth (2016); xusb transport reference: Pete Batard,
 * Alan Stern. LGPL-2.1-or-later. Source references and LGPL-2.1 notices are retained in docs/REUSE.md.
 */
package io.github.ayatinene.sonycompanion.protocol

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sony vendor CDB carried over USB Mass Storage Bulk-Only Transport. */
object SonyGuidRegistration {
    const val PAYLOAD_SIZE=8192
    const val COMMAND_SIZE=31
    const val STATUS_SIZE=13

    fun payload(guid: ByteArray, receiver: String="Alpha"): ByteArray {
        require(guid.size==16) { "接收端 GUID 必须为 16 字节" }
        val name=receiver.toByteArray(Charsets.UTF_16LE)
        require(name.size<=16) { "USB 接收端名称最多 8 个 UTF-16 字符" }
        return ByteArray(PAYLOAD_SIZE).apply {
            byteArrayOf(0x25,0x01,0,0,0x08).copyInto(this,0)
            name.copyInto(this,17)
            this[177]=0x10
            guid.copyInto(this,181)
        }
    }
    fun command(tag: Int, lun: Int=0): ByteArray {
        require(lun in 0..15) { "USB LUN 超出范围" }
        return ByteBuffer.allocate(COMMAND_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x43425355); putInt(tag); putInt(PAYLOAD_SIZE)
            put(0); put(lun.toByte()); put(9)
            put(byteArrayOf(0x7a,0x04,0,0,0,0,0,0,0))
        }.array()
    }
    fun checkStatus(status: ByteArray, tag: Int) {
        if(status.size!=STATUS_SIZE) throw IOException("USB 状态长度不符：${status.size} / $STATUS_SIZE")
        val data=ByteBuffer.wrap(status).order(ByteOrder.LITTLE_ENDIAN)
        if(data.int!=0x53425355) throw IOException("USB 状态签名错误")
        if(data.int!=tag) throw IOException("USB 状态事务标识不符")
        val residue=data.int.toLong() and 0xffffffffL
        val result=data.get().toInt() and 255
        if(result!=0) throw IOException("相机未接受 GUID 登记：USB 状态 $result，剩余 $residue 字节")
        // Some Sony devices report a nonzero residue even for successful vendor commands.
    }
    fun register(guid: ByteArray, lun: Int=0, write: (ByteArray)->Int, readStatus: ()->ByteArray) {
        val body=payload(guid)
        val tag=1
        val command=command(tag,lun)
        if(write(command)!=COMMAND_SIZE) throw IOException("USB 登记命令发送不完整")
        if(write(body)!=PAYLOAD_SIZE) throw IOException("USB 登记数据发送不完整；请重新连接相机后重试")
        checkStatus(readStatus(),tag)
    }
}
