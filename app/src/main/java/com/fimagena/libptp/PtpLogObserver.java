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
 * Optional simple log observer. Attach an implementation via
 * {@code PtpIpConnection.setLogObserver(...)} to route PTP/PTP-IP diagnostics into an
 * application log (e.g. a DiagnosticLog) without pulling Android into libptp.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #report(PtpLogRecord.Level, String, Throwable)} is called synchronously from
 *       the thread doing the protocol work. Implementations must return promptly and must not
 *       block indefinitely.</li>
 *   <li>Implementations <b>must not throw</b>. libptp wraps every call and swallows all
 *       {@link Throwable}s, so logging can never interrupt or corrupt the protocol.</li>
 *   <li>{@code message} is a ready-to-log single line. It contains protocol metadata only
 *       (phase, operation code, transaction id, response code, parameter count/values, timing)
 *       and never the host GUID, the InitCommandRequest payload or object/photo bytes.</li>
 *   <li>{@code error} is the original exception (with its own cause chain) when the record
 *       describes a failure, otherwise {@code null}.</li>
 * </ul>
 */
public interface PtpLogObserver {

    /**
     * Report one diagnostic line.
     *
     * @param level   severity, never {@code null}
     * @param message human readable single-line description (no GUID/payload/photo bytes)
     * @param error   original exception with its cause chain, or {@code null}
     */
    void report(PtpLogRecord.Level level, String message, Throwable error);
}
