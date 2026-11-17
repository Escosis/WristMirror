package io.github.escosis.hwtfaceswap.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import static org.junit.Assert.*;

/** Synthetic offline product layouts, not device captures or a commercial model-name mapping. */
public final class DeviceModelTest {
    @Test public void root0AIsTheExactModelNotTheNameSerialOrFirmware() throws Exception {
        byte[] body = fields(text(3, "HW1"), text(7, "5.0.0.123"), text(9, "SERIAL-A"),
                text(0x0a, "MODEL-A42"), text(0x11, "Renamed device"), success());
        assertEquals("MODEL-A42", parse(body));
        assertEquals("5.0.0.123", DeviceInfo.firmware(1, 7, body));
        assertEquals("SERIAL-A", DeviceTelemetry.parse(1, 7, body).serialNumber);
        assertEquals("Renamed device", WristInitialization.productName(TlvView.decode(body), "Fallback"));
    }

    @Test public void missingModelNeverFallsBackToADeviceNameOrOtherIdentity() throws Exception {
        assertNull(parse(new byte[0]));
        assertNull(parse(fields(text(3, "HW-MODEL"), text(7, "1.2.3"), text(9, "SERIAL-MODEL"),
                text(0x11, "Device name"), success())));
    }

    @Test public void emptyAndUnknownModelsStayUnknown() throws Exception {
        for (String value : new String[] { "", "\0", "   ", "   \0", "unknown", " UNKNOWN ",
                "未知", "未知型号", "null", "N/A" }) {
            assertNull(value, parse(fields(text(0x0a, value), text(0x11, "Known name"))));
        }
    }

    @Test public void onlyOneTrailingNulAndOrdinaryEdgePaddingAreRemoved() throws Exception {
        assertEquals("MODEL-A42", parse(text(0x0a, "  MODEL-A42  \0")));
        assertEquals("MODEL-A42", parse(text(0x0a, "MODEL-A42\0")));
        assertEquals("model-A42_rev.2", parse(text(0x0a, "model-A42_rev.2")));
    }

    @Test public void validUtf8IsPreservedWithoutGuessingACommercialName() throws Exception {
        assertEquals("型号-甲42", parse(text(0x0a, "型号-甲42")));
        assertEquals("MODEL-\ud83d\udd37", parse(text(0x0a, "MODEL-\ud83d\udd37")));
        assertEquals("MODEL-A42", DeviceInfo.modelText(" MODEL-A42 "));
    }

    @Test public void embeddedAndRepeatedNulAreRejectedNotUsedAsTerminators() throws Exception {
        assertFault("PRODUCT_MODEL_CONTROL", () -> parse(text(0x0a, "MODEL\0-A42")));
        assertFault("PRODUCT_MODEL_CONTROL", () -> parse(text(0x0a, "MODEL-A42\0\0")));
        assertFault("PRODUCT_MODEL_CONTROL", () -> parse(text(0x0a, "\0\0")));
    }

    @Test public void malformedUtf8IsNeverReplaced() throws Exception {
        for (byte[] value : new byte[][] {
                { 'M', (byte) 0xc3, 0x28 }, { (byte) 0xc0, (byte) 0x80, 0 },
                { (byte) 0xed, (byte) 0xa0, (byte) 0x80 },
                { (byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80 },
                { (byte) 0xe2, (byte) 0x82 }, { (byte) 0xff } }) {
            assertFault("INVALID_UTF8", () -> parse(Core.tlv(0x0a, value)));
        }
    }

    @Test public void controlsAndDisplayDirectionControlsAreRejectedBeforeTrimming() throws Exception {
        for (String value : new String[] { "\nMODEL", "MODEL\r", "MODEL\t42", "MODEL\u007f",
                "MODEL\u0085", "MODEL\u202e", "MODEL\u200b", "MODEL\ufffd" }) {
            assertFault("PRODUCT_MODEL_CONTROL", () -> parse(text(0x0a, value)));
        }
    }

    @Test public void cachedTextCannotRepairUnpairedSurrogatesOrTrailingNul() throws Exception {
        assertFault("PRODUCT_MODEL_CONTROL", () -> DeviceInfo.modelText("MODEL-\ud800"));
        assertFault("PRODUCT_MODEL_CONTROL", () -> DeviceInfo.modelText("MODEL-\udc00"));
        assertFault("PRODUCT_MODEL_CONTROL", () -> DeviceInfo.modelText("MODEL\0"));
        assertNull(DeviceInfo.modelText(null));
    }

    @Test public void duplicateModelIsRejectedEvenWhenBothValuesMatch() throws Exception {
        assertFault("WIRE_DUPLICATE_TAG", () -> parse(fields(text(0x0a, "MODEL-A42"), text(0x0a, "MODEL-A42"))));
        assertFault("WIRE_DUPLICATE_TAG", () -> parse(fields(text(0x0a, ""), text(0x0a, "MODEL-A42"))));
        assertFault("WIRE_DUPLICATE_TAG", () -> parse(fields(text(0x0a, "MODEL-A42"), text(0x0a, "MODEL-B17"))));
    }

    @Test public void nestedModelIsNotRootModelAndNestedDuplicateDoesNotMaskRoot() throws Exception {
        byte[] nested = Core.tlv(0x81, fields(text(0x0a, "NESTED-A"), text(0x0a, "NESTED-B")));
        assertNull(parse(nested));
        assertEquals("MODEL-A42", parse(fields(nested, text(0x0a, "MODEL-A42"))));
        assertEquals("MODEL-A42", parse(fields(Core.tlv(0x81, Core.tlv(0x7f, new byte[] { 1 })), text(0x0a, "MODEL-A42"))));
    }

    @Test public void malformedUnrelatedFieldsDoNotMaskValidRootModel() throws Exception {
        assertEquals("MODEL-A42", parse(fields(text(0x0a, "MODEL-A42"),
                Core.tlv(7, new byte[] { (byte) 0xc3, 0x28 }), text(9, "A"), text(9, "B"),
                Core.tlv(0x11, new byte[] { (byte) 0xff }))));
    }

    @Test public void badModelDoesNotChangeFirmwareOrSerialParsing() throws Exception {
        for (byte[] invalid : new byte[][] {
                Core.tlv(0x0a, new byte[] { (byte) 0xc3, 0x28 }), text(0x0a, "MODEL\0A"),
                fields(text(0x0a, "MODEL-A"), text(0x0a, "MODEL-B")),
                text(0x0a, repeat('M', DeviceInfo.MAX_MODEL_BYTES + 1)) }) {
            byte[] body = fields(text(7, "5.0.0.123"), text(9, "SERIAL-A"), invalid, success());
            assertThrows(Core.Fault.class, () -> parse(body));
            assertEquals("5.0.0.123", DeviceInfo.firmware(1, 7, body));
            assertEquals("SERIAL-A", DeviceTelemetry.parse(1, 7, body).serialNumber);
        }
    }

    @Test public void maximumByteLengthAcceptsExactlyOneOptionalNul() throws Exception {
        String boundary = repeat('M', DeviceInfo.MAX_MODEL_BYTES);
        assertEquals(boundary, parse(text(0x0a, boundary)));
        assertEquals(boundary, parse(text(0x0a, boundary + "\0")));
        String multibyte = repeat('界', 42) + "AB";
        assertEquals(128, multibyte.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(multibyte, parse(text(0x0a, multibyte + "\0")));
    }

    @Test public void oversizeIsNotTruncatedOrTrimmedIntoAKnownModel() throws Exception {
        for (String value : new String[] { repeat('M', 129), repeat('M', 129) + "\0",
                repeat(' ', 128) + "MODEL", repeat('界', 43) }) {
            assertFault("PRODUCT_MODEL_SIZE", () -> parse(text(0x0a, value)));
        }
        assertFault("PRODUCT_MODEL_SIZE", () -> DeviceInfo.modelText(repeat('M', 129)));
    }

    @Test public void errorHeaderCannotPublishAnOtherwiseValidModel() throws Exception {
        assertFault("PRODUCT_INFO_REJECTED", () -> parse(fields(text(0x0a, "MODEL-A42"), Core.tlv(0x7f, new byte[] { 1 }))));
        assertFault("PRODUCT_INFO_REJECTED", () -> parse(fields(text(0x0a, "MODEL-A42"), Core.tlv(0x7f, new byte[] { 0, 1, (byte) 0x86, (byte) 0xa1 }))));
        assertEquals("MODEL-A42", parse(fields(text(0x0a, "MODEL-A42"), Core.tlv(0x7f, new byte[] { 0, 1, (byte) 0x86, (byte) 0xa0 }))));
        assertEquals("MODEL-A42", parse(text(0x0a, "MODEL-A42")));
    }

    @Test public void duplicateOrMalformedErrorHeaderIsRejected() throws Exception {
        assertFault("WIRE_DUPLICATE_TAG", () -> parse(fields(text(0x0a, "MODEL-A42"), success(), success())));
        assertFault("NUMERIC_TLV_LENGTH", () -> parse(fields(text(0x0a, "MODEL-A42"), Core.tlv(0x7f, new byte[0]))));
        assertFault("NUMERIC_TLV_LENGTH", () -> parse(fields(text(0x0a, "MODEL-A42"), Core.tlv(0x7f, new byte[5]))));
    }

    @Test public void wrongServiceOrCommandCannotBeProductModel() throws Exception {
        byte[] body = text(0x0a, "MODEL-A42");
        assertFault("DEVICE_INFO_HEADER_MISMATCH", () -> DeviceInfo.model(0x27, 7, body));
        assertFault("DEVICE_INFO_HEADER_MISMATCH", () -> DeviceInfo.model(1, 8, body));
        assertFault("DEVICE_INFO_HEADER_MISMATCH", () -> DeviceInfo.model(1, 0x0a, body));
    }

    @Test public void malformedTlvCannotYieldAPartiallyDecodedModel() throws Exception {
        assertFault("TLV_VIEW_TRUNCATED_LENGTH", () -> parse(fields(text(0x0a, "MODEL-A42"), new byte[] { 9 })));
        assertFault("TLV_VIEW_TRUNCATED_VALUE", () -> parse(new byte[] { 0x0a, 4, 'M', 'O', 'D' }));
        assertFault("TLV_VIEW_BYTES_LIMIT", () -> parse(null));
    }

    @Test public void malformedPublicFieldListsAreRejected() throws Exception {
        assertFault("WIRE_FIELDS_REQUIRED", () -> DeviceInfo.model((List<TlvView.Part>) null));
        assertFault("WIRE_FIELD_INVALID", () -> DeviceInfo.model(Collections.singletonList(new TlvView.Part(-1, new byte[0]))));
        assertFault("WIRE_FIELD_INVALID", () -> DeviceInfo.model(Collections.singletonList(new TlvView.Part(0x0a, null))));
    }

    @Test public void originalProductRequestAlreadyContainsModelExactlyOnce() throws Exception {
        int[] expected = { 1, 2, 7, 9, 10, 17, 18, 22, 26, 29, 30, 31, 32, 33, 34, 35 };
        List<TlvView.Part> request = TlvView.decode(WristInitialization.productRequest());
        assertEquals(expected.length, request.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], request.get(i).tag);
            assertEquals(0, request.get(i).value.length);
        }
        assertNotNull(Wire.unique(request, DeviceInfo.MODEL_TAG));
    }

    private static String parse(byte[] body) throws IOException { return DeviceInfo.model(1, 7, body); }
    private static byte[] text(int tag, String value) throws IOException { return Core.tlv(tag, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] success() throws IOException { return Core.tlv(0x7f, new byte[] { 0 }); }
    private static byte[] fields(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }
    private static String repeat(char ch, int count) { char[] data = new char[count]; Arrays.fill(data, ch); return new String(data); }
    private static void assertFault(String expected, ThrowingRunnable action) {
        assertEquals(expected, assertThrows(Core.Fault.class, action).code);
    }
}
