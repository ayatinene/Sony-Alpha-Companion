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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;


public class TcpConnection {
    private volatile Socket mSocket;
    private javax.net.SocketFactory mSocketFactory = javax.net.SocketFactory.getDefault();
    public void setSocketFactory(javax.net.SocketFactory factory) { mSocketFactory = factory; }
    private OutputStream mOut;
    private InputStream mIn;
    private long mLastActivityTimestamp;
    private volatile boolean mIsClosed = false;

    // Serializes only the socket publish (after the blocking connect) with close(), so a socket
    // created concurrently with close() is closed instead of leaking. The blocking connect itself
    // is deliberately performed outside this lock.
    private final Object mSocketLock = new Object();

    private final static Logger LOG = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private BlockingQueue<PtpIpPacket> mPacketOutQueue;

    private void putBlocking(PtpIpPacket packet) {
        while (!mIsClosed) {
            try {if (mPacketOutQueue.offer(packet, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) return;}
            catch (InterruptedException e) {Thread.currentThread().interrupt(); return;}
        }
    }


    private class TcpListener extends Thread {

        private PtpIpPacket.ReadingListener mReadingListener = new PtpIpPacket.ReadingListener() {
            @Override public void onLoaded(PtpIpPacket packet, int loadedBytes) {
                putBlocking(new PtpIpPacket.LoadStatus(packet, loadedBytes));
            }
        };

        public void run() {
            // Capture the streams locally: close() may null the fields while this listener is running.
            InputStream in;
            OutputStream out;
            synchronized (mSocketLock) {in = mIn; out = mOut;}
            if (in == null) return;
            PtpIpPacket packet;
            PtpPacketReader reader = new PtpPacketReader(in);
            while (true) {
                try {
                    packet = reader.next();
                    packet.setSourceConnection(TcpConnection.this);
                    mLastActivityTimestamp = System.currentTimeMillis();
                    LOG.info("PTPIP: Packet in  <== " + safeDescription(packet));
                    putBlocking(packet);
                }
                catch (IOException | PtpIpExceptions.MalformedPacket e) {
                    // did we trigger this ourselves? --> shutdown listener and don't notify
                    if ((e instanceof IOException) && mIsClosed) return;

                    // something happened --> shutdown send error-packet upwards
                    LOG.severe("PTPIP: Error when receiving packet - closing connection! (" + e.getMessage() + ")");
                    try {in.close();} catch (Exception ex) {}
                    try {if (out != null) out.close();} catch (Exception ex) {}

                    packet = new PtpIpPacket.Error(e);
                    packet.setSourceConnection(TcpConnection.this);
                    putBlocking(packet);
                    return;
                }
            }
        }
    }


    public TcpConnection(BlockingQueue<PtpIpPacket> receivedPacketQueue) {
        mPacketOutQueue = receivedPacketQueue;
    }

    public void connect(InetSocketAddress server) throws IOException {
        Socket socket = null;
        try {
            socket = mSocketFactory.createSocket();
            socket.setSoTimeout(0);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            // Publish the freshly created (not yet connected) socket under the same lock as close(), so
            // a concurrent close() can close it and unblock the connect below. The blocking connect is
            // deliberately performed outside the lock.
            synchronized (mSocketLock) {
                if (mIsClosed) {
                    try {socket.close();} catch (Exception ignored) {}
                    throw new IOException("TCP connection closed during connect");
                }
                mSocket = socket;
            }
            socket.connect(server, 8000);
            synchronized (mSocketLock) {
                // Re-check: a close() during the connect may have closed the socket already.
                if (mIsClosed) {
                    try {socket.close();} catch (Exception ignored) {}
                    throw new IOException("TCP connection closed during connect");
                }
                mOut = socket.getOutputStream();
                mIn = socket.getInputStream();
            }
            mLastActivityTimestamp = System.currentTimeMillis();
        } catch (IOException e) {
            if (socket != null) {try {socket.close();} catch (Exception ignored) {}}
            LOG.severe("Error on establishing TCP connection - closing! (" + e.getMessage() + ")");
            close();
            throw e;
        }
        new TcpListener().start();
    }

    public void sendPacket(PtpIpPacket packet) throws IOException {
        LOG.info("PTPIP: Packet out ==> " + safeDescription(packet));
        Socket socket = mSocket;
        OutputStream out = mOut;
        if ((socket == null) || (socket.isClosed()) || (!socket.isConnected()) || (out == null)) throw new IOException();
        out.write(packet.serializePacket());
        out.flush();
        mLastActivityTimestamp = System.currentTimeMillis();
    }

    /** Init packets carry the host GUID / friendly name and Data/EndData carry payload bytes, so never render them. */
    private static String safeDescription(PtpIpPacket packet) {
        if ((packet instanceof PtpIpPacket.InitCommandRequest) || (packet instanceof PtpIpPacket.InitCommandAck)
                || (packet instanceof PtpIpPacket.InitEventRequest) || (packet instanceof PtpIpPacket.InitEventAck)
                || (packet instanceof PtpIpPacket.InitFail)
                || (packet instanceof PtpIpPacket.Data) || (packet instanceof PtpIpPacket.EndData))
            return packet.getClass().getSimpleName() + "[payload omitted]";
        return packet.toString();
    }

    public void close() {
        LOG.info("PTPIP: Closing TCP connection");
        Socket socket;
        InputStream in;
        OutputStream out;
        synchronized (mSocketLock) {
            mIsClosed = true;
            socket = mSocket; mSocket = null;
            in = mIn; mIn = null;
            out = mOut; mOut = null;
        }
        try {if (in != null) in.close();} catch (Exception e) {}
        try {if (out != null) out.close();} catch (Exception e) {}
        try {if (socket != null) socket.close();} catch (Exception e) {}
    }

    public long getLastActivityTimestamp() {return mLastActivityTimestamp;}
}