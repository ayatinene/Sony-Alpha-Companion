package io.github.ayatinene.sonycompanion;

import com.fimagena.libptp.PtpDataType;
import com.fimagena.libptp.PtpExceptions;
import com.fimagena.libptp.PtpOperation;
import com.fimagena.libptp.PtpSession;
import com.fimagena.libptp.PtpTransport;
import com.fimagena.libptp.ptpip.DataBuffer;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Byte-exact Sony Send-to-Computer payloads: the existing 21-byte start body stays untouched and the
 * experimental 19-byte sleep body is sent with the same 0x9280 -> 0x9281 order and parameter 4.
 * Uses a fake {@link PtpTransport.Session} so only PtpSession's own framing is under test.
 */
public class PtpSessionSonyTest {

    private static final byte[] START_21 = new byte[]{2,0,0,0, 2,0,0,0, 0,0,0,0, 0,0,0,0, 1,0,2,0, 0};
    private static final byte[] SLEEP_19 = new byte[]{0,0,0,0, 5,0,0,0, 0,0,0,0, 0,0,0,0, 2,0, 0x30};

    /** Test subclass so the protected PtpSession constructor is reachable; the pair never uses the connection. */
    private static final class TestSession extends PtpSession {
        TestSession(PtpTransport.Session session) {super(null, session);}
    }

    private static final class FakeSession implements PtpTransport.Session {
        final List<PtpOperation.Request> requests = new ArrayList<>();
        final Map<Integer, Integer> responseCodes = new HashMap<>();
        boolean ackCarriesData = true;

        @Override public PtpOperation.Response executeTransaction(PtpOperation.Request request) {
            requests.add(request);
            PtpOperation.Response response = PtpOperation.createResponse(request);
            int code = responseCodes.getOrDefault(request.getOperationCode(), PtpOperation.RSPCODE_OK);
            response.setResponseCode(code);
            // 0x9280/0x9281 declare zero response parameters (see PtpOperation).
            response.setParameters(new long[0]);
            if ((code == PtpOperation.RSPCODE_OK) && (request.getOperationCode() == 0x9281) && ackCarriesData)
                response.setData(new DataBuffer(new byte[]{0x11, 0x22, 0x33}));
            return response;
        }
        @Override public PtpOperation.Response executeTransaction(PtpOperation.Request request, DataLoadListener listener) {return executeTransaction(request);}
        @Override public PtpOperation.Response executeTransaction(PtpOperation.Request request, DataLoadListener listener, OutputStream outputStream) {return executeTransaction(request);}
        @Override public void close() {}
    }

    private static byte[] body(PtpOperation.Request request) {
        return ((PtpDataType.Object) request.getData()).mObject;
    }

    @Test public void startKeepsTheExact21ByteBodyAndPairOrder() throws Exception {
        FakeSession fake = new FakeSession();
        new TestSession(fake).sonyStartTransfer();

        assertEquals(2, fake.requests.size());
        assertEquals(0x9280, fake.requests.get(0).getOperationCode());
        assertArrayEquals(new long[]{4}, fake.requests.get(0).getParameters());
        assertEquals(21, START_21.length);
        assertArrayEquals(START_21, body(fake.requests.get(0)));
        assertEquals(0x9281, fake.requests.get(1).getOperationCode());
        assertArrayEquals(new long[]{4}, fake.requests.get(1).getParameters());
    }

    @Test public void sleepUsesTheExact19ByteBodyAndPairOrder() throws Exception {
        FakeSession fake = new FakeSession();
        new TestSession(fake).sonySleep();

        assertEquals(2, fake.requests.size());
        assertEquals(0x9280, fake.requests.get(0).getOperationCode());
        assertArrayEquals(new long[]{4}, fake.requests.get(0).getParameters());
        assertEquals(19, SLEEP_19.length);
        assertArrayEquals(SLEEP_19, body(fake.requests.get(0)));
        assertEquals(0x9281, fake.requests.get(1).getOperationCode());
        assertArrayEquals(new long[]{4}, fake.requests.get(1).getParameters());
    }

    @Test public void sleepStopsBeforeTheAckWhenStartFails() {
        FakeSession fake = new FakeSession();
        fake.responseCodes.put(0x9280, PtpOperation.RSPCODE_DeviceBusy);
        try {
            new TestSession(fake).sonySleep();
            fail("a failed 0x9280 must not continue to 0x9281");
        } catch (PtpExceptions.OperationFailed expected) {
            // expected
        } catch (Exception unexpected) {
            fail("unexpected: " + unexpected);
        }
        assertEquals(1, fake.requests.size());
    }

    @Test public void sleepStopsAfterTheAckWhenItFails() {
        FakeSession fake = new FakeSession();
        fake.responseCodes.put(0x9281, PtpOperation.RSPCODE_DeviceBusy);
        try {
            new TestSession(fake).sonySleep();
            fail("a failed 0x9281 must be reported");
        } catch (PtpExceptions.OperationFailed expected) {
            // expected
        } catch (Exception unexpected) {
            fail("unexpected: " + unexpected);
        }
        assertEquals(2, fake.requests.size());
    }

    @Test public void sleepRequiresTheAckDataToBeReadInFull() {
        FakeSession fake = new FakeSession();
        fake.ackCarriesData = false; // a success without its DATA_IN body is a protocol violation
        try {
            new TestSession(fake).sonySleep();
            fail("0x9281 success without data must be rejected");
        } catch (PtpExceptions.PtpProtocolViolation expected) {
            assertTrue("the failure must name the missing data, got: " + expected.getMessage(),
                    String.valueOf(expected.getMessage()).contains("Expected data"));
        } catch (Exception unexpected) {
            fail("unexpected: " + unexpected);
        }
    }
}
