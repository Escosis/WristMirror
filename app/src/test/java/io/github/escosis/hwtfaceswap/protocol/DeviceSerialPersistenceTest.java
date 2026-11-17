package io.github.escosis.hwtfaceswap.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import static org.junit.Assert.*;

/** Dedicated synthetic offline serial/cache validation. No captures, hardware, or identity fallback. */
public final class DeviceSerialPersistenceTest {
    @Test public void root09IsTheActualSerialIndependentOfOtherIdentityFields() throws Exception {
        byte[] body = fields(text(7, "5.0.0.123"), text(9, "SERIAL-A42"), text(10, "MODEL-A"), text(17, "Named A"), success());
        assertEquals("SERIAL-A42", parse(body));
        assertEquals("SERIAL-A42", DeviceTelemetry.serialText(parse(body)));
    }

    @Test public void missingSerialCannotUseFirmwareMacModelNameOrIdFallback() throws Exception {
        assertNull(parse(new byte[0]));
        assertNull(parse(fields(text(7, "5.0.0.123"), text(10, "MODEL-A"), text(17, "Named A"), text(18, "123456789012345"))));
        assertNull(DeviceTelemetry.serialText(null));
    }

    @Test public void firstKnownCacheExcludesMissingAndUnknownPlaceholders() throws Exception {
        for (String value : new String[] { "", " ", "unknown", " UNKNOWN ", "未知", "未知序列号", "null", "N/A" }) {
            assertNull(value, DeviceTelemetry.serialText(value));
        }
        // Wire parser contract is unchanged; cache separately treats literal placeholders as absent.
        assertEquals("unknown", parse(text(9, "unknown")));
        assertEquals("SN-unknown", DeviceTelemetry.serialText("SN-unknown"));
    }

    @Test public void oneWireTrailingNulIsRemovedBeforeCachingNotRememberedAsData() throws Exception {
        String value = parse(text(9, "  SERIAL-A42  \0"));
        assertEquals("SERIAL-A42", value);
        assertEquals(value, DeviceTelemetry.serialText(value));
        assertNull(parse(text(9, "\0")));
    }

    @Test public void embeddedRepeatedNulAndControlsNeverBecomeACachedPrefix() throws Exception {
        for (String value : new String[] { "SN\0other", "SN\0\0", "\nSN", "SN\r", "SN\t42", "SN\u007f", "SN\u0085" }) {
            fault("PRODUCT_SERIAL_CONTROL", () -> parse(text(9, value)));
            fault("PRODUCT_SERIAL_CONTROL", () -> DeviceTelemetry.serialText(value));
        }
        fault("PRODUCT_SERIAL_CONTROL", () -> DeviceTelemetry.serialText("SN\0"));
    }

    @Test public void malformedWireUtf8NeverBecomesAReplacementSerial() throws Exception {
        for (byte[] value : new byte[][] { {(byte) 0xc3, 0x28}, {(byte) 0xc0, (byte) 0x80},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80} }) {
            fault("INVALID_UTF8", () -> parse(Core.tlv(9, value)));
        }
    }

    @Test public void cachedUnpairedSurrogatesCannotBeReencodedByReplacement() throws Exception {
        fault("INVALID_UTF8", () -> DeviceTelemetry.serialText("SN-\ud800"));
        fault("INVALID_UTF8", () -> DeviceTelemetry.serialText("SN-\udc00"));
    }

    @Test public void validUtf8AndSupplementaryCharactersRetainOriginalText() throws Exception {
        for (String serial : new String[] { "合成序列-甲42", "SN-\ud83d\udd37", "SN-A42_rev.2" }) {
            assertEquals(serial, parse(text(9, serial)));
            assertEquals(serial, DeviceTelemetry.serialText(" " + serial + " "));
        }
    }

    @Test public void byteBoundaryAllowsOneWireNulButNeverTruncatesCache() throws Exception {
        String boundary = repeat('S', DeviceTelemetry.MAX_SERIAL_BYTES);
        assertEquals(boundary, parse(text(9, boundary)));
        assertEquals(boundary, parse(text(9, boundary + "\0")));
        assertEquals(boundary, DeviceTelemetry.serialText(boundary));
        fault("PRODUCT_SERIAL_SIZE", () -> parse(text(9, boundary + "S")));
        fault("PRODUCT_SERIAL_SIZE", () -> DeviceTelemetry.serialText(boundary + "S"));
        fault("PRODUCT_SERIAL_SIZE", () -> DeviceTelemetry.serialText(repeat('界', 43)));
        String multibyte = repeat('界', 42) + "AB";
        assertEquals(multibyte, DeviceTelemetry.serialText(multibyte));
    }

    @Test public void duplicateSerialAndNestedSerialCannotSelectFirstOrOtherRoot() throws Exception {
        fault("WIRE_DUPLICATE_TAG", () -> parse(fields(text(9, "SERIAL-A42"), text(9, "SERIAL-A42"))));
        fault("WIRE_DUPLICATE_TAG", () -> parse(fields(text(9, ""), text(9, "SERIAL-A42"))));
        assertNull(parse(Core.tlv(0x81, text(9, "SERIAL-A42"))));
        assertEquals("SERIAL-A42", parse(fields(Core.tlv(0x81, text(9, "NESTED")), text(9, "SERIAL-A42"))));
    }

    @Test public void rejectedProductAndWrongRoutesCannotPublishSerial() throws Exception {
        fault("DEVICE_STATS_REJECTED", () -> parse(fields(text(9, "SERIAL-A42"), Core.tlv(0x7f, new byte[] { 1 }))));
        fault("DEVICE_STATS_HEADER_MISMATCH", () -> DeviceTelemetry.parse(0x27, 7, text(9, "SERIAL-A42")));
        assertNull(DeviceTelemetry.parse(1, 8, text(9, "SERIAL-A42")).serialNumber);
    }

    @Test public void modelAndFirmwareRemainIndependentOfBrokenSerial() throws Exception {
        byte[] body = fields(text(7, "5.0.0.123"), text(10, "MODEL-A"), Core.tlv(9, new byte[] { (byte) 0xc3, 0x28 }));
        fault("INVALID_UTF8", () -> parse(body));
        assertEquals("5.0.0.123", DeviceInfo.firmware(1, 7, body));
        assertEquals("MODEL-A", DeviceInfo.model(1, 7, body));
    }

    @Test public void originalRequestAlreadyAsksForSerialExactlyOnce() throws Exception {
        List<TlvView.Part> request = TlvView.decode(WristInitialization.productRequest());
        int[] expected = { 1, 2, 7, 9, 10, 17, 18, 22, 26, 29, 30, 31, 32, 33, 34, 35 };
        assertEquals(expected.length, request.size());
        for (int i = 0; i < expected.length; i++) { assertEquals(expected[i], request.get(i).tag); assertEquals(0, request.get(i).value.length); }
        assertNotNull(Wire.unique(request, 9));
    }

    private static String parse(byte[] body) throws Exception { return DeviceTelemetry.parse(1, 7, body).serialNumber; }
    private static byte[] text(int tag, String value) throws Exception { return Core.tlv(tag, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] success() throws Exception { return Core.tlv(0x7f, new byte[] { 0 }); }
    private static byte[] fields(byte[]... parts) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] part : parts) out.write(part); return out.toByteArray(); }
    private static String repeat(char value, int length) { char[] chars = new char[length]; Arrays.fill(chars, value); return new String(chars); }
    private static void fault(String expected, ThrowingRunnable action) { assertEquals(expected, assertThrows(Core.Fault.class, action).code); }
}
