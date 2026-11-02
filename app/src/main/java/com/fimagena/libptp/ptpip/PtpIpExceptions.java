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

import com.fimagena.libptp.PtpTransport;

import java.io.IOException;


public class PtpIpExceptions {

    // ---------------------------------------------------------------------------------------------
    // Assert functions

    public static void testError(PtpIpPacket packet) throws IOError, MalformedPacket, ProtocolViolation {
        if (packet instanceof PtpIpPacket.Error) {
            Exception e = ((PtpIpPacket.Error)packet).mException;
            // Preserve the original exception type (and its cause chain) so callers
            // can tell a genuine socket/EOF failure apart from a protocol violation.
            if (e instanceof IOError) throw (IOError) e;
            if (e instanceof ProtocolViolation) throw (ProtocolViolation) e;
            if (e instanceof MalformedPacket) throw (MalformedPacket) e;
            if (e instanceof IOException) throw new IOError((IOException) e);
            throw new MalformedPacket("Unexpected internal error: " + e, e);
        }
    }

    /**
     * Surface a remembered terminal connection cause (original transport failure or close reason)
     * as the appropriate checked exception, preserving its type and cause chain.
     */
    public static void throwTerminal(Exception terminal) throws IOError, MalformedPacket, ProtocolViolation {
        testError(new PtpIpPacket.Error(terminal));
        // testError always throws for an Error packet; defensive fallback for the compiler.
        throw new IOError(new IOException("Connection failed", terminal));
    }

    public static void testType(PtpIpPacket packet, Class expectedClass) throws ProtocolViolation {
        if (!expectedClass.isAssignableFrom(packet.getClass()))
            throw new ProtocolViolation("Wrong PacketType: Expected " + expectedClass.getSimpleName() + ", received " + packet.getClass().getSimpleName());
    }

    // ---------------------------------------------------------------------------------------------
    // PtpIp Exception classes


    public static class IOError extends PtpTransport.TransportIOError {
        public IOError(IOException e) {super(e.toString(), e);}
        public IOError(String s, IOException e) {super(s, e);}
    }

    public static class OperationFailed extends PtpTransport.TransportOperationFailed {
        private final long mResponseCode;
        private final long[] mResponseParameters;

        public OperationFailed(String s, long responseCode) {this(s, responseCode, new long[0]);}
        public OperationFailed(String s, long responseCode, long[] responseParameters) {
            super("Responder failed on " + s + " (Response: " + String.format("0x%08x", responseCode) + ")!");
            mResponseCode = responseCode;
            mResponseParameters = responseParameters == null ? new long[0] : responseParameters.clone();
        }

        /** Response code that caused the failure (e.g. 0x201E SessionAlreadyOpen). */
        public long getResponseCode() {return mResponseCode;}
        /** Response parameters received with the failing response (e.g. the SessionID). */
        public long[] getResponseParameters() {return mResponseParameters.clone();}
    }

    public static class ProtocolViolation extends PtpTransport.TransportDataError {
        public ProtocolViolation(String s) {super(s);}
        public ProtocolViolation(String s, Throwable cause) {super(s, cause);}
    }

    public static class MalformedPacket extends PtpTransport.TransportDataError {
        public MalformedPacket(String s) {super(s);}
        public MalformedPacket(String s, Throwable cause) {super(s, cause);}
    }
}
