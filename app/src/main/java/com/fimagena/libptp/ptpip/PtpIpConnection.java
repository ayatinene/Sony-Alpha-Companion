/*  Copyright (C) 2017 Fimagena (fimagena at gmail dot com)

    This file is part of libptp.

    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU Lesser General Public
    License as published by the Free Software Foundation; either
    version 2.1 of the License, or (at your option) any later version.

    This library is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public
    License along with this library; see the file COPYING.  If not, 
    see <http://www.gnu.org/licenses/>.
*/

package com.fimagena.libptp.ptpip;

import com.fimagena.libptp.PtpDataType;
import com.fimagena.libptp.PtpExceptions;
import com.fimagena.libptp.PtpLogObserver;
import com.fimagena.libptp.PtpLogRecord;
import com.fimagena.libptp.PtpOperation;
import com.fimagena.libptp.PtpEvent;
import com.fimagena.libptp.PtpTransport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;


public class PtpIpConnection extends PtpTransport {
    public final static int MAX_QUEUE_SIZE = 8;

    private final static int PTP_VERSION_MAJOR = 1;
    private final static int PTP_VERSION_MINOR = 0;
    private final static int PTP_PORT = 15740;

    private final static int SESSION_ID = 1; // only one session per connection for PtpIp - use this id

    private final static long PING_TIMEGAP = 20000; // ping after 20 seconds of inactivity

    private final static Logger LOG = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    static {LOG.setLevel(Level.SEVERE);}


    public static class PtpIpAddress extends PtpTransport.ResponderAddress {
        protected InetSocketAddress mTcpAddress;

        public PtpIpAddress(InetAddress address) {this(address, PTP_PORT);}
        public PtpIpAddress(InetAddress address, int port) {mTcpAddress = new InetSocketAddress(address, port);}

        public String toString() {return mTcpAddress.toString();}
    }


    public static class PtpIpHostId extends PtpTransport.HostId {
        protected short[] mGuid;
        protected String mFriendlyName;
        protected int mVersionMajor, mVersionMinor;

        public PtpIpHostId(short[] guid, String friendlyName) {this(guid, friendlyName, PTP_VERSION_MAJOR, PTP_VERSION_MINOR);}
        public PtpIpHostId(short[] guid, String friendlyName, int versionMajor, int versionMinor) {
            mGuid = guid;
            mFriendlyName = friendlyName;
            mVersionMajor = versionMajor;
            mVersionMinor = versionMinor;
        }
    }


    public void setSocketFactory(javax.net.SocketFactory factory) { mCommandConnection.setSocketFactory(factory); mEventConnection.setSocketFactory(factory); }

    private PtpIpAddress mAddress;
    private PtpIpHostId mHostId;

    private TcpConnection mCommandConnection;
    private TcpConnection mEventConnection;

    // TcpConnections send us PtpPackets in our incoming queue...
    private BlockingDeque<PtpIpPacket> mPacketInQueue             = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);

    // ...which we then distribute into various outgoing queues
    private BlockingQueue<PtpIpPacket> mInitPacketOutQueue        = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private BlockingDeque<PtpIpPacket> mTransactionPacketOutQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
    private BlockingQueue<PtpEvent>    mEventOutQueue             = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    @Override public BlockingQueue<PtpEvent> getEventQueue() {return mEventOutQueue;}

    private enum ConnectionStatus {INITIALIZED, CONNECTED, CLOSED}
    private volatile ConnectionStatus mStatus;

    private PtpIpSession mSingleSession;

    private volatile PtpIpPacketListener mPtpIpPacketListener;

    // Close/failure bookkeeping. mClosed guarantees close() is idempotent; mTerminalCause
    // remembers the first terminal cause (original transport failure or close reason) so a later
    // generic close can never mask it. Valid packets already queued are never discarded.
    private final Object mCloseLock = new Object();
    private volatile boolean mClosed = false;
    private volatile Exception mTerminalCause;

    private volatile PtpLogObserver mLogObserver;

    /** Attach an optional simple log observer. Safe to call at any time; null detaches. */
    public void setLogObserver(PtpLogObserver observer) {mLogObserver = observer;}

    /** First terminal cause recorded for this connection, or null while it is healthy. */
    Exception getTerminalCause() {return mTerminalCause;}

    /** Record the first terminal cause; returns the effective (first-wins) cause. */
    private Exception recordTerminal(Exception cause) {
        synchronized (mCloseLock) {
            if (mTerminalCause == null) mTerminalCause = cause;
            return mTerminalCause;
        }
    }

    /** Route one structured record to the observer. Never lets an observer failure break the protocol. */
    void log(PtpLogRecord record) {
        PtpLogObserver observer = mLogObserver;
        if ((observer == null) || (record == null)) return;
        try {observer.report(record.level, record.toString(), record.error);}
        catch (Throwable ignored) { /* logging must never break the protocol */ }
    }

    private class PtpIpPacketListener extends Thread {

        private void putBlocking(BlockingQueue<PtpIpPacket> queue, PtpIpPacket packet) {
            while (!mClosed) {
                try {if (queue.offer(packet, 100, TimeUnit.MILLISECONDS)) return;}
                catch (InterruptedException e) {Thread.currentThread().interrupt(); return;}
            }
        }

        private void putFirstBlocking(BlockingDeque<PtpIpPacket> queue, PtpIpPacket packet) {
            while (!mClosed) {
                try {if (queue.offerFirst(packet, 100, TimeUnit.MILLISECONDS)) return;}
                catch (InterruptedException e) {Thread.currentThread().interrupt(); return;}
            }
        }

        private void putEvent(PtpEvent event) {
            if (mEventOutQueue == null) return;
            while (!mClosed) {
                try {if (mEventOutQueue.offer(event, 100, TimeUnit.MILLISECONDS)) return;}
                catch (InterruptedException e) {Thread.currentThread().interrupt(); return;}
            }
        }

        public void run() {
            PtpIpPacket packet = null;
            while (mStatus != ConnectionStatus.CLOSED) {

                // ---------------------------------------------------------------------------------
                // Check time since last traffic and send ping if over timeout value

                long remaining = PING_TIMEGAP - (System.currentTimeMillis() - Math.max(mCommandConnection.getLastActivityTimestamp(), mEventConnection.getLastActivityTimestamp()));
                if (remaining <= 0) {
                    // Sending ping when timing out; however, we're ignoring the pongs
                    try {mEventConnection.sendPacket(new PtpIpPacket.ProbeRequest());} catch (IOException e) {}
                    remaining = PING_TIMEGAP;
                }
                try {packet = mPacketInQueue.poll(remaining, TimeUnit.MILLISECONDS);} catch (InterruptedException e) {continue;}
                if (packet == null) continue;

                // ---------------------------------------------------------------------------------
                // Process packets according to type

                // ---------------------------------------------------------------------------------
                // Internal packets

                // if LoadStatus for a transaction --> send onwards
                if (packet instanceof PtpIpPacket.LoadStatus) {
                    if (((PtpIpPacket.LoadStatus) packet).mLoadedPacket instanceof PtpIpPacket.TransactionPacket)
                        putBlocking(mTransactionPacketOutQueue, packet);
                }

                // if Error --> something happened, let's close down and tell everybody
                else if (packet instanceof PtpIpPacket.Error) {
                    // Preserve the original transport exception as the first failure delivered
                    // to every waiter (init and transaction) instead of a generic close reason.
                    failConnection(((PtpIpPacket.Error) packet).mException);
                }

                // ---------------------------------------------------------------------------------
                // Probing packets

                // if Ping --> send Pong
                else if (packet instanceof PtpIpPacket.ProbeRequest) {
                    try {packet.getSourceConnection().sendPacket(new PtpIpPacket.ProbeResponse());}
                    catch (IOException e) {}
                }

                // if Pong --> ignore
                else if (packet instanceof PtpIpPacket.ProbeResponse) {}

                // ---------------------------------------------------------------------------------
                // Init packets

                // if InitPacket --> check for correct state and send onwards
                else if (packet instanceof PtpIpPacket.InitPacket) {
                    if (mStatus == ConnectionStatus.INITIALIZED) putBlocking(mInitPacketOutQueue, packet);
                    else {
                        LOG.severe("PTPIP: Protocol violation (received InitPacket but not in Init state) - closing connection!");
                        putFirstBlocking(mPacketInQueue, new PtpIpPacket.Error(new PtpIpExceptions.ProtocolViolation("Wrong PacketType: Received InitPacket but not in Init state!")));
                    }
                }

                // ---------------------------------------------------------------------------------
                // Transaction packets

                // if Event --> send a layer upwards (and cancel also onwards to be sure)
                else if (packet instanceof PtpIpPacket.Event) {
                    PtpIpPacket.Event eventPacket = (PtpIpPacket.Event) packet;
                    putEvent(new PtpEvent(new PtpDataType.EventCode(eventPacket.mEventCode), new PtpDataType.UInt32(eventPacket.mTransactionId), eventPacket.mParameters));
                    if (eventPacket.mEventCode == PtpEvent.EVENTCODE_CancelTransaction) putBlocking(mTransactionPacketOutQueue, packet);
                }

                // if TransactionPacket --> check for correct state and send onwards
                else if (packet instanceof PtpIpPacket.TransactionPacket) {
                    if (mStatus == ConnectionStatus.CONNECTED) putBlocking(mTransactionPacketOutQueue, packet);
                    else {
                        LOG.severe("PTPIP: Protocol violation (received TransactionPacket but not in Connected state) - closing connection!");
                        putFirstBlocking(mPacketInQueue, new PtpIpPacket.Error(new PtpIpExceptions.ProtocolViolation("Wrong PacketType: Received TransactionPacket but not in Connected state!")));
                    }
                }

                // ---------------------------------------------------------------------------------
                // if Other --> can't happen, there is nothing else --> Error
                else {
                    LOG.severe("PTPIP: Encountered unknown internal packet type!");
                    putFirstBlocking(mPacketInQueue, new PtpIpPacket.Error(new PtpIpExceptions.MalformedPacket("Unknown internal packet type!")));
                }
            }

            // We're done listening, remove reference - not strictly necessary but the Thread is toast - might as well clean up resources
            mPtpIpPacketListener = null;
        }
    }

    public PtpIpConnection() {this(null);}
    public PtpIpConnection(BlockingQueue<PtpEvent> eventOutQueue){
        // we use only one packet-queue for both TCP-channels
        mCommandConnection = new TcpConnection(mPacketInQueue);
        mEventConnection   = new TcpConnection(mPacketInQueue);

        // ability to set EventOutQueue only used for add'l session based on add'l connection with same EventQueue
        if (eventOutQueue != null) mEventOutQueue = eventOutQueue;

        mSingleSession = new PtpIpSession(this, mTransactionPacketOutQueue);

        mStatus = ConnectionStatus.INITIALIZED;
    }

    @Override public PtpDataType.DeviceInfoDataSet getDeviceInfo() throws TransportDataError, TransportIOError, TransportOperationFailed, PtpExceptions.PtpProtocolViolation {
        PtpOperation.Response response = mSingleSession.executeNullTransaction(PtpOperation.createRequest(PtpOperation.OPSCODE_GetDeviceInfo));
        response.validate();
        if (!response.isSuccess()) throw new PtpIpExceptions.OperationFailed("GetDeviceInfo", response.getResponseCode());
        return (PtpDataType.DeviceInfoDataSet) response.getData();
    }

    @Override public PtpTransport.Session openSession() throws TransportDataError, TransportIOError, TransportOperationFailed, PtpExceptions.PtpProtocolViolation {
        if (mSingleSession.isOpened()) {
            PtpIpConnection connection = new PtpIpConnection(mEventOutQueue);
            connection.setLogObserver(mLogObserver);
            connection.connect(mAddress, mHostId);
            return connection.openSession();
            //FIXME: add'l connection is never properly closed - no reference is being held
        }

        long start = System.currentTimeMillis();
        PtpOperation.Request request = PtpOperation.createRequest(PtpOperation.OPSCODE_OpenSession);
        request.setParameters(new long[]{SESSION_ID});
        PtpOperation.Response response = mSingleSession.executeNullTransaction(request);
        int responseCode = response.getResponseCode();

        // Known PTP/IP spec special case: SessionAlreadyOpen is the only response that
        // carries a single SessionID parameter. It is still a failure (the camera holds a
        // live session), never a success, and must surface the response code + SessionID.
        if (responseCode == PtpOperation.RSPCODE_SessionAlreadyOpen) {
            long[] responseParameters = response.getParameters();
            if ((responseParameters == null) || (responseParameters.length != 1)) {
                throw new PtpIpExceptions.ProtocolViolation(
                        "SessionAlreadyOpen (0x201E) response must carry exactly one SessionID parameter!");
            }
            log(PtpLogRecord.builder(PtpLogRecord.Level.WARN, "TRANSACTION")
                    .message("OpenSession refused: camera session still open")
                    .operationCode(PtpOperation.OPSCODE_OpenSession)
                    .responseCode(responseCode)
                    .parameters(responseParameters)
                    .durationMillis(System.currentTimeMillis() - start)
                    .build());
            throw new PtpIpExceptions.OperationFailed("OpenSession", responseCode, responseParameters);
        }

        response.validate();
        if (!response.isSuccess()) {
            log(PtpLogRecord.builder(PtpLogRecord.Level.ERROR, "TRANSACTION")
                    .message("OpenSession failed")
                    .operationCode(PtpOperation.OPSCODE_OpenSession)
                    .responseCode(responseCode)
                    .durationMillis(System.currentTimeMillis() - start)
                    .build());
            throw new PtpIpExceptions.OperationFailed("OpenSession", responseCode);
        }
        mSingleSession.setOpened(true);

        log(PtpLogRecord.builder(PtpLogRecord.Level.INFO, "TRANSACTION")
                .message("OpenSession succeeded")
                .operationCode(PtpOperation.OPSCODE_OpenSession)
                .responseCode(responseCode)
                .durationMillis(System.currentTimeMillis() - start)
                .build());

        return mSingleSession;
    }

    protected void sendCommandChannelPacket(PtpIpPacket packet) throws IOException {mCommandConnection.sendPacket(packet);}
    protected void sendEventChannelPacket(PtpIpPacket.Event packet) throws IOException {mEventConnection.sendPacket(packet);}

    private PtpIpPacket.InitPacket connectChannel(String channelName, TcpConnection tcpConnection, InetSocketAddress address, PtpIpPacket.InitPacket initPacket, Class expectedAnswer)
            throws PtpIpExceptions.IOError, PtpIpExceptions.MalformedPacket, PtpIpExceptions.ProtocolViolation, PtpIpExceptions.OperationFailed {
        long start = System.currentTimeMillis();
        try {
            tcpConnection.connect(address);
            tcpConnection.sendPacket(initPacket);
        }
        catch (IOException e) {
            close();
            log(PtpLogRecord.builder(PtpLogRecord.Level.ERROR, channelName)
                    .message("Channel connect failed")
                    .durationMillis(System.currentTimeMillis() - start)
                    .error(e).build());
            throw new PtpIpExceptions.IOError("Could not connect channel!", e);
        }

        // takeBlocking; a concurrent terminal (close/failure) surfaces its original cause instead
        // of a fabricated timeout, but only once any already-queued init packet was consumed.
        Exception terminal = mTerminalCause;
        if ((terminal != null) && mInitPacketOutQueue.isEmpty()) PtpIpExceptions.throwTerminal(terminal);
        PtpIpPacket packet = null;
        try {packet = mInitPacketOutQueue.poll(10, TimeUnit.SECONDS);} catch (InterruptedException e) {Thread.currentThread().interrupt(); throw new PtpIpExceptions.IOError(new IOException("PTP init interrupted"));}
        if (packet == null) {
            terminal = mTerminalCause;
            if (terminal != null) PtpIpExceptions.throwTerminal(terminal);
            PtpIpExceptions.IOError timeout = new PtpIpExceptions.IOError(new java.net.SocketTimeoutException("PTP init timed out"));
            log(PtpLogRecord.builder(PtpLogRecord.Level.ERROR, channelName)
                    .message("Channel init timed out")
                    .durationMillis(System.currentTimeMillis() - start)
                    .error(timeout).build());
            throw timeout;
        }

        if (packet instanceof PtpIpPacket.InitFail) {
            PtpIpExceptions.OperationFailed failed = new PtpIpExceptions.OperationFailed("InitRequest", ((PtpIpPacket.InitFail) packet).mReason);
            log(PtpLogRecord.builder(PtpLogRecord.Level.ERROR, channelName)
                    .message("Channel init refused by responder")
                    .durationMillis(System.currentTimeMillis() - start)
                    .error(failed).build());
            throw failed;
        }
        try {
            PtpIpExceptions.testError(packet);
            PtpIpExceptions.testType(packet, expectedAnswer);
        }
        catch (PtpIpExceptions.IOError | PtpIpExceptions.MalformedPacket | PtpIpExceptions.ProtocolViolation e) {
            log(PtpLogRecord.builder(PtpLogRecord.Level.ERROR, channelName)
                    .message("Channel init failed")
                    .durationMillis(System.currentTimeMillis() - start)
                    .error(e).build());
            throw e;
        }

        log(PtpLogRecord.builder(PtpLogRecord.Level.INFO, channelName)
                .message("Channel initialized")
                .durationMillis(System.currentTimeMillis() - start)
                .build());

        return (PtpIpPacket.InitPacket) packet;
    }

    @Override public boolean isConnected() {return mStatus == ConnectionStatus.CONNECTED;}
    @Override public void connect(PtpTransport.ResponderAddress address, PtpTransport.HostId hostId)
            throws PtpIpExceptions.IOError, PtpIpExceptions.MalformedPacket, PtpIpExceptions.ProtocolViolation, PtpIpExceptions.OperationFailed {
        mHostId = (PtpIpHostId) hostId;
        mAddress = (PtpIpAddress) address;

        mPtpIpPacketListener = new PtpIpPacketListener();
        mPtpIpPacketListener.start();

        // -----------------------------------------------------------------------------------------
        // Open command and event channel

        long start = System.currentTimeMillis();
        try {
            PtpIpPacket.InitPacket initAck = connectChannel("INIT_COMMAND", mCommandConnection, mAddress.mTcpAddress, new PtpIpPacket.InitCommandRequest(mHostId), PtpIpPacket.InitCommandAck.class);
            long mConnectionNumber = ((PtpIpPacket.InitCommandAck) initAck).mConnectionNumber;
            connectChannel("INIT_EVENT", mEventConnection, mAddress.mTcpAddress, new PtpIpPacket.InitEventRequest(mConnectionNumber), PtpIpPacket.InitEventAck.class);
        }
        catch (PtpIpExceptions.IOError | PtpIpExceptions.MalformedPacket | PtpIpExceptions.ProtocolViolation | PtpIpExceptions.OperationFailed e) {
            close();
            log(PtpLogRecord.builder(PtpLogRecord.Level.ERROR, "CONNECT")
                    .message("Connect failed")
                    .durationMillis(System.currentTimeMillis() - start)
                    .error(e).build());
            throw e;
        }
        // Atomically commit success or detect a concurrent close: the mClosed check and the
        // mStatus transition share mCloseLock, so a close() interleaving can never be undone by a
        // late "CONNECTED" write.
        boolean aborted;
        synchronized (mCloseLock) {
            aborted = mClosed;
            if (!aborted) mStatus = ConnectionStatus.CONNECTED;
        }
        if (aborted) {
            IOException closeCause = new IOException("Connection closed during connect", mTerminalCause);
            PtpIpExceptions.IOError error = new PtpIpExceptions.IOError(closeCause);
            log(PtpLogRecord.builder(PtpLogRecord.Level.ERROR, "CONNECT")
                    .message("Connect aborted by concurrent close")
                    .durationMillis(System.currentTimeMillis() - start)
                    .error(error).build());
            throw error;
        }

        log(PtpLogRecord.builder(PtpLogRecord.Level.INFO, "CONNECT")
                .message("Connected")
                .durationMillis(System.currentTimeMillis() - start)
                .build());
    }


    @Override public boolean isClosed() {return mStatus == ConnectionStatus.CLOSED;}

    /**
     * Idempotent teardown. Returns true only for the call that actually performed the
     * teardown, so repeated/concurrent close() calls never re-deliver or re-close.
     */
    private boolean teardown() {
        PtpIpPacketListener listener;
        synchronized (mCloseLock) {
            if (mClosed) return false;
            mClosed = true;
            mStatus = ConnectionStatus.CLOSED;
            listener = mPtpIpPacketListener;
        }
        if (listener != null) listener.interrupt();
        mSingleSession.setOpened(false);
        mEventConnection.close();
        mCommandConnection.close();
        return true;
    }

    /**
     * Wake every init/transaction waiter with the given terminal cause without discarding valid
     * packets that are already queued: the terminal marker is appended at the <em>tail</em>, so a
     * completed last response (EndData + OperationResponse OK) still wins. Delivery is
     * non-blocking, so a full bounded queue can never deadlock the caller; a waiter that drains the
     * queue and finds it empty falls back to {@link #getTerminalCause()}.
     */
    private void wakeWaiters(Exception cause) {
        PtpIpPacket error = new PtpIpPacket.Error(cause);
        mInitPacketOutQueue.offer(error);
        mTransactionPacketOutQueue.offerLast(error);
        // Non-blocking: an error event must never stall the listener on a full queue.
        if (mEventOutQueue != null) mEventOutQueue.offer(new PtpEvent.Error(cause));
    }

    /**
     * Called by the packet listener when the transport reports a genuine failure. The original
     * exception is remembered (first failure wins) and delivered after any already-queued valid
     * packets; a later close() cannot mask it with a generic "Connection closed".
     */
    private void failConnection(Exception cause) {
        Exception effective = recordTerminal(cause);
        teardown();
        wakeWaiters(effective);
        log(PtpLogRecord.builder(PtpLogRecord.Level.ERROR, "TRANSPORT")
                .message("Connection failed")
                .error(effective).build());
    }

    public void close() {
        // Record a close reason first (never overwriting a genuine failure), so waiters that drain
        // the queue still get a cause even if the terminal marker could not be enqueued.
        Exception cause = recordTerminal(new IOException("Connection closed"));
        if (!teardown()) return; // repeated / concurrent close is a no-op

        wakeWaiters(cause);

        log(PtpLogRecord.builder(PtpLogRecord.Level.INFO, "CLOSE")
                .message("Connection closed")
                .build());
    }
}
