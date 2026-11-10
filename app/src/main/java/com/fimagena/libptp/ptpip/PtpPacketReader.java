/* libptp adaptation, LGPL-2.1-or-later. See licenses/libptp-LGPL-2.1.txt.
 * Splits data packets before allocation: a single wire packet may contain an entire ARW.
 */
package com.fimagena.libptp.ptpip;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

public final class PtpPacketReader {
    private final InputStream input;
    private long remaining, transaction;
    private boolean endPending;
    public PtpPacketReader(InputStream input) { this.input = input; }
    public PtpIpPacket next() throws IOException, PtpIpExceptions.MalformedPacket {
        if (remaining > 0) return chunk();
        if (endPending) { endPending = false; return new PtpIpPacket.EndData(transaction, new DataBuffer()); }
        byte[] header = read(8);
        DataBuffer buffer = new DataBuffer(header);
        long length = buffer.readUInt32(), type = buffer.readUInt32();
        if (length < 8) throw new IOException("Invalid PTP/IP length");
        if (type == 10 || type == 12) {
            if (length < 12) throw new IOException("Truncated PTP/IP data header");
            transaction = new DataBuffer(read(4)).readUInt32();
            remaining = length - 12;
            endPending = type == 12;
            if (remaining > 0) return chunk();
            if (endPending) { endPending = false; return new PtpIpPacket.EndData(transaction,new DataBuffer()); }
            return new PtpIpPacket.Data(transaction,new DataBuffer());
        }
        if (length > 8 * 1024 * 1024) throw new IOException("PTP/IP metadata packet exceeds 8 MiB");
        return PtpIpPacket.readPacket(new SequenceInputStream(new ByteArrayInputStream(header),input));
    }
    private PtpIpPacket chunk() throws IOException {
        int n = (int)Math.min(remaining, 64 * 1024);
        byte[] bytes = read(n); remaining -= n;
        return new PtpIpPacket.Data(transaction,new DataBuffer(bytes));
    }
    private byte[] read(int length) throws IOException {
        byte[] bytes = new byte[length]; int offset = 0;
        while (offset < length) {
            int n = input.read(bytes,offset,length-offset);
            if (n < 0) throw new EOFException("PTP/IP packet truncated");
            offset += n;
        }
        return bytes;
    }
}
