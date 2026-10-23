package io.github.ayatinene.sonycompanion;

import com.fimagena.libptp.PtpOperation;
import com.fimagena.libptp.ptpip.*;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PtpStreamingTest {
    @Test public void largeWirePacketUsesBoundedChunks() throws Exception {
        final long size = 85_327_872L; // uploaded A7R2 uncompressed ARW size; synthetic content only
        byte[] header = new DataBuffer().writeUInt32(size+12).writeUInt32(12).writeUInt32(7).getData();
        InputStream synthetic = new InputStream() {
            long left=size;
            public int read() { if(left--<=0)return -1;return 37; }
            public int read(byte[] b,int off,int len) { if(left<=0)return -1;int n=(int)Math.min(left,len);java.util.Arrays.fill(b,off,off+n,(byte)37);left-=n;return n; }
        };
        PtpPacketReader reader=new PtpPacketReader(new SequenceInputStream(new ByteArrayInputStream(header),synthetic));
        long received=0;int packets=0;
        while(true) {
            PtpIpPacket packet=reader.next();
            if(packet instanceof PtpIpPacket.EndData)break;
            assertTrue(packet instanceof PtpIpPacket.Data);
            int chunk=packet.serializePacket().length-12;
            assertTrue(chunk<=65536);received+=chunk;packets++;
        }
        assertEquals(size,received);assertTrue(packets>1000);
    }
    @Test public void malformedDataPacketFailsBeforeAllocating() throws Exception {
        byte[] bytes=new DataBuffer().writeUInt32(9).writeUInt32(12).getData();
        try{new PtpPacketReader(new ByteArrayInputStream(bytes)).next();fail();}catch(IOException expected){}
    }
    @Test public void getHandlesAcceptsAllThreeSonyParameters() throws Exception {
        PtpOperation.Request request=PtpOperation.createRequest(PtpOperation.OPSCODE_GetObjectHandles);
        request.setParameters(new long[]{0xffffffffL,0,0});request.validate();
    }
    @Test public void streamedBufferDoesNotAccumulateBytes() {
        final long[] length={0};
        DataBuffer buffer=new DataBuffer();buffer.enableStreaming(new OutputStream(){
            public void write(int b){length[0]++;}
            public void write(byte[] b,int off,int len){length[0]+=len;}
        });
        byte[] chunk=new byte[65536];for(int i=0;i<1302;i++)buffer.writeObject(chunk);
        assertEquals(length[0],buffer.size());assertEquals(0,buffer.readObject().length);
    }
}
