package io.github.ayatinene.sonycompanion;

import com.fimagena.libptp.ptpip.PtpIpPacket;
import com.fimagena.libptp.ptpip.TcpConnection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * TcpConnection close/connect race: a close() must be able to unblock an in-flight (blocking) connect
 * by closing the already-published pending socket, and a socket created after close() must be closed
 * immediately instead of leaking.
 */
public class TcpConnectionRaceTest {

    private static final class GatedSocket extends Socket {
        final CountDownLatch connectEntered = new CountDownLatch(1);
        final CountDownLatch releaseConnect = new CountDownLatch(1);
        volatile boolean closed = false;
        volatile boolean connectCalled = false;

        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
            connectCalled = true;
            connectEntered.countDown();
            try {if (!releaseConnect.await(5, TimeUnit.SECONDS)) throw new SocketTimeoutException("test gate");}
            catch (InterruptedException e) {Thread.currentThread().interrupt(); throw new IOException(e);}
            if (closed) throw new SocketException("Socket closed");
        }
        @Override public void close() {closed = true; releaseConnect.countDown();}
        @Override public boolean isClosed() {return closed;}
        @Override public boolean isConnected() {return connectCalled && !closed;}
        @Override public InputStream getInputStream() {return new ByteArrayInputStream(new byte[0]);}
        @Override public OutputStream getOutputStream() {return new ByteArrayOutputStream();}
    }

    private static final class GatedFactory extends SocketFactory {
        final List<GatedSocket> created = new CopyOnWriteArrayList<>();
        final CountDownLatch createdLatch = new CountDownLatch(1);

        @Override public Socket createSocket() {
            GatedSocket socket = new GatedSocket();
            created.add(socket);
            createdLatch.countDown();
            return socket;
        }
        @Override public Socket createSocket(String host, int port) {return createSocket();}
        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {return createSocket();}
        @Override public Socket createSocket(InetAddress host, int port) {return createSocket();}
        @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {return createSocket();}
    }

    private static TcpConnection connection(GatedFactory factory) {
        TcpConnection connection = new TcpConnection(new LinkedBlockingQueue<PtpIpPacket>());
        connection.setSocketFactory(factory);
        return connection;
    }

    @Test(timeout = 10000) public void closeUnblocksABlockingConnect() throws Exception {
        GatedFactory factory = new GatedFactory();
        TcpConnection connection = connection(factory);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> pending = worker.submit(() -> {
                try {connection.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), 15740)); return null;}
                catch (Throwable t) {return t;}
            });
            assertTrue("a socket must be created", factory.createdLatch.await(5, TimeUnit.SECONDS));
            GatedSocket socket = factory.created.get(0);
            assertTrue("connect must have been entered", socket.connectEntered.await(5, TimeUnit.SECONDS));
            long start = System.currentTimeMillis();
            connection.close();
            Throwable failure = pending.get(5, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("close must unblock the connect quickly, took " + elapsed + "ms", elapsed < 3000);
            assertNotNull("connect must fail once closed", failure);
            assertTrue("expected an IO failure, got: " + failure, failure instanceof IOException);
            assertTrue("the published pending socket must be closed", socket.closed);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test(timeout = 10000) public void socketCreatedAfterCloseIsClosedImmediately() throws Exception {
        GatedFactory factory = new GatedFactory();
        TcpConnection connection = connection(factory);
        connection.close();
        try {
            connection.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), 15740));
            fail("a connect after close must fail");
        } catch (IOException expected) {
            // expected
        }
        assertEquals(1, factory.created.size());
        GatedSocket socket = factory.created.get(0);
        assertTrue("a socket created after close must be closed immediately", socket.closed);
        assertFalse("it must never be connected", socket.connectCalled);
    }
}
