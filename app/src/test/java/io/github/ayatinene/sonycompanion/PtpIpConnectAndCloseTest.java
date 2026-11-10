package io.github.ayatinene.sonycompanion;

import com.fimagena.libptp.PtpTransport;
import com.fimagena.libptp.ptpip.DataBuffer;
import com.fimagena.libptp.ptpip.PtpIpConnection;
import com.fimagena.libptp.ptpip.PtpIpExceptions;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Init-failure teardown and CloseSession confirmation fidelity on a real loopback command/event pair.
 * A failed command/event init must release both channels; only a local close or a real CloseSession
 * response confirms teardown, while a remote EOF without a response must propagate.
 */
public class PtpIpConnectAndCloseTest {

    private static byte[] readPacket(InputStream in) throws Exception {
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

    private static PtpIpConnection.PtpIpHostId hostId() {
        return new PtpIpConnection.PtpIpHostId(new short[16], "Mock");
    }

    private static int operationOf(byte[] request) throws Exception {
        return new DataBuffer(Arrays.copyOfRange(request, 12, request.length)).readUInt16();
    }

    private static long transactionOf(byte[] request) throws Exception {
        DataBuffer b = new DataBuffer(Arrays.copyOfRange(request, 12, request.length));
        b.readUInt16();
        return b.readUInt32();
    }

    private static PtpIpConnection connect(ServerSocket listener) throws Exception {
        PtpIpConnection transport = new PtpIpConnection();
        transport.connect(new PtpIpConnection.PtpIpAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                new PtpIpConnection.PtpIpHostId(new short[16], "test"));
        return transport;
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Init failure on either channel releases both channels

    @Test(timeout = 20000) public void initCommandFailReleasesBothChannels() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            worker.submit(() -> {
                try (Socket command = listener.accept()) {
                    command.setSoTimeout(10000);
                    readPacket(command.getInputStream());
                    send(command.getOutputStream(), new PtpIpPacket.InitFail(1));
                    Thread.sleep(500);
                } catch (Exception ignored) {}
                return null;
            });

            PtpIpConnection transport = new PtpIpConnection();
            try {
                transport.connect(new PtpIpConnection.PtpIpAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                        new PtpIpConnection.PtpIpHostId(new short[16], "test"));
                fail("a refused command init must fail the connect");
            } catch (PtpIpExceptions.OperationFailed expected) {
                // expected
            } catch (Exception unexpected) {
                fail("unexpected: " + unexpected);
            } finally {
                assertTrue("both channels must be released after an init failure", transport.isClosed());
                assertFalse(transport.isConnected());
                transport.close();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    @Test(timeout = 20000) public void initEventFailReleasesBothChannels() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            worker.submit(() -> {
                try (Socket command = listener.accept()) {
                    command.setSoTimeout(10000);
                    readPacket(command.getInputStream());
                    send(command.getOutputStream(), new PtpIpPacket.InitCommandAck(1, hostId()));
                    try (Socket event = listener.accept()) {
                        event.setSoTimeout(10000);
                        readPacket(event.getInputStream());
                        send(event.getOutputStream(), new PtpIpPacket.InitFail(2));
                        Thread.sleep(500);
                    }
                } catch (Exception ignored) {}
                return null;
            });

            PtpIpConnection transport = new PtpIpConnection();
            try {
                transport.connect(new PtpIpConnection.PtpIpAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                        new PtpIpConnection.PtpIpHostId(new short[16], "test"));
                fail("a refused event init must fail the connect");
            } catch (PtpIpExceptions.OperationFailed expected) {
                // expected
            } catch (Exception unexpected) {
                fail("unexpected: " + unexpected);
            } finally {
                assertTrue("both channels must be released after an init failure", transport.isClosed());
                assertFalse(transport.isConnected());
                transport.close();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. A completed CloseSession response queued before EOF still succeeds (FIFO regression)

    @Test(timeout = 20000) public void closeSessionResponseQueuedBeforeEofSucceeds() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            worker.submit(() -> {
                try (Socket command = listener.accept()) {
                    command.setSoTimeout(10000);
                    readPacket(command.getInputStream());
                    send(command.getOutputStream(), new PtpIpPacket.InitCommandAck(1, hostId()));
                    try (Socket event = listener.accept()) {
                        event.setSoTimeout(10000);
                        readPacket(event.getInputStream());
                        send(event.getOutputStream(), new PtpIpPacket.InitEventAck());
                        byte[] open = readPacket(command.getInputStream());
                        send(command.getOutputStream(), new PtpIpPacket.OperationResponse(0x2001, transactionOf(open), new long[0]));
                        byte[] close = readPacket(command.getInputStream());
                        assertEquals(0x1003, operationOf(close));
                        send(command.getOutputStream(), new PtpIpPacket.OperationResponse(0x2001, transactionOf(close), new long[0]));
                        command.getOutputStream().flush();
                        command.close(); // EOF arrives after the valid CloseSession response
                        Thread.sleep(500);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });

            PtpIpConnection transport = connect(listener);
            try {
                PtpTransport.Session session = transport.openSession();
                session.close(); // must not throw: the response was queued before EOF
            } finally {
                transport.close();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. A remote EOF without a CloseSession response is not a confirmation and must propagate

    @Test(timeout = 20000) public void remoteEofWithoutCloseSessionResponsePropagates() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            worker.submit(() -> {
                try (Socket command = listener.accept()) {
                    command.setSoTimeout(10000);
                    readPacket(command.getInputStream());
                    send(command.getOutputStream(), new PtpIpPacket.InitCommandAck(1, hostId()));
                    try (Socket event = listener.accept()) {
                        event.setSoTimeout(10000);
                        readPacket(event.getInputStream());
                        send(event.getOutputStream(), new PtpIpPacket.InitEventAck());
                        byte[] open = readPacket(command.getInputStream());
                        send(command.getOutputStream(), new PtpIpPacket.OperationResponse(0x2001, transactionOf(open), new long[0]));
                        readPacket(command.getInputStream()); // consume CloseSession, never answer
                        command.close(); // remote EOF without a response
                        Thread.sleep(500);
                    }
                } catch (Exception ignored) {}
                return null;
            });

            PtpIpConnection transport = connect(listener);
            try {
                PtpTransport.Session session = transport.openSession();
                try {
                    session.close();
                    fail("a remote EOF without a CloseSession response must not be treated as confirmed");
                } catch (PtpTransport.TransportIOError expected) {
                    // expected
                }
            } finally {
                transport.close();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. A local close while CloseSession is pending is still unconfirmed and must propagate

    @Test(timeout = 20000) public void localCloseWithoutACloseSessionResponseIsUnconfirmed() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch closeRequestReceived = new CountDownLatch(1);
        try (ServerSocket listener = new ServerSocket(0)) {
            worker.submit(() -> {
                try (Socket command = listener.accept()) {
                    command.setSoTimeout(10000);
                    readPacket(command.getInputStream());
                    send(command.getOutputStream(), new PtpIpPacket.InitCommandAck(1, hostId()));
                    try (Socket event = listener.accept()) {
                        event.setSoTimeout(10000);
                        readPacket(event.getInputStream());
                        send(event.getOutputStream(), new PtpIpPacket.InitEventAck());
                        byte[] open = readPacket(command.getInputStream());
                        send(command.getOutputStream(), new PtpIpPacket.OperationResponse(0x2001, transactionOf(open), new long[0]));
                        readPacket(command.getInputStream()); // consume CloseSession, never answer
                        closeRequestReceived.countDown();
                        Thread.sleep(8000);
                    }
                } catch (Exception ignored) {}
                return null;
            });

            PtpIpConnection transport = connect(listener);
            try {
                PtpTransport.Session session = transport.openSession();
                Future<Throwable> pending = caller.submit(() -> {
                    try {session.close(); return null;}
                    catch (Throwable t) {return t;}
                });
                assertTrue("the CloseSession request must reach the peer", closeRequestReceived.await(5, TimeUnit.SECONDS));
                transport.close(); // local close, but no OK was ever received
                Throwable failure = pending.get(5, TimeUnit.SECONDS);
                assertNotNull("an unconfirmed close must not be reported as success", failure);
                assertTrue("expected a transport failure, got: " + failure, failure instanceof PtpTransport.TransportIOError);
            } finally {
                transport.close();
                caller.shutdownNow();
            }
        } finally {
            worker.shutdownNow();
        }
    }
}
