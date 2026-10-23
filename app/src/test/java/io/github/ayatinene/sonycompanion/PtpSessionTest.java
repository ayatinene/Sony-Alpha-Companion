package io.github.ayatinene.sonycompanion;

import com.fimagena.libptp.*;
import com.fimagena.libptp.ptpip.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** A real loopback command/event socket pair, with synthetic camera responses. */
public class PtpSessionTest {
    private static byte[] packet(InputStream in) throws Exception {
        byte[] header=in.readNBytes(8);
        if(header.length!=8)throw new EOFException();
        int len=(int)new DataBuffer(header).readUInt32();
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(header);out.write(in.readNBytes(len-8));return out.toByteArray();
    }
    private static void send(OutputStream out,PtpIpPacket p) throws Exception {out.write(p.serializePacket());out.flush();}
    private static void result(OutputStream out,long id,byte[] data) throws Exception {
        if(data!=null) {send(out,new PtpIpPacket.StartData(id,data.length));send(out,new PtpIpPacket.EndData(id,new DataBuffer(data)));}
        send(out,new PtpIpPacket.OperationResponse(0x2000+1,id,new long[0]));
    }
    private static byte[] info() {
        DataBuffer b=new DataBuffer();b.writeUInt16(100).writeUInt32(6).writeUInt16(100).writeUInt8((short)0).writeUInt16(0);
        b.writeUInt32(3).writeUInt16(0x1001).writeUInt16(0x1002).writeUInt16(0x1009);
        for(int i=0;i<4;i++)b.writeUInt32(0);
        for(String s:new String[]{"Sony Corporation","ILCE-7RM2 synthetic","4.01","test"})new PtpDataType.PtpString(s).writeToBuffer(b);
        return b.getData();
    }
    @Test(timeout=15000) public void opensBothChannelsStreamsObjectAndCloses() throws Exception {
        byte[] jpeg=new byte[131079];for(int i=0;i<jpeg.length;i++)jpeg[i]=(byte)(i%251);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0)) {
            Future<?> mock=worker.submit(()->{
                try(Socket command=listener.accept()) {
                    command.setSoTimeout(10000);packet(command.getInputStream());
                    send(command.getOutputStream(),new PtpIpPacket.InitCommandAck(1,new PtpIpConnection.PtpIpHostId(new short[16],"Mock A7R2")));
                    try(Socket event=listener.accept()) {
                        event.setSoTimeout(10000);packet(event.getInputStream());send(event.getOutputStream(),new PtpIpPacket.InitEventAck());
                        for(int count=0;count<4;count++) {
                            byte[] request=packet(command.getInputStream());DataBuffer b=new DataBuffer(java.util.Arrays.copyOfRange(request,12,request.length));
                            int op=b.readUInt16();long tx=b.readUInt32();
                            if(op==0x1001)result(command.getOutputStream(),tx,info());
                            else if(op==0x1002||op==0x1003)result(command.getOutputStream(),tx,null);
                            else if(op==0x1009)result(command.getOutputStream(),tx,jpeg);
                            else throw new AssertionError("Unexpected operation "+op);
                        }
                    }
                } catch(Exception e){throw new RuntimeException(e);}
            });
            PtpConnection c=new PtpConnection(new PtpIpConnection());
            try {
                c.connect(new PtpIpConnection.PtpIpAddress(InetAddress.getLoopbackAddress(),listener.getLocalPort()),new PtpIpConnection.PtpIpHostId(new short[16],"test"));
                assertEquals("ILCE-7RM2 synthetic",c.getDeviceInfo().mModel.mString);
                PtpSession s=c.openSession();ByteArrayOutputStream output=new ByteArrayOutputStream();long[] last={0};
                s.getObjectToStream(new PtpDataType.ObjectHandle(42),output,(read,total)->{assertTrue(read>=last[0]);last[0]=read;assertEquals(jpeg.length,total);});
                assertArrayEquals(jpeg,output.toByteArray());assertEquals(jpeg.length,last[0]);
                c.close();mock.get(5,TimeUnit.SECONDS);
            } finally {try{c.close();}catch(Exception ignored){}}
        } finally {worker.shutdownNow();}
    }
}
