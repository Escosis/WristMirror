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

/** 原腕连 DEX H/L/y/p0 → n → y0.h → r4.T 布局的合成离线样本，非实机抓包。 */
public final class DeviceTelemetryTest {
    private static final long MIB = DeviceTelemetry.MIB;

    @Test public void productSerialReadsOnlyRoot09() throws Exception {
        DeviceTelemetry.Stats stats = product(fields(text(7, "5.0.0.123"), text(9, "SYNTHETIC_SN_001"),
                text(10, "GT-MODEL"), text(17, "HUAWEI Watch"), success(100000)));
        assertEquals("SYNTHETIC_SN_001", stats.serialNumber);
        assertNull(stats.batteryPercent);
        assertNull(stats.freeMemoryBytes);
    }

    @Test public void productSerialDoesNotFallBackToFirmwareModelDeviceNameOrUid() throws Exception {
        assertNull(product(fields(text(7, "5.0.0.123"), text(10, "MODEL"), text(17, "NAME"),
                text(18, "UID"), text(1, "NOT_SERIAL"))).serialNumber);
    }

    @Test public void serialAllowsOneTrailingNulAndDisplayPadding() throws Exception {
        assertEquals("SYNTHETIC_SN_002", product(text(9, "  SYNTHETIC_SN_002  \0")).serialNumber);
    }

    @Test public void missingEmptyOrOnlyPaddedSerialStaysUnknown() throws Exception {
        assertNull(product(new byte[0]).serialNumber);
        for (String empty : new String[] { "", "\0", "   ", "   \0" }) {
            assertFalse(product(text(9, empty)).hasObservedFields());
        }
    }

    @Test public void serialDoesNotTruncateEmbeddedRepeatedNulOrControls() throws Exception {
        for (String invalid : new String[] { "SN\0OTHER", "SN\0\0", "\nSN", "SN\r", "SN\t01", "SN\u007f" }) {
            fault("PRODUCT_SERIAL_CONTROL", () -> product(text(9, invalid)));
        }
    }

    @Test public void serialReadsValidUtf8WithoutGuessingAnAsciiSerialPattern() throws Exception {
        assertEquals("合成序列甲-01", product(text(9, "合成序列甲-01\0")).serialNumber);
    }

    @Test public void serialNeverReplacesBadUtf8() throws Exception {
        fault("INVALID_UTF8", () -> product(Core.tlv(9, new byte[] { (byte) 0xc3, 0x28 })));
        fault("INVALID_UTF8", () -> product(Core.tlv(9, new byte[] { (byte) 0xc0, (byte) 0x80, 0 })));
        fault("INVALID_UTF8", () -> product(Core.tlv(9, new byte[] { (byte) 0xed, (byte) 0xa0, (byte) 0x80 })));
    }

    @Test public void serialBoundaryIsInBytesAndAllowsOneExtraNulOnly() throws Exception {
        String boundary = repeat('S', DeviceTelemetry.MAX_SERIAL_BYTES);
        assertEquals(boundary, product(text(9, boundary)).serialNumber);
        assertEquals(boundary, product(text(9, boundary + "\0")).serialNumber);
        fault("PRODUCT_SERIAL_SIZE", () -> product(text(9, boundary + "S")));
        fault("PRODUCT_SERIAL_SIZE", () -> product(text(9, boundary + "S\0")));
        fault("PRODUCT_SERIAL_SIZE", () -> product(text(9, repeat('甲', 43))));
    }

    @Test public void serialDuplicatesCannotBeResolvedByPickingFirstOrNonempty() throws Exception {
        fault("WIRE_DUPLICATE_TAG", () -> product(fields(text(9, "SN"), text(9, "SN"))));
        fault("WIRE_DUPLICATE_TAG", () -> product(fields(text(9, ""), text(9, "SN"))));
    }

    @Test public void invalidUnrelatedOptionalTextDoesNotMaskAValidSerial() throws Exception {
        assertEquals("SN", product(fields(text(9, "SN"), Core.tlv(10, new byte[] { (byte) 0xc3, 0x28 }),
                text(7, "firmware-not-a-version"), text(7, "duplicate-not-serial"))).serialNumber);
    }

    @Test public void nestedSerialBatteryAndStorageTagsAreNotRootFields() throws Exception {
        assertFalse(product(Core.tlv(0x81, text(9, "SN"))).hasObservedFields());
        assertFalse(battery(Core.tlv(0x81, Core.tlv(1, new byte[] { 88 }))).hasObservedFields());
        assertFalse(storage(Core.tlv(0x81, Core.tlv(2, num(512, 4)))).hasObservedFields());
    }

    @Test public void batteryAcceptsAllObservedPercentValuesIncludingRealZero() throws Exception {
        for (int value = 0; value <= 100; value++) {
            DeviceTelemetry.Stats stats = battery(Core.tlv(1, new byte[] { (byte) value }));
            assertEquals(Integer.valueOf(value), stats.batteryPercent);
            assertNull(stats.serialNumber);
            assertNull(stats.freeMemoryBytes);
        }
    }

    @Test public void missingOrEmptyBatteryDoesNotReuseAnotherFieldOrForgeZero() throws Exception {
        assertFalse(battery(new byte[0]).hasObservedFields());
        assertFalse(battery(fields(Core.tlv(1, new byte[0]), Core.tlv(2, new byte[] { 80 }))).hasObservedFields());
        assertFalse(battery(Core.tlv(2, new byte[] { 80 })).hasObservedFields());
    }

    @Test public void nonemptyBatteryUsesOriginalFirstOctetNotAnExtraSingleByteRestriction() throws Exception {
        // 原腕连 n.e 和 Gadgetbridge getByte 均取首字节；不是本机多字节抓包证据。
        for (int length : new int[] { 2, 3, 4, 5, 128, 256 }) {
            for (int percent = 0; percent <= 100; percent++) {
                byte[] value = new byte[length]; Arrays.fill(value, (byte) 255); value[0] = (byte) percent;
                assertEquals(Integer.valueOf(percent), battery(Core.tlv(1, value)).batteryPercent);
            }
        }
    }

    @Test public void batterySuffixIsNeitherABigEndianPercentNorAMultiBatteryFallback() throws Exception {
        assertEquals(Integer.valueOf(0), battery(Core.tlv(1, new byte[] { 0, 100 })).batteryPercent);
        assertEquals(Integer.valueOf(1), battery(Core.tlv(1, new byte[] { 1, 0 })).batteryPercent);
        assertEquals(Integer.valueOf(80), battery(Core.tlv(1, new byte[] { 80, (byte) 255 })).batteryPercent);
        assertEquals(Integer.valueOf(88), battery(fields(Core.tlv(1, new byte[] { 88, 12 }),
                Core.tlv(2, new byte[] { 40 }), Core.tlv(3, new byte[] { 1 }))).batteryPercent);
    }

    @Test public void batteryNeverSearchesForAValidSuffixAfterInvalidFirstOctet() throws Exception {
        for (int first : new int[] { 101, 127, 128, 255 }) {
            for (int length : new int[] { 2, 5, 128, 256 }) {
                byte[] value = new byte[length]; Arrays.fill(value, (byte) 88); value[0] = (byte) first;
                fault("BATTERY_PERCENT_RANGE", () -> battery(Core.tlv(1, value)));
            }
        }
    }

    @Test public void longerBatteryDuplicatesRemainAmbiguousEvenWithEqualFirstOctets() throws Exception {
        fault("WIRE_DUPLICATE_TAG", () -> battery(fields(Core.tlv(1, new byte[] { 88, 1 }),
                Core.tlv(1, new byte[] { 88, 2 }))));
        fault("WIRE_DUPLICATE_TAG", () -> battery(fields(Core.tlv(1, new byte[0]),
                Core.tlv(1, new byte[] { 88, 1 }))));
    }

    @Test public void invalidHeaderStillBlocksLongerBatteryField() throws Exception {
        fault("DEVICE_STATS_REJECTED", () -> battery(fields(Core.tlv(1, new byte[] { 88, 0 }), success(100001))));
        fault("WIRE_DUPLICATE_TAG", () -> battery(fields(Core.tlv(1, new byte[] { 88, 0 }), success(0), success(0))));
    }

    @Test public void batteryOutOfRangeIsRejectedNotClamped() throws Exception {
        for (int value : new int[] { 101, 127, 128, 255 }) {
            fault("BATTERY_PERCENT_RANGE", () -> battery(Core.tlv(1, new byte[] { (byte) value })));
        }
    }

    @Test public void duplicateBatteryIsRejectedEvenWhenEqual() throws Exception {
        fault("WIRE_DUPLICATE_TAG", () -> battery(fields(Core.tlv(1, new byte[] { 88 }), Core.tlv(1, new byte[] { 88 }))));
    }

    @Test public void storageUsesTheProvenOriginalMbDisplayBranchNotRawBytes() throws Exception {
        for (long raw : new long[] { 1, 128, 512, 1024, 4096 }) {
            DeviceTelemetry.Stats stats = storage(Core.tlv(2, num(raw, 4)));
            assertEquals(Long.valueOf(raw * MIB), stats.freeMemoryBytes);
            assertNull(stats.serialNumber);
            assertNull(stats.batteryPercent);
        }
    }

    @Test public void storageLargeBranchPreservesOriginalIntegerMbRounding() throws Exception {
        assertEquals(Long.valueOf(3 * MIB), storage(Core.tlv(2, num(3 * MIB + 12345, 4))).freeMemoryBytes);
        assertEquals(Long.valueOf(2047 * MIB), storage(Core.tlv(2, num(Integer.MAX_VALUE, 4))).freeMemoryBytes);
    }

    @Test public void storageThresholdDiscontinuityIsDocumentedNotSilentlyReinterpreted() throws Exception {
        // 原 H 规则确有这个不连续性；这里只复制 UI 归一量，绝不冒充协议精确 bytes。
        assertEquals(Long.valueOf((MIB - 1) * MIB), storage(Core.tlv(2, num(MIB - 1, 4))).freeMemoryBytes);
        assertEquals(Long.valueOf(MIB * MIB), storage(Core.tlv(2, num(MIB, 4))).freeMemoryBytes);
        assertEquals(Long.valueOf(MIB), storage(Core.tlv(2, num(MIB + 1, 4))).freeMemoryBytes);
    }

    @Test public void zeroMissingAndEmptyStorageRemainUnknownNotZeroBytes() throws Exception {
        assertFalse(storage(new byte[0]).hasObservedFields());
        assertFalse(storage(Core.tlv(2, new byte[0])).hasObservedFields());
        for (int width = 1; width <= 4; width++) assertFalse(storage(Core.tlv(2, num(0, width))).hasObservedFields());
    }

    @Test public void signedHighBitAndSentinelStorageAreNotRepackagedAsHugeUintCapacity() throws Exception {
        fault("MUSIC_STORAGE_RANGE", () -> storage(Core.tlv(2, num(0x80000000L, 4))));
        fault("MUSIC_STORAGE_RANGE", () -> storage(Core.tlv(2, num(0xffffffffL, 4))));
    }

    @Test public void storageSupportsTheOriginalShortNumericShapesInBigEndian() throws Exception {
        assertEquals(Long.valueOf(255 * MIB), storage(Core.tlv(2, num(255, 1))).freeMemoryBytes);
        assertEquals(Long.valueOf(65535 * MIB), storage(Core.tlv(2, num(65535, 2))).freeMemoryBytes);
        assertEquals(Long.valueOf(15 * MIB), storage(Core.tlv(2, num(0xffffff, 3))).freeMemoryBytes);
    }

    @Test public void oversizedStorageIsRejectedInsteadOfReadingOnlyFirstFourBytes() throws Exception {
        fault("NUMERIC_TLV_LENGTH", () -> storage(Core.tlv(2, new byte[] { 0, 0, 2, 0, 1 })));
        fault("NUMERIC_TLV_LENGTH", () -> storage(Core.tlv(2, new byte[8])));
    }

    @Test public void duplicateStorageIsNotResolvedByIgnoringEmptyFirstValue() throws Exception {
        fault("WIRE_DUPLICATE_TAG", () -> storage(fields(Core.tlv(2, num(512, 4)), Core.tlv(2, num(512, 4)))));
        fault("WIRE_DUPLICATE_TAG", () -> storage(fields(Core.tlv(2, new byte[0]), Core.tlv(2, num(512, 4)))));
    }

    @Test public void storageDoesNotReadCountFormatsOrTheUnrequestedExtendedTag01() throws Exception {
        assertFalse(storage(fields(Core.tlv(1, num(512, 4)), Core.tlv(3, new byte[] { 1 }),
                Core.tlv(4, num(100, 4)), Core.tlv(5, num(5, 4)))).hasObservedFields());
    }

    @Test public void wrongRoutingAndUndeclaredExtendedStorageAreRejected() throws Exception {
        for (int[] route : new int[][] { { 0x27, 7 }, { 1, 0x27 }, { 0x25, 0x0d }, { 0x25, 0x0e }, { 0x28, 4 } }) {
            assertFalse(DeviceTelemetry.matches(route[0], route[1]));
            fault("DEVICE_STATS_HEADER_MISMATCH", () -> DeviceTelemetry.parse(route[0], route[1], new byte[0]));
        }
    }

    @Test public void malformedTlvCannotPublishPartiallyDecodedTelemetry() throws Exception {
        fault("TLV_VIEW_TRUNCATED_LENGTH", () -> product(fields(text(9, "SN"), new byte[] { 10 })));
        fault("TLV_VIEW_TRUNCATED_VALUE", () -> battery(new byte[] { 1, 2, 88 }));
        fault("TLV_VIEW_TRUNCATED_VALUE", () -> storage(new byte[] { 2, 4, 0, 1 }));
        fault("TLV_VIEW_BYTES_LIMIT", () -> DeviceTelemetry.parse(1, 7, (byte[]) null));
    }

    @Test public void absentZeroAnd100000HeadersDoNotForgeMissingFields() throws Exception {
        for (byte[] header : new byte[][] { new byte[0], success(0), success(100000) }) {
            assertEquals("SN", product(fields(text(9, "SN"), header)).serialNumber);
            assertEquals(Integer.valueOf(88), battery(fields(Core.tlv(1, new byte[] { 88 }), header)).batteryPercent);
            assertEquals(Long.valueOf(512 * MIB), storage(fields(Core.tlv(2, num(512, 4)), header)).freeMemoryBytes);
            assertFalse(product(header).hasObservedFields());
            assertFalse(battery(header).hasObservedFields());
            assertFalse(storage(header).hasObservedFields());
        }
    }

    @Test public void errorHeadersBlockOtherwiseValidFieldsForEveryTelemetryRoute() throws Exception {
        for (byte[] header : new byte[][] { Core.tlv(0x7f, new byte[] { 1 }), success(100001), success(0xffffffffL) }) {
            fault("DEVICE_STATS_REJECTED", () -> product(fields(text(9, "SN"), header)));
            fault("DEVICE_STATS_REJECTED", () -> battery(fields(Core.tlv(1, new byte[] { 88 }), header)));
            fault("DEVICE_STATS_REJECTED", () -> storage(fields(Core.tlv(2, num(512, 4)), header)));
        }
    }

    @Test public void duplicateAndMalformedErrorHeadersAreRejected() throws Exception {
        byte[] duplicate = fields(success(0), success(0));
        fault("WIRE_DUPLICATE_TAG", () -> product(fields(text(9, "SN"), duplicate)));
        fault("WIRE_DUPLICATE_TAG", () -> battery(fields(Core.tlv(1, new byte[] { 88 }), duplicate)));
        fault("WIRE_DUPLICATE_TAG", () -> storage(fields(Core.tlv(2, num(512, 4)), duplicate)));
        for (int width : new int[] { 0, 5 }) {
            fault("NUMERIC_TLV_LENGTH", () -> product(fields(text(9, "SN"), Core.tlv(0x7f, new byte[width]))));
        }
    }

    @Test public void malformedPublicFieldsAreNotTreatedAsAbsentValues() throws Exception {
        fault("WIRE_FIELDS_REQUIRED", () -> DeviceTelemetry.parse(1, 7, (List<TlvView.Part>) null));
        fault("WIRE_FIELD_INVALID", () -> DeviceTelemetry.batteryPercent(Collections.singletonList(new TlvView.Part(1, null))));
        fault("WIRE_FIELD_INVALID", () -> DeviceTelemetry.musicFreeMemoryBytes(Collections.singletonList(new TlvView.Part(-1, new byte[0]))));
    }

    @Test public void existingProductRequestAndOnlyOneNewStorageRequestHaveExactEmptyTags() throws Exception {
        tags(WristInitialization.productRequest(), 1, 2, 7, 9, 10, 17, 18, 22, 26, 29, 30, 31, 32, 33, 34, 35);
        tags(DeviceTelemetry.musicStorageRequest(), 1, 2, 3);
        assertArrayEquals(Core.unhex("010002000300"), DeviceTelemetry.musicStorageRequest());
    }

    @Test public void ownerClaimsInitBeforeSendingAndNeverTwiceInTheSameEpoch() {
        DeviceTelemetry.ProbeOwner owner = new DeviceTelemetry.ProbeOwner(7);
        assertFalse(owner.batteryQueryClaimed(7));
        assertFalse(owner.markInitializationRequests(6));
        assertTrue(owner.markInitializationRequests(7));
        assertTrue(owner.batteryQueryClaimed(7));
        assertFalse(owner.batteryQueryClaimed(6));
        assertTrue(owner.pending(7, 1, 7));
        assertTrue(owner.pending(7, 1, 8));
        assertFalse(owner.pending(7, 0x25, 4));
        assertFalse(owner.markInitializationRequests(7));
        assertFalse(owner.pending(6, 1, 7));
        assertTrue(owner.complete(7, 1, 8));
        assertFalse(owner.pending(7, 1, 8));
        assertTrue(owner.batteryQueryClaimed(7));
        assertFalse(owner.complete(7, 1, 8));
        assertNull(owner.publication(7, true, true));
    }

    @Test public void readyOwnerRequiresVerifiedReadyAndCapabilityThenMarksBeforeSend() {
        DeviceTelemetry.ProbeOwner owner = new DeviceTelemetry.ProbeOwner(7);
        assertFalse(owner.markReadyStorageRequest(7, false, true, true));
        assertFalse(owner.markReadyStorageRequest(7, true, false, true));
        assertFalse(owner.markReadyStorageRequest(6, true, true, true));
        assertTrue(owner.markReadyStorageRequest(7, true, true, true));
        assertTrue(owner.pending(7, 0x25, 4));
        assertFalse(owner.markReadyStorageRequest(7, true, true, true));
    }

    @Test public void unsupportedReadyIsAOnceDecisionNotAnInvitationToRetryOnRefresh() {
        DeviceTelemetry.ProbeOwner owner = new DeviceTelemetry.ProbeOwner(7);
        assertFalse(owner.markReadyStorageRequest(7, true, true, false));
        assertFalse(owner.pending(7, 0x25, 4));
        assertFalse(owner.markReadyStorageRequest(7, true, true, true));
    }

    @Test public void oldEpochCannotConsumeObserveOrPublishCurrentQueries() throws Exception {
        DeviceTelemetry.ProbeOwner owner = new DeviceTelemetry.ProbeOwner(7);
        owner.markInitializationRequests(7);
        assertFalse(owner.complete(6, 1, 7));
        assertTrue(owner.pending(7, 1, 7));
        owner.observe(6, product(text(9, "STALE")));
        assertNull(owner.publication(7, true, true));
        assertNull(owner.publication(6, true, true));
    }

    @Test public void initDataWaitsForReadyAndLateStoragePublishesOnlyItsActualField() throws Exception {
        DeviceTelemetry.ProbeOwner owner = new DeviceTelemetry.ProbeOwner(7);
        owner.markInitializationRequests(7);
        assertTrue(owner.complete(7, 1, 7)); owner.observe(7, product(text(9, "SN")));
        assertTrue(owner.complete(7, 1, 8)); owner.observe(7, battery(Core.tlv(1, new byte[] { 88 })));
        assertNull(owner.publication(7, false, false));
        assertNull(owner.publication(7, false, true));
        assertNull(owner.publication(7, true, false));
        DeviceTelemetry.Stats initial = owner.publication(7, true, true);
        assertEquals("SN", initial.serialNumber);
        assertEquals(Integer.valueOf(88), initial.batteryPercent);
        assertNull(initial.freeMemoryBytes);
        assertNull(owner.publication(7, true, true));
        assertTrue(owner.markReadyStorageRequest(7, true, true, true));
        assertTrue(owner.complete(7, 0x25, 4)); owner.observe(7, storage(Core.tlv(2, num(512, 4))));
        DeviceTelemetry.Stats late = owner.publication(7, true, true);
        assertNull(late.serialNumber);
        assertNull(late.batteryPercent);
        assertEquals(Long.valueOf(512 * MIB), late.freeMemoryBytes);
        assertNull(owner.publication(7, true, true));
    }

    @Test public void nullPartialAndUnknownRepliesNeverEraseAnObservedField() throws Exception {
        DeviceTelemetry.ProbeOwner owner = new DeviceTelemetry.ProbeOwner(7);
        owner.observe(7, product(text(9, "SN")));
        owner.observe(7, product(new byte[0]));
        owner.observe(7, battery(new byte[0]));
        owner.observe(7, storage(Core.tlv(2, num(0, 4))));
        assertEquals("SN", owner.publication(7, true, true).serialNumber);
        owner.observe(7, product(new byte[0]));
        assertNull(owner.publication(7, true, true));
    }

    @Test public void firstOwnedUnknownReplyConsumesOnceWithoutAFalseSuccessfulPublication() throws Exception {
        DeviceTelemetry.ProbeOwner owner = new DeviceTelemetry.ProbeOwner(7);
        owner.markInitializationRequests(7);
        assertTrue(owner.complete(7, 1, 7)); owner.observe(7, product(success(0)));
        assertFalse(owner.complete(7, 1, 7));
        assertNull(owner.publication(7, true, true));
    }

    @Test public void compatibleBatteryStillWaitsForCurrentVerifiedReadyPublication() throws Exception {
        DeviceTelemetry.ProbeOwner owner = new DeviceTelemetry.ProbeOwner(7);
        assertTrue(owner.markInitializationRequests(7));
        assertTrue(owner.complete(7, 1, 8));
        owner.observe(7, battery(Core.tlv(1, new byte[] { 88, (byte) 255 })));
        assertNull(owner.publication(7, false, false));
        assertNull(owner.publication(7, false, true));
        assertNull(owner.publication(7, true, false));
        assertNull(owner.publication(6, true, true));
        DeviceTelemetry.Stats observed = owner.publication(7, true, true);
        assertEquals(Integer.valueOf(88), observed.batteryPercent);
        assertNull(observed.serialNumber);
        assertNull(observed.freeMemoryBytes);
        assertNull(owner.publication(7, true, true));
        assertFalse(owner.complete(7, 1, 8));
        assertTrue(owner.batteryQueryClaimed(7));
        assertFalse(owner.batteryQueryClaimed(6));
    }

    @Test public void ownedMissingBatteryRemainsUnknownWithoutASecondQuery() throws Exception {
        DeviceTelemetry.ProbeOwner owner = new DeviceTelemetry.ProbeOwner(7);
        assertTrue(owner.markInitializationRequests(7));
        assertTrue(owner.complete(7, 1, 8));
        owner.observe(7, battery(Core.tlv(1, new byte[0])));
        assertNull(owner.publication(7, true, true));
        assertFalse(owner.pending(7, 1, 8));
        assertFalse(owner.markInitializationRequests(7));
        assertFalse(owner.complete(7, 1, 8));
    }

    @Test public void repeatedReadyAndRefreshDecisionsProduceZeroAdditionalQueries() throws Exception {
        DeviceTelemetry.ProbeOwner owner = new DeviceTelemetry.ProbeOwner(7);
        assertTrue(owner.markInitializationRequests(7));
        assertTrue(owner.markReadyStorageRequest(7, true, true, true));
        int additional = 0;
        for (int refresh = 0; refresh < 100; refresh++) {
            if (owner.markInitializationRequests(7)) additional += 2;
            if (owner.markReadyStorageRequest(7, true, true, true)) additional++;
            assertNull(owner.publication(7, true, true));
        }
        assertEquals(0, additional);
    }

    @Test public void aNewConnectionEpochHasFreshUnknownDataAndCanQueryAgain() throws Exception {
        DeviceTelemetry.ProbeOwner old = new DeviceTelemetry.ProbeOwner(7);
        old.markInitializationRequests(7); old.markReadyStorageRequest(7, true, true, true);
        old.observe(7, product(text(9, "OLD"))); old.publication(7, true, true);
        DeviceTelemetry.ProbeOwner fresh = new DeviceTelemetry.ProbeOwner(9);
        assertFalse(fresh.batteryQueryClaimed(9));
        assertFalse(old.batteryQueryClaimed(9));
        assertNull(fresh.publication(9, true, true));
        assertTrue(fresh.markInitializationRequests(9));
        assertTrue(fresh.batteryQueryClaimed(9));
        assertFalse(fresh.batteryQueryClaimed(7));
        assertTrue(fresh.markReadyStorageRequest(9, true, true, true));
        assertFalse(fresh.complete(7, 1, 7));
        assertTrue(fresh.pending(9, 1, 7));
    }

    private static DeviceTelemetry.Stats product(byte[] body) throws IOException { return DeviceTelemetry.parse(1, 7, body); }
    private static DeviceTelemetry.Stats battery(byte[] body) throws IOException { return DeviceTelemetry.parse(1, 8, body); }
    private static DeviceTelemetry.Stats storage(byte[] body) throws IOException { return DeviceTelemetry.parse(0x25, 4, body); }
    private static byte[] text(int tag, String value) throws IOException { return Core.tlv(tag, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] success(long code) throws IOException { return Core.tlv(0x7f, num(code, 4)); }
    private static byte[] num(long value, int width) { byte[] bytes = new byte[width]; for (int i = width - 1; i >= 0; i--) { bytes[i] = (byte) value; value >>>= 8; } return bytes; }
    private static byte[] fields(byte[]... fields) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] field : fields) out.write(field); return out.toByteArray(); }
    private static String repeat(char value, int count) { char[] text = new char[count]; Arrays.fill(text, value); return new String(text); }
    private static void tags(byte[] body, int... expected) throws IOException { List<TlvView.Part> fields = TlvView.decode(body); assertEquals(expected.length, fields.size()); for (int i = 0; i < expected.length; i++) { assertEquals(expected[i], fields.get(i).tag); assertEquals(0, fields.get(i).value.length); } }
    private static void fault(String code, ThrowingRunnable action) { Core.Fault fault = assertThrows(Core.Fault.class, action); assertEquals(code, fault.code); }
}
