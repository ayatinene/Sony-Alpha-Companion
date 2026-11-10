package io.github.ayatinene.sonycompanion;

import com.fimagena.libptp.PtpExceptions;
import com.fimagena.libptp.PtpOperation;
import com.fimagena.libptp.PtpTransport;
import com.fimagena.libptp.ptpip.*;

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
 * Failure/close fidelity for the PTP-IP transport, exercised against a real loopback
 * command/event socket pair with synthetic camera responses.
 */
public class PtpIpFailureFidelityTest {

    // ---------------------------------------------------------------------------------------------
    // Shared synthetic-transport helpers (kept local so the existing PtpSessionTest stays untouched)

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

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Original transport failure is preserved and is not masked by a generic "Connection closed"

    @Test(timeout = 20000) public void socketFailureCauseIsNotGenericConnectionClosed() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            Future<?> server = worker.submit(() -> {
                try (Socket command = listener.accept()) {
                    command.setSoTimeout(10000);
                    readPacket(command.getInputStream());
                    send(command.getOutputStream(), new PtpIpPacket.InitCommandAck(1, hostId()));
                    try (Socket event = listener.accept()) {
                        event.setSoTimeout(10000);
                        readPacket(event.getInputStream());
                        send(event.getOutputStream(), new PtpIpPacket.InitEventAck());
                        // Consume the transaction request, then drop both sockets without a response.
                        readPacket(command.getInputStream());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            PtpIpConnection transport = connect(listener);
            try {
                transport.getDeviceInfo();
                fail("expected the dropped socket to fail the transaction");
            } catch (PtpTransport.TransportIOError e) {
                Throwable cause = rootCause(e);
                assertTrue("expected the original EOF exception, got: " + cause,
                        (cause instanceof EOFException) || String.valueOf(cause.getMessage()).contains("truncated"));
                assertFalse("generic close must not mask the original cause: " + e.getMessage(),
                        String.valueOf(e.getMessage()).contains("Connection closed"));
            } finally {
                transport.close();
            }
            server.get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. A manual close wakes a transaction that is blocked waiting for a response

    @Test(timeout = 20000) public void manualCloseUnblocksWaitingTransaction() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
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
                        readPacket(command.getInputStream()); // consume the request, never answer
                        Thread.sleep(8000);
                    }
                } catch (Exception ignored) {
                }
                return null;
            });

            PtpIpConnection transport = connect(listener);
            try {
                Future<Throwable> pending = caller.submit(() -> {
                    try {transport.getDeviceInfo(); return null;}
                    catch (Throwable t) {return t;}
                });
                Thread.sleep(400); // allow the request to be sent and the waiter to block
                long before = System.currentTimeMillis();
                transport.close();
                Throwable failure = pending.get(5, TimeUnit.SECONDS);
                assertTrue("close must wake the blocked transaction quickly, took " + (System.currentTimeMillis() - before) + "ms",
                        (System.currentTimeMillis() - before) < 5000);
                assertNotNull("a blocked transaction must fail on close", failure);
                assertTrue(failure instanceof PtpTransport.TransportIOError);
                assertTrue("close reason should be reported", String.valueOf(rootCause(failure).getMessage()).contains("Connection closed"));
            } finally {
                transport.close();
                caller.shutdownNow();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Repeated close is idempotent and does not block

    @Test(timeout = 15000) public void repeatedCloseIsIdempotentAndFast() throws Exception {
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
                        Thread.sleep(3000);
                    }
                } catch (Exception ignored) {
                }
                return null;
            });

            PtpIpConnection transport = connect(listener);
            long start = System.currentTimeMillis();
            for (int i = 0; i < 8; i++) transport.close();
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("repeated close should be cheap, took " + elapsed + "ms", elapsed < 2000);
            assertTrue(transport.isClosed());
            assertFalse(transport.isConnected());
        } finally {
            worker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3b. A close() racing with connect() must surface a real failure, never a fake success

    @Test(timeout = 20000) public void concurrentCloseDuringConnectFailsInsteadOfReturningSuccess() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            worker.submit(() -> {
                try (Socket command = listener.accept()) {
                    command.setSoTimeout(10000);
                    readPacket(command.getInputStream());
                    send(command.getOutputStream(), new PtpIpPacket.InitCommandAck(1, hostId()));
                    // Accept the event channel but never acknowledge it, so connect() blocks there.
                    try (Socket event = listener.accept()) {
                        event.setSoTimeout(10000);
                        readPacket(event.getInputStream());
                        Thread.sleep(8000);
                    }
                } catch (Exception ignored) {
                }
                return null;
            });

            PtpIpConnection transport = new PtpIpConnection();
            Future<Throwable> pending = caller.submit(() -> {
                try {
                    transport.connect(new PtpIpConnection.PtpIpAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                            new PtpIpConnection.PtpIpHostId(new short[16], "test"));
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Thread.sleep(500); // command channel done, event channel still waiting
            transport.close();

            Throwable failure = pending.get(5, TimeUnit.SECONDS);
            assertNotNull("connect must fail on concurrent close instead of returning success", failure);
            assertTrue("expected a transport IO failure, got: " + failure, failure instanceof PtpTransport.TransportIOError);
            assertFalse("a closed transport must not report itself as connected", transport.isConnected());
        } finally {
            worker.shutdownNow();
            caller.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3c. A completed response already queued must survive a later EOF terminal (no queue clearing)

    @Test(timeout = 20000) public void completedResponseQueuedBeforeEofIsNotLost() throws Exception {
        byte[] object = new byte[200_000]; // 4 wire chunks + StartData + EndData + OK = 7 packets, fits the bounded queue
        for (int i = 0; i < object.length; i++) object[i] = (byte) (i % 251);

        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
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
                        // OpenSession
                        byte[] open = readPacket(command.getInputStream());
                        assertEquals(0x1002, operationOf(open));
                        send(command.getOutputStream(),
                                new PtpIpPacket.OperationResponse(0x2001, transactionOf(open), new long[0]));
                        // GetObject: send the complete valid response, then drop the command socket.
                        byte[] get = readPacket(command.getInputStream());
                        assertEquals(0x1009, operationOf(get));
                        long tx = transactionOf(get);
                        send(command.getOutputStream(), new PtpIpPacket.StartData(tx, object.length));
                        send(command.getOutputStream(), new PtpIpPacket.Data(tx, new DataBuffer(object)));
                        send(command.getOutputStream(), new PtpIpPacket.EndData(tx, new DataBuffer()));
                        send(command.getOutputStream(), new PtpIpPacket.OperationResponse(0x2001, tx, new long[0]));
                        command.getOutputStream().flush();
                        command.close(); // EOF arrives after every valid byte on this stream
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
                final CountDownLatch firstWrite = new CountDownLatch(1);
                final CountDownLatch release = new CountDownLatch(1);
                final ByteArrayOutputStream received = new ByteArrayOutputStream();
                OutputStream slow = new OutputStream() {
                    boolean first = true;
                    private void gate() throws IOException {
                        if (!first) return;
                        first = false;
                        firstWrite.countDown();
                        try {assertTrue("consumer was not released", release.await(8, TimeUnit.SECONDS));}
                        catch (InterruptedException e) {throw new IOException(e);}
                    }
                    @Override public void write(int b) throws IOException {gate(); received.write(b);}
                    @Override public void write(byte[] b, int off, int len) throws IOException {gate(); received.write(b, off, len);}
                };

                PtpOperation.Request get = PtpOperation.createRequest(PtpOperation.OPSCODE_GetObject);
                get.setParameters(new long[]{42});
                Future<Throwable> pending = caller.submit(() -> {
                    try {session.executeTransaction(get, null, slow); return null;}
                    catch (Throwable t) {return t;}
                });

                assertTrue("download should have started", firstWrite.await(5, TimeUnit.SECONDS));
                // Wait until the transport has processed the EOF terminal while the consumer is still blocked.
                long deadline = System.currentTimeMillis() + 5000;
                while (!transport.isClosed() && System.currentTimeMillis() < deadline) Thread.sleep(10);
                assertTrue("terminal cause must be recorded before releasing the consumer", transport.isClosed());
                release.countDown();

                Throwable failure = pending.get(5, TimeUnit.SECONDS);
                assertNull("a response fully queued before EOF must not be lost: " + failure, failure);
                assertArrayEquals(object, received.toByteArray());
            } finally {
                transport.close();
                caller.shutdownNow();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. OpenSession SessionAlreadyOpen (0x201E) is a failure carrying the response code + SessionID

    @Test(timeout = 20000) public void openSessionAlreadyOpenIsFailureWithSessionId() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            Future<?> server = worker.submit(() -> {
                try (Socket command = listener.accept()) {
                    command.setSoTimeout(10000);
                    readPacket(command.getInputStream());
                    send(command.getOutputStream(), new PtpIpPacket.InitCommandAck(1, hostId()));
                    try (Socket event = listener.accept()) {
                        event.setSoTimeout(10000);
                        readPacket(event.getInputStream());
                        send(event.getOutputStream(), new PtpIpPacket.InitEventAck());
                        byte[] request = readPacket(command.getInputStream());
                        assertEquals(0x1002, operationOf(request));
                        send(command.getOutputStream(),
                                new PtpIpPacket.OperationResponse(0x201E, transactionOf(request), new long[]{7}));
                        Thread.sleep(500);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });

            PtpIpConnection transport = connect(listener);
            try {
                transport.openSession();
                fail("SessionAlreadyOpen must not be treated as a successful OpenSession");
            } catch (PtpIpExceptions.OperationFailed e) {
                assertEquals(0x201E, e.getResponseCode());
                assertArrayEquals(new long[]{7}, e.getResponseParameters());
            } finally {
                transport.close();
            }
            server.get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Strict validation is retained: only exactly one SessionID is allowed for 0x201E

    @Test(timeout = 20000) public void openSessionAlreadyOpenWithWrongParamCountIsRejected() throws Exception {
        assertRejected(0x201E, new long[]{7, 8}, "SessionAlreadyOpen with two parameters");
    }

    @Test(timeout = 20000) public void openSessionSuccessWithUnexpectedParameterIsRejected() throws Exception {
        assertRejected(0x2001, new long[]{1}, "success response carrying a parameter");
    }

    private void assertRejected(int responseCode, long[] responseParameters, String label) throws Exception {
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
                        byte[] request = readPacket(command.getInputStream());
                        send(command.getOutputStream(),
                                new PtpIpPacket.OperationResponse(responseCode, transactionOf(request), responseParameters));
                        Thread.sleep(300);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });

            PtpIpConnection transport = connect(listener);
            try {
                transport.openSession();
                fail("must reject " + label);
            } catch (PtpTransport.TransportError expected) {
                // ProtocolViolation (TransportDataError) for 0x201E with wrong arity, or
                // PtpProtocolViolation for a success carrying an unexpected parameter.
            } catch (PtpExceptions.PtpProtocolViolation expected) {
                // success with a parameter is rejected by strict response validation
            } finally {
                transport.close();
            }
        } finally {
            worker.shutdownNow();
        }
    }
}
