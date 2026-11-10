package io.github.ayatinene.sonycompanion;

import com.fimagena.libptp.PtpConnection;
import com.fimagena.libptp.PtpDataType;
import com.fimagena.libptp.PtpLogObserver;
import com.fimagena.libptp.PtpLogRecord;
import com.fimagena.libptp.PtpSession;
import com.fimagena.libptp.ptpip.DataBuffer;
import com.fimagena.libptp.ptpip.PtpIpConnection;
import com.fimagena.libptp.ptpip.PtpIpPacket;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Diagnostic-log observer behaviour: it must cover init/transaction phases, carry the original
 * cause on failures, never leak GUID/payload/photo bytes, and never break the protocol even when
 * the observer itself throws.
 */
public class PtpIpDiagnosticsTest {

    private static byte[] packet(InputStream in) throws Exception {
        byte[] header = in.readNBytes(8);
        if (header.length != 8) throw new EOFException();
        int len = (int) new DataBuffer(header).readUInt32();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(in.readNBytes(len - 8));
        return out.toByteArray();
    }

    private static void send(OutputStream out, PtpIpPacket p) throws IOException {
        out.write(p.serializePacket());
        out.flush();
    }

    private static void result(OutputStream out, long id, byte[] data) throws IOException {
        if (data != null) {
            send(out, new PtpIpPacket.StartData(id, data.length));
            send(out, new PtpIpPacket.EndData(id, new DataBuffer(data)));
        }
        send(out, new PtpIpPacket.OperationResponse(0x2001, id, new long[0]));
    }

    private static byte[] info() {
        DataBuffer b = new DataBuffer();
        b.writeUInt16(100).writeUInt32(6).writeUInt16(100).writeUInt8((short) 0).writeUInt16(0);
        b.writeUInt32(3).writeUInt16(0x1001).writeUInt16(0x1002).writeUInt16(0x1009);
        for (int i = 0; i < 4; i++) b.writeUInt32(0);
        for (String s : new String[]{"Sony Corporation", "ILCE-7RM2 synthetic", "4.01", "test"})
            new PtpDataType.PtpString(s).writeToBuffer(b);
        return b.getData();
    }

    private static short[] distinctiveGuid() {
        return new short[]{0x12, 0x34, 0x56, 0x78, (short) 0x9a, (short) 0xbc, (short) 0xde, (short) 0xf0,
                0, 0, 0, 0, 0, 0, 0, 0};
    }

    /** Server for a full successful flow: GetDeviceInfo, OpenSession, GetObject, CloseSession. */
    private static Future<?> serveFullFlow(ExecutorService worker, ServerSocket listener, byte[] jpeg) {
        return worker.submit(() -> {
            try (Socket command = listener.accept()) {
                command.setSoTimeout(10000);
                packet(command.getInputStream());
                send(command.getOutputStream(), new PtpIpPacket.InitCommandAck(1,
                        new PtpIpConnection.PtpIpHostId(new short[16], "Mock A7R2")));
                try (Socket event = listener.accept()) {
                    event.setSoTimeout(10000);
                    packet(event.getInputStream());
                    send(event.getOutputStream(), new PtpIpPacket.InitEventAck());
                    for (int count = 0; count < 4; count++) {
                        byte[] request = packet(command.getInputStream());
                        DataBuffer b = new DataBuffer(Arrays.copyOfRange(request, 12, request.length));
                        int op = b.readUInt16();
                        long tx = b.readUInt32();
                        if (op == 0x1001) result(command.getOutputStream(), tx, info());
                        else if (op == 0x1002 || op == 0x1003) result(command.getOutputStream(), tx, null);
                        else if (op == 0x1009) result(command.getOutputStream(), tx, jpeg);
                        else throw new AssertionError("Unexpected operation " + op);
                    }
                    // Hold the event channel open until the client tears the connection down.
                    // Closing it here would race the CloseSession response and turn a confirmed
                    // close into a spurious transport EOF (the peer keeps the event socket open
                    // during the CloseSession handshake; see PtpIpConnectAndCloseTest).
                    while (event.getInputStream().read() != -1) { }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    private static final class RecordingObserver implements PtpLogObserver {
        final List<PtpLogRecord.Level> levels = new CopyOnWriteArrayList<>();
        final List<String> messages = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override public void report(PtpLogRecord.Level level, String message, Throwable error) {
            levels.add(level);
            messages.add(message == null ? "" : message);
            errors.add(error);
        }

        boolean anyMessageContains(String needle) {
            for (String m : messages) if (m.contains(needle)) return true;
            return false;
        }
    }

    private static final class ThrowingObserver implements PtpLogObserver {
        @Override public void report(PtpLogRecord.Level level, String message, Throwable error) {
            throw new RuntimeException("observer must never break the protocol");
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Test(timeout = 20000) public void observerCoversPhasesWithoutLeakingSensitiveData() throws Exception {
        byte[] jpeg = new byte[131079];
        for (int i = 0; i < jpeg.length; i++) jpeg[i] = (byte) (i % 251);

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            Future<?> server = serveFullFlow(worker, listener, jpeg);
            PtpIpConnection transport = new PtpIpConnection();
            RecordingObserver observer = new RecordingObserver();
            transport.setLogObserver(observer);
            PtpConnection c = new PtpConnection(transport);
            try {
                c.connect(new PtpIpConnection.PtpIpAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                        new PtpIpConnection.PtpIpHostId(distinctiveGuid(), "test"));
                assertEquals("ILCE-7RM2 synthetic", c.getDeviceInfo().mModel.mString);
                PtpSession s = c.openSession();
                assertArrayEquals(jpeg, s.getObject(new PtpDataType.ObjectHandle(42)));
                c.close();
                server.get(5, TimeUnit.SECONDS);
            } finally {
                try {c.close();} catch (Exception ignored) {}
            }

            assertFalse("observer should have received records", observer.messages.isEmpty());
            assertTrue("init phase should be logged", observer.anyMessageContains("INIT_COMMAND"));
            assertTrue("transaction phase should be logged", observer.anyMessageContains("TRANSACTION"));
            assertTrue("operation codes should be logged", observer.anyMessageContains("op=0x"));

            String guidHex = "12:34:56:78";
            for (String m : observer.messages) {
                assertFalse("GUID must never be logged: " + m, m.contains(guidHex));
                assertFalse("GUID must never be logged: " + m, m.contains("GUID"));
                assertFalse("raw packet payload must never be logged: " + m, m.contains("PayloadLength"));
            }
        } finally {
            worker.shutdownNow();
        }
    }

    @Test(timeout = 20000) public void observerReportsOriginalCauseOnTransportFailure() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            worker.submit(() -> {
                try (Socket command = listener.accept()) {
                    command.setSoTimeout(10000);
                    packet(command.getInputStream());
                    send(command.getOutputStream(), new PtpIpPacket.InitCommandAck(1,
                            new PtpIpConnection.PtpIpHostId(new short[16], "Mock")));
                    try (Socket event = listener.accept()) {
                        event.setSoTimeout(10000);
                        packet(event.getInputStream());
                        send(event.getOutputStream(), new PtpIpPacket.InitEventAck());
                        packet(command.getInputStream()); // consume request, drop sockets
                    }
                } catch (Exception ignored) {
                }
                return null;
            });

            PtpIpConnection transport = new PtpIpConnection();
            RecordingObserver observer = new RecordingObserver();
            transport.setLogObserver(observer);
            transport.connect(new PtpIpConnection.PtpIpAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                    new PtpIpConnection.PtpIpHostId(new short[16], "test"));
            try {
                transport.getDeviceInfo();
                fail("expected transport failure");
            } catch (Exception expected) {
                // ignored - the observer must have captured the original cause
            } finally {
                transport.close();
            }

            assertTrue("transport failure should be logged", observer.anyMessageContains("TRANSPORT"));
            Throwable recorded = null;
            for (Throwable t : observer.errors) if (t != null) recorded = t;
            assertNotNull("failure record must carry the original exception", recorded);
            Throwable root = recorded;
            while (root.getCause() != null) root = root.getCause();
            assertTrue("original EOF cause must be preserved, got: " + root,
                    (root instanceof EOFException) || String.valueOf(root.getMessage()).contains("truncated"));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test(timeout = 20000) public void throwingObserverDoesNotBreakProtocol() throws Exception {
        byte[] jpeg = new byte[131079];
        for (int i = 0; i < jpeg.length; i++) jpeg[i] = (byte) (i % 251);

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            Future<?> server = serveFullFlow(worker, listener, jpeg);
            PtpIpConnection transport = new PtpIpConnection();
            transport.setLogObserver(new ThrowingObserver());
            PtpConnection c = new PtpConnection(transport);
            try {
                c.connect(new PtpIpConnection.PtpIpAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                        new PtpIpConnection.PtpIpHostId(distinctiveGuid(), "test"));
                assertEquals("ILCE-7RM2 synthetic", c.getDeviceInfo().mModel.mString);
                PtpSession s = c.openSession();
                assertArrayEquals(jpeg, s.getObject(new PtpDataType.ObjectHandle(42)));
                c.close();
                server.get(5, TimeUnit.SECONDS);
            } finally {
                try {c.close();} catch (Exception ignored) {}
            }
        } finally {
            worker.shutdownNow();
        }
    }
}
