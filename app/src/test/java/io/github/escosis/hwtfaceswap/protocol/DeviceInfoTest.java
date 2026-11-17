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

/** 按已读 GB/腕连字段布局构造的离线样本；不冒充真实 GT/Fit 抓包或实机兼容结果。 */
public final class DeviceInfoTest {
    @Test public void gtProductLayoutReadsOnlySoftwareVersion() throws Exception {
        // 根层依次为硬件版本 03、软件版本 07、序列号 09、型号 0A、设备名 11、成功 7F。
        byte[] body = Core.unhex("030456312e300711322e312e302e343035284330304d303029"
                + "0906534e303030310a0647542d4d4f44110a485541574549204754377f0100");
        assertEquals("2.1.0.405(C00M00)", DeviceInfo.firmware(1, 7, body));
        assertEquals("HUAWEI GT7", WristInitialization.productName(TlvView.decode(body), "原名称"));
        assertEquals("2.1.0.405(C00M00)", WristInitialization.productFirmware(TlvView.decode(body)));
    }

    @Test public void fitProductLayoutUsesTheSameFirmwareTag() throws Exception {
        byte[] body = fields(Core.tlv(1, new byte[] { 2 }), Core.tlv(2, new byte[] { 1 }),
                text(7, "4.0.0.110(C00M00)"), text(9, "FIT000001"), text(10, "WATCH FIT 3"),
                text(17, "HUAWEI WATCH FIT 3"), Core.tlv(0x7f, new byte[] { 0, 1, (byte) 0x86, (byte) 0xa0 }));
        assertEquals("4.0.0.110(C00M00)", DeviceInfo.firmware(1, 7, body));
    }

    @Test public void missingHeaderDoesNotInventAnErrorOrVersion() throws Exception {
        assertEquals("5.0.0.32(SP8C00M01)", parse(text(7, "5.0.0.32(SP8C00M01)")));
        assertNull(parse(new byte[0]));
    }

    @Test public void oneTrailingNulIsRemovedWithoutChangingTheVersion() throws Exception {
        assertEquals("5.0.0.123(SP1C00M01)", parse(text(7, "5.0.0.123(SP1C00M01)\0")));
    }

    @Test public void ordinaryPaddingAndVersionPrefixArePreservedSafely() throws Exception {
        assertEquals("V1.0.12.20", parse(text(7, "  V1.0.12.20  \0")));
        assertEquals("1.0.12.20-SP1", parse(text(7, "1.0.12.20-SP1")));
        assertEquals("1.0.12.20 (C00)", parse(text(7, "1.0.12.20 (C00)")));
    }

    @Test public void missingFirmwareNeverFallsBackToIdentityFields() throws Exception {
        assertNull(parse(fields(text(3, "8.8.8.8"), text(9, "9.9.9.9"),
                text(10, "10.10.10.10"), text(17, "11.11.11.11"))));
    }

    @Test public void emptyFirmwareKeepsUnknownEvenWhenOtherFieldsLookLikeVersions() throws Exception {
        for (String absent : new String[] { "", "\0", "   ", "   \0" }) {
            assertNull("空软件版本不能从其他字段补造", parse(fields(text(7, absent), text(9, "1.2.3.4"),
                    text(10, "2.3.4.5"), text(17, "3.4.5.6"))));
        }
    }

    @Test public void misplacedModelSerialAndUnknownTextAreNotVersions() throws Exception {
        for (String invalid : new String[] { "HUAWEI WATCH GT 7", "WATCH FIT 3", "GT7-MODEL",
                "SN000000000001", "5500123456789012", "123", "unknown", "未知", "1.2.3(C00", "1.2.3)" }) {
            assertNull("非版本文本应保留未知", parse(text(7, invalid)));
        }
    }

    @Test public void aNestedTag07IsNotTheRootSoftwareVersion() throws Exception {
        assertNull(parse(Core.tlv(0x81, text(7, "5.0.0.123"))));
    }

    @Test public void unrelatedOptionalFieldsCannotMaskAValidFirmware() throws Exception {
        assertEquals("5.0.0.123", parse(fields(text(7, "5.0.0.123"),
                Core.tlv(10, new byte[] { (byte) 0xc3, 0x28 }), text(9, "SN1"), text(9, "SN2"))));
    }

    @Test public void duplicateFirmwareIsRejectedEvenWhenBothValuesMatch() throws Exception {
        assertFault("WIRE_DUPLICATE_TAG", () -> parse(fields(text(7, "5.0.0.123"), text(7, "5.0.0.123"))));
    }

    @Test public void duplicateFirmwareIsNotResolvedByIgnoringTheEmptyFirstValue() throws Exception {
        assertFault("WIRE_DUPLICATE_TAG", () -> parse(fields(text(7, ""), text(7, "5.0.0.123"))));
    }

    @Test public void maximumContentLengthAcceptsAnOptionalSingleNul() throws Exception {
        String boundary = "1." + repeat('0', DeviceInfo.MAX_FIRMWARE_BYTES - 2);
        assertEquals(boundary, parse(text(7, boundary)));
        assertEquals(boundary, parse(text(7, boundary + "\0")));
    }

    @Test public void excessContentIsNotTrimmedOrTruncatedIntoAValidVersion() throws Exception {
        String tooLong = "1." + repeat('0', DeviceInfo.MAX_FIRMWARE_BYTES - 1);
        assertFault("PRODUCT_FIRMWARE_SIZE", () -> parse(text(7, tooLong)));
        assertFault("PRODUCT_FIRMWARE_SIZE", () -> parse(text(7, tooLong + "\0")));
        assertFault("PRODUCT_FIRMWARE_SIZE", () -> parse(text(7, repeat(' ', 80) + "1.2")));
    }

    @Test public void invalidUtf8IsNotReplacedWithDisplayText() throws Exception {
        assertFault("INVALID_UTF8", () -> parse(Core.tlv(7, new byte[] { '1', '.', (byte) 0xc3, 0x28 })));
        assertFault("INVALID_UTF8", () -> parse(Core.tlv(7, new byte[] { '1', '.', (byte) 0xc0, (byte) 0x80, 0 })));
    }

    @Test public void embeddedOrRepeatedNulIsNotAStringTerminator() throws Exception {
        assertFault("PRODUCT_FIRMWARE_CONTROL", () -> parse(text(7, "1.2\0.3")));
        assertFault("PRODUCT_FIRMWARE_CONTROL", () -> parse(text(7, "1.2.3\0\0")));
    }

    @Test public void controlCharactersAreRejectedBeforeTrimming() throws Exception {
        for (String invalid : new String[] { "\n1.2.3", "1.2.3\r", "1.2\t.3", "1.2.3\u007f" }) {
            assertFault("PRODUCT_FIRMWARE_CONTROL", () -> parse(text(7, invalid)));
        }
    }

    @Test public void validUtf8WithoutTheConservativeVersionShapeStaysUnknown() throws Exception {
        assertNull(parse(text(7, "版本5.0.0.123")));
        assertNull(parse(text(7, "５.０.０.１２３")));
        assertNull(parse(text(7, "5.0.0.123\u202e")));
    }

    @Test public void rejectedHeaderCannotPublishAValidFirmware() throws Exception {
        assertFault("PRODUCT_INFO_REJECTED", () -> parse(fields(text(7, "5.0.0.123"), Core.tlv(0x7f, new byte[] { 1 }))));
        assertFault("PRODUCT_INFO_REJECTED", () -> parse(fields(text(7, "5.0.0.123"), Core.tlv(0x7f, new byte[] { 0, 1, (byte) 0x86, (byte) 0xa1 }))));
    }

    @Test public void duplicateSuccessHeadersAreRejected() throws Exception {
        assertFault("WIRE_DUPLICATE_TAG", () -> parse(fields(text(7, "5.0.0.123"),
                Core.tlv(0x7f, new byte[] { 0 }), Core.tlv(0x7f, new byte[] { 0 }))));
    }

    @Test public void malformedHeaderLengthsAreRejected() throws Exception {
        assertFault("NUMERIC_TLV_LENGTH", () -> parse(fields(text(7, "5.0.0.123"), Core.tlv(0x7f, new byte[0]))));
        assertFault("NUMERIC_TLV_LENGTH", () -> parse(fields(text(7, "5.0.0.123"), Core.tlv(0x7f, new byte[5]))));
    }

    @Test public void wrongServiceOrCommandCannotBeParsedAsProductInformation() throws Exception {
        byte[] body = text(7, "5.0.0.123");
        assertFault("DEVICE_INFO_HEADER_MISMATCH", () -> DeviceInfo.firmware(0x27, 7, body));
        assertFault("DEVICE_INFO_HEADER_MISMATCH", () -> DeviceInfo.firmware(1, 8, body));
    }

    @Test public void malformedTlvCannotPublishAPartiallyDecodedVersion() throws Exception {
        assertFault("TLV_VIEW_TRUNCATED_LENGTH", () -> parse(fields(text(7, "5.0.0.123"), new byte[] { 10 })));
        assertFault("TLV_VIEW_TRUNCATED_VALUE", () -> parse(new byte[] { 7, 4, '1', '.', '2' }));
        assertFault("TLV_VIEW_BYTES_LIMIT", () -> parse(null));
    }

    @Test public void malformedPublicFieldsAreRejected() throws Exception {
        assertFault("WIRE_FIELDS_REQUIRED", () -> DeviceInfo.firmware((List<TlvView.Part>) null));
        assertFault("WIRE_FIELD_INVALID", () -> DeviceInfo.firmware(Collections.singletonList(new TlvView.Part(-1, new byte[0]))));
        assertFault("WIRE_FIELD_INVALID", () -> DeviceInfo.firmware(Collections.singletonList(new TlvView.Part(7, null))));
    }

    @Test public void existingProductRequestAlreadyAsksForFirmwareWithoutExtraCommands() throws Exception {
        List<TlvView.Part> request = TlvView.decode(WristInitialization.productRequest());
        int[] expected = { 1, 2, 7, 9, 10, 17, 18, 22, 26, 29, 30, 31, 32, 33, 34, 35 };
        assertEquals("产品请求字段数不变", expected.length, request.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], request.get(i).tag);
            assertEquals(0, request.get(i).value.length);
        }
    }

    private static String parse(byte[] body) throws IOException { return DeviceInfo.firmware(1, 7, body); }
    private static byte[] text(int tag, String value) throws IOException { return Core.tlv(tag, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] fields(byte[]... fields) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] field : fields) out.write(field);
        return out.toByteArray();
    }
    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
    private static void assertFault(String expected, ThrowingRunnable action) {
        Core.Fault fault = assertThrows(Core.Fault.class, action);
        assertEquals("应拒绝指定协议错误，而非猜测版本", expected, fault.code);
    }
}
