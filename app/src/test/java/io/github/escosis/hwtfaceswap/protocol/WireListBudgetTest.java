package io.github.escosis.hwtfaceswap.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import static org.junit.Assert.*;

/** 合成有效列表/边界回归。2687B仅对应日志中长度，不冒充恢复出的实机正文或数量。 */
public final class WireListBudgetTest {
    @Test public void representative2687ByteListExceedsOldSharedBudgetButParsesFully() throws Exception {
        byte[] body = catalog(92, 1, 0, 14);
        assertEquals(2687, body.length);
        assertEquals(554, countNodes(body));
        assertTrue(countNodes(body) > TlvView.MAX_FIELDS);
        List<TlvView.Face> faces = parse(body);
        assertEquals(92, faces.size());
        assertEquals("1000000000", faces.get(0).id);
        assertEquals("1000000091", faces.get(91).id);
        assertEquals("2.7.1", faces.get(91).version);
        assertEquals(Long.valueOf(5), faces.get(0).type);
        assertEquals(Long.valueOf(0), faces.get(91).expandType);
        assertSame(faces.get(0), FacePolicy.defaultSelection(faces));
    }

    @Test public void fullSingleContainerCapacityIsNotMistakenForTotalTreeCapacity() throws Exception {
        byte[] body = catalog(TlvView.MAX_FIELDS, 0, 0, -1);
        assertEquals(2561, countNodes(body));
        assertEquals(TlvView.MAX_FIELDS, parse(body).size());
    }

    @Test public void exact4096NodeBoundaryIsAccepted() throws Exception {
        byte[] body = catalog(512, 3, -1, -1);
        assertEquals(4096, countNodes(body));
        assertTrue(body.length < TlvView.MAX_BYTES);
        assertEquals(512, parse(body).size());
    }

    @Test public void oneNodeBeyond4096IsStillRejected() throws Exception {
        byte[] body = catalog(512, 3, 0, -1);
        assertEquals(4097, countNodes(body));
        assertTrue(body.length < TlvView.MAX_BYTES);
        assertFault("WIRE_TOTAL_FIELDS_LIMIT", () -> parse(body));
    }

    @Test public void cumulativeBudgetIsResetForEachPacket() throws Exception {
        byte[] body = catalog(92, 1, 0, 14);
        for (int i = 0; i < 4; i++) assertEquals(92, parse(body).size());
    }

    @Test public void entryContainerLimitRemains512() throws Exception {
        assertFault("TLV_VIEW_FIELDS_LIMIT", () -> parse(catalog(513, 0, 0, -1)));
    }

    @Test public void oneEntryCanUseItsFullLocalFieldBudget() throws Exception {
        byte[] body = catalog(1, TlvView.MAX_FIELDS - 4, 0, -1);
        assertEquals(514, countNodes(body));
        assertEquals(1, parse(body).size());
    }

    @Test public void oneEntryCannotExceedItsLocalFieldBudget() throws Exception {
        assertFault("TLV_VIEW_FIELDS_LIMIT", () -> parse(catalog(1, TlvView.MAX_FIELDS - 3, 0, -1)));
    }

    @Test public void directRootFieldsLimitIsUnchanged() throws Exception {
        List<TlvView.Part> fields = new ArrayList<>();
        fields.add(new TlvView.Part(0x81, new byte[0]));
        for (int i = 1; i < TlvView.MAX_FIELDS; i++) fields.add(new TlvView.Part(0x20, new byte[0]));
        assertTrue(Wire.faces(fields).isEmpty());
        fields.add(new TlvView.Part(0x20, new byte[0]));
        assertFault("WIRE_FIELDS_LIMIT", () -> Wire.faces(fields));
    }

    @Test public void full64KiBBodyAndNativeHeaderBoundaryStillWork() throws Exception {
        byte[] prefix = catalog(1, 0, 0, -1);
        byte[] body = fields(prefix, Core.tlv(0x20, new byte[TlvView.MAX_BYTES - prefix.length - 4]));
        assertEquals(TlvView.MAX_BYTES, body.length);
        assertEquals(1, parse(body).size());
        byte[] packet = fields(new byte[] { 0x27, 0x02 }, body);
        assertEquals(Wire.MAX_NATIVE_PACKET_BYTES, packet.length);
        assertEquals(1, Wire.faces(Wire.fields(packet, 0x27, 0x02)).size());
    }

    @Test public void byteLimitsAreNotRemovedByNodeBudgetChange() throws Exception {
        assertFault("TLV_VIEW_BYTES_LIMIT", () -> parse(new byte[TlvView.MAX_BYTES + 1]));
        byte[] tooLongPacket = new byte[Wire.MAX_NATIVE_PACKET_BYTES + 1];
        assertFault("WIRE_PACKET_BYTES_LIMIT", () -> Wire.fields(tooLongPacket, 0x27, 0x02));
        List<TlvView.Part> parts = Arrays.asList(new TlvView.Part(0x81, entry(0, 0, 5, 0)),
                new TlvView.Part(0x20, new byte[TlvView.MAX_BYTES]));
        assertFault("WIRE_FIELDS_BYTES_LIMIT", () -> Wire.faces(parts));
    }

    @Test public void missingRootAndDuplicateRootDoNotBecomeValidLists() throws Exception {
        assertFault("WIRE_FACE_LIST_ROOT_MISSING", () -> parse(Core.tlv(0x20, new byte[0])));
        byte[] body = catalog(1, 0, 0, -1);
        assertFault("WIRE_DUPLICATE_TAG", () -> parse(fields(body, body)));
    }

    @Test public void aPresentEmptyRootIsStillAnEmptyList() throws Exception {
        assertTrue(parse(Core.tlv(0x81, new byte[0])).isEmpty());
    }

    @Test public void allRequiredEntryFieldsRemainRequired() throws Exception {
        byte[][] mandatory = { text(3, "1000000000"), text(4, "2.7.1"), number(5, 5) };
        for (int missing = 0; missing < mandatory.length; missing++) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            for (int i = 0; i < mandatory.length; i++) if (i != missing) data.write(mandatory[i]);
            byte[] body = listFromEntryData(data.toByteArray());
            assertFault("WIRE_FACE_REQUIRED_FIELD_MISSING", () -> parse(body));
        }
    }

    @Test public void duplicateSemanticFieldsAreStillRejected() throws Exception {
        byte[] data = fields(text(3, "1000000000"), text(4, "2.7.1"), number(5, 5), number(7, 0));
        for (byte[] duplicate : new byte[][] { text(3, "1000000000"), text(4, "2.7.1"), number(5, 5), number(7, 0) }) {
            byte[] body = listFromEntryData(fields(data, duplicate));
            assertFault("WIRE_DUPLICATE_TAG", () -> parse(body));
        }
    }

    @Test public void malformedUtf8AndNumericValuesAreNotNormalizedIntoEntries() throws Exception {
        byte[] invalidText = listFromEntryData(fields(text(3, "1000000000"),
                Core.tlv(4, new byte[] { (byte) 0xc3, 0x28 }), number(5, 5), number(7, 0)));
        assertFault("INVALID_UTF8", () -> parse(invalidText));
        for (byte[] value : new byte[][] { new byte[0], new byte[5] }) {
            byte[] body = listFromEntryData(fields(text(3, "1000000000"), text(4, "2.7.1"),
                    Core.tlv(5, value), number(7, 0)));
            assertFault("NUMERIC_TLV_LENGTH", () -> parse(body));
        }
    }

    @Test public void emptyAndOversizedResourceTextAreNotAcceptedSilently() throws Exception {
        byte[] empty = listFromEntryData(fields(text(3, ""), text(4, "2.7.1"), number(5, 5)));
        assertFault("WIRE_TEXT_LENGTH_INVALID", () -> parse(empty));
        byte[] longId = listFromEntryData(fields(text(3, "123456789012345678901234567890123"),
                text(4, "2.7.1"), number(5, 5)));
        assertFault("WIRE_TEXT_LENGTH_INVALID", () -> parse(longId));
    }

    @Test public void optionalExpandAbsenceAndSpecialTypesNeverGrantOrdinaryEligibility() throws Exception {
        TlvView.Face missingExpand = parse(listFromEntryData(fields(text(3, "1000000000"), text(4, "2.7.1"), number(5, 5)))).get(0);
        assertNull(missingExpand.expandType);
        assertFalse(FacePolicy.eligible(missingExpand));
        TlvView.Face photo = parse(Core.tlv(0x81, entry(0, 0, 42, 2))).get(0);
        assertEquals("相册表盘", FacePolicy.kind(photo));
        assertFalse(FacePolicy.eligible(photo));
        assertNull(FacePolicy.defaultSelection(Collections.singletonList(photo)));
    }

    @Test public void structurallyVisibleUnsupportedResourceIsNotAnOperationTarget() throws Exception {
        TlvView.Face unsupported = parse(listFromEntryData(fields(text(3, "not-an-id"), text(4, "2.7.1"), number(5, 5), number(7, 0)))).get(0);
        assertEquals("not-an-id", unsupported.id);
        assertFalse(unsupported.validResource());
        assertFalse(FacePolicy.eligible(unsupported));
    }

    @Test public void unknownContainersStayOpaqueRatherThanCreatingFaces() throws Exception {
        byte[] unknown = Core.tlv(0x81, Core.tlv(0x83, catalog(1, 0, 0, -1)));
        assertTrue(parse(unknown).isEmpty());
    }

    @Test public void malformedTlvAndWrongNativeHeaderAreStillRejected() throws Exception {
        assertFault("TLV_VIEW_TRUNCATED_VALUE", () -> parse(new byte[] { (byte) 0x81, 4, (byte) 0x82, 0 }));
        byte[] wrongPacket = fields(new byte[] { 0x27, 0x03 }, catalog(1, 0, 0, -1));
        assertFault("WIRE_PACKET_HEADER_MISMATCH", () -> Wire.fields(wrongPacket, 0x27, 0x02));
    }

    @Test public void listOperationAndDeletionWireBodiesAreUnchanged() throws Exception {
        assertArrayEquals(Core.unhex("0100060102"), Wire.list(2));
        List<TlvView.Part> operation = TlvView.decode(Wire.operation("1000000000", "2.7.2"));
        List<TlvView.Part> deletion = TlvView.decode(Wire.deletion("1000000000", "2.7.1"));
        assertEquals(3, operation.size());
        assertEquals(3, deletion.size());
        assertEquals(Long.valueOf(1), Wire.number(operation, 3));
        assertEquals(Long.valueOf(2), Wire.number(deletion, 3));
        assertNull(Wire.unique(operation, 8));
        assertNull(Wire.unique(operation, 9));
    }

    private static List<TlvView.Face> parse(byte[] body) throws Exception { return Wire.faces(TlvView.decode(body)); }

    private static byte[] catalog(int count, int extraPerEntry, int firstAdjustment, int rootPadding) throws Exception {
        ByteArrayOutputStream rows = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) rows.write(entry(i, extraPerEntry + (i == 0 ? firstAdjustment : 0), i == 0 ? 5 : 4, 0));
        byte[] root = Core.tlv(0x81, rows.toByteArray());
        return rootPadding < 0 ? root : fields(root, Core.tlv(0x20, new byte[rootPadding]));
    }

    private static byte[] entry(int index, int extras, int type, int expand) throws Exception {
        ByteArrayOutputStream row = new ByteArrayOutputStream();
        row.write(text(3, Long.toString(1000000000L + index)));
        row.write(text(4, "2.7.1"));
        row.write(number(5, type));
        row.write(number(7, expand));
        for (int i = 0; i < extras; i++) row.write(Core.tlv(0x20, new byte[0]));
        return Core.tlv(0x82, row.toByteArray());
    }

    private static byte[] listFromEntryData(byte[] data) throws Exception { return Core.tlv(0x81, Core.tlv(0x82, data)); }
    private static byte[] text(int tag, String text) throws Exception { return Core.tlv(tag, text.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] number(int tag, int number) throws Exception { return Core.tlv(tag, new byte[] { (byte) number }); }
    private static byte[] fields(byte[]... fields) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] field : fields) out.write(field);
        return out.toByteArray();
    }
    private static int countNodes(byte[] body) throws Exception {
        List<TlvView.Part> root = TlvView.decode(body);
        List<TlvView.Part> entries = TlvView.decode(Wire.unique(root, 0x81).value);
        int count = root.size() + entries.size();
        for (TlvView.Part entry : entries) if (entry.tag == 0x82) count += TlvView.decode(entry.value).size();
        return count;
    }
    private static void assertFault(String expected, ThrowingRunnable action) {
        Core.Fault fault = assertThrows(Core.Fault.class, action);
        assertEquals(expected, fault.code);
    }
}
