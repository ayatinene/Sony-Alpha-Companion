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

package com.fimagena.libptp;

/**
 * Internal structured record used by libptp to format one line for a
 * {@link PtpLogObserver}. It also exposes the log {@link Level} enum used by the
 * observer contract.
 *
 * <p>Deliberately contains only protocol metadata (phases, operation/response
 * codes, transaction ids, parameter counts/values, durations and the original
 * exception). It never carries host GUIDs, InitCommandRequest payloads or
 * object/photo bytes. {@link #toString()} renders the message handed to
 * {@link PtpLogObserver#report(Level, String, Throwable)}.</p>
 */
public final class PtpLogRecord {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    public static final int UNSET_INT = -1;
    public static final long UNSET_LONG = -1L;

    /** Log severity. */
    public final Level level;
    /** Logical phase, e.g. {@code CONNECT}, {@code INIT_COMMAND}, {@code INIT_EVENT}, {@code TRANSACTION}, {@code CLOSE}, {@code TRANSPORT}. */
    public final String phase;
    /** Human readable description. */
    public final String message;
    /** PTP operation code, or {@link #UNSET_INT}. */
    public final int operationCode;
    /** PTP transaction id, or {@link #UNSET_LONG}. */
    public final long transactionId;
    /** PTP response code, or {@link #UNSET_INT}. */
    public final int responseCode;
    /** Number of request parameters, or {@link #UNSET_INT}. */
    public final int parameterCount;
    /** Request parameter values (may be empty), never payload/photo bytes. */
    public final long[] parameters;
    /** Duration in milliseconds, or {@link #UNSET_LONG}. */
    public final long durationMillis;
    /** Original exception (with its own cause chain) or {@code null}. */
    public final Throwable error;

    private PtpLogRecord(Builder builder) {
        level = builder.level;
        phase = builder.phase;
        message = builder.message;
        operationCode = builder.operationCode;
        transactionId = builder.transactionId;
        responseCode = builder.responseCode;
        parameterCount = builder.parameterCount;
        parameters = builder.parameters == null ? new long[0] : builder.parameters.clone();
        durationMillis = builder.durationMillis;
        error = builder.error;
    }

    public static Builder builder(Level level, String phase) {return new Builder(level, phase);}

    public static final class Builder {
        private final Level level;
        private final String phase;
        private String message;
        private int operationCode = UNSET_INT;
        private long transactionId = UNSET_LONG;
        private int responseCode = UNSET_INT;
        private int parameterCount = UNSET_INT;
        private long[] parameters;
        private long durationMillis = UNSET_LONG;
        private Throwable error;

        private Builder(Level level, String phase) {
            this.level = level;
            this.phase = phase;
        }

        public Builder message(String message) {this.message = message; return this;}
        public Builder operationCode(int operationCode) {this.operationCode = operationCode; return this;}
        public Builder transactionId(long transactionId) {this.transactionId = transactionId; return this;}
        public Builder responseCode(int responseCode) {this.responseCode = responseCode; return this;}
        public Builder parameterCount(int parameterCount) {this.parameterCount = parameterCount; return this;}
        public Builder parameters(long[] parameters) {
            this.parameters = parameters;
            if (parameters != null) this.parameterCount = parameters.length;
            return this;
        }
        public Builder durationMillis(long durationMillis) {this.durationMillis = durationMillis; return this;}
        public Builder error(Throwable error) {this.error = error; return this;}

        public PtpLogRecord build() {return new PtpLogRecord(this);}
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(level).append(' ').append(phase);
        if (operationCode != UNSET_INT) sb.append(" op=").append(String.format("0x%04x", operationCode));
        if (transactionId != UNSET_LONG) sb.append(" tx=").append(transactionId);
        if (responseCode != UNSET_INT) sb.append(" rsp=").append(String.format("0x%04x", responseCode));
        if (parameterCount != UNSET_INT) sb.append(" params=").append(parameterCount);
        if ((parameters != null) && (parameters.length > 0)) {
            sb.append(" [");
            for (int i = 0; i < parameters.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.format("0x%08x", parameters[i]));
            }
            sb.append(']');
        }
        if (durationMillis != UNSET_LONG) sb.append(" ").append(durationMillis).append("ms");
        if (message != null) sb.append(" - ").append(message);
        if (error != null) sb.append(" cause=").append(error.getClass().getSimpleName()).append(": ").append(error.getMessage());
        return sb.toString();
    }
}
