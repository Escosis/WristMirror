package io.github.escosis.hwtfaceswap.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 独立处理原生运动健康 TLV 数据体与观察；不含 Android、厂商调用、帧封装、加密或 app-ID 猜测。 */
public final class Wire {
    /** TLV 最多 64 KiB，加上原生服务与命令字节后仍小于 65 KiB。 */
    public static final int MAX_NATIVE_PACKET_BYTES = TlvView.MAX_BYTES + 2;
    private static final int MAX_TEXT_BYTES = 32;

    private Wire() {}

    /** 仅生成 TLV 数据体；原生命令与传输层另行提供 0x27/0x03 及原有帧封装。 */
    public static byte[] operation(String id, String ver) throws IOException {
        Core.resource(id, ver);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        append(out, 1, id.getBytes(StandardCharsets.US_ASCII));
        append(out, 2, ver.getBytes(StandardCharsets.US_ASCII));
        append(out, 3, new byte[] { 1 });
        return out.toByteArray();
    }

    /** 原生删除使用实际已安装版本与 op2，绝不上传文件或追加授权。 */
    public static byte[] deletion(String id, String ver) throws IOException {
        Core.resource(id, ver);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        append(out, 1, id.getBytes(StandardCharsets.US_ASCII));
        append(out, 2, ver.getBytes(StandardCharsets.US_ASCII));
        append(out, 3, new byte[] { 2 });
        return out.toByteArray();
    }

    /** 原生 0x27/0x01 数据体；不请求或编造未知的 mode/sync 字段。 */
    public static byte[] params() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int tag : new int[] { 1, 2, 3, 4, 5, 0x14, 0x15 }) {
            append(out, tag, new byte[0]);
        }
        return out.toByteArray();
    }

    /** 原生 0x27/0x02 数据体；保留调用方真实的 serverMode，包括模式 2。 */
    public static byte[] list(int serverMode) throws IOException {
        if (serverMode < 1 || serverMode > 3) throw new Core.Fault("LIST_SERVER_MODE_INVALID");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        append(out, 1, new byte[0]);
        append(out, 6, new byte[] { (byte) serverMode });
        return out.toByteArray();
    }

    private static void append(ByteArrayOutputStream out, int tag, byte[] value) throws IOException {
        byte[] field = Core.tlv(tag, value);
        out.write(field, 0, field.length);
    }

    /**
     * 解码恰好以服务与命令两个字节开头的原生数据包。
     * 仅在 Environment.sIsAarInTheme 为 false 时使用，该检查由适配层负责；
     * 本工具绝不猜测或跳过 appId、加密封装或其他帧格式。
     */
    public static List<TlvView.Part> fields(byte[] nativePacket, int service, int command) throws IOException {
        if (service < 0 || service > 255 || command < 0 || command > 255) {
            throw new Core.Fault("WIRE_SERVICE_COMMAND_INVALID");
        }
        if (nativePacket == null || nativePacket.length < 2) {
            throw new Core.Fault("WIRE_PACKET_TRUNCATED");
        }
        if (nativePacket.length > MAX_NATIVE_PACKET_BYTES) {
            throw new Core.Fault("WIRE_PACKET_BYTES_LIMIT");
        }
        byte[] snapshot = nativePacket.clone();
        if ((snapshot[0] & 255) != service || (snapshot[1] & 255) != command) {
            throw new Core.Fault("WIRE_PACKET_HEADER_MISMATCH");
        }
        return TlvView.decode(Arrays.copyOfRange(snapshot, 2, snapshot.length));
    }

    /** 缺失字段返回 null；请求标签重复或公开输入格式非法时明确报错。 */
    public static TlvView.Part unique(List<TlvView.Part> fields, int tag) throws IOException {
        if (tag < 0 || tag > 255) throw new Core.Fault("WIRE_TAG_INVALID");
        validateParts(fields);
        TlvView.Part found = null;
        for (TlvView.Part field : fields) {
            if (field.tag != tag) continue;
            if (found != null) throw new Core.Fault("WIRE_DUPLICATE_TAG");
            found = field;
        }
        return found;
    }

    /** 非空且长度有界的 UTF-8 文本；不静默截断、不强制转换数字，也不替换非法字节。 */
    public static String text(List<TlvView.Part> fields, int tag) throws IOException {
        TlvView.Part field = unique(fields, tag);
        if (field == null) return null;
        if (field.value.length == 0 || field.value.length > MAX_TEXT_BYTES) {
            throw new Core.Fault("WIRE_TEXT_LENGTH_INVALID");
        }
        return Core.utf8(field.value);
    }

    /** 1..4 字节大端无符号数值；缺失时保持 null，绝不默认成零。 */
    public static Long number(List<TlvView.Part> fields, int tag) throws IOException {
        TlvView.Part field = unique(fields, tag);
        return field == null ? null : Long.valueOf(Core.number(field.value));
    }

    /**
     * 已观察到的列表结构为唯一 root81 → 重复 82 → 唯一 03/04/05 与可选 07。
     * 缺失 root81 不等于空列表；存在但为空的 root81 才是有效的空列表。
     * 每个条目需要非空 ID、版本文本及类型数值，可选 07 可以缺失。
     * 其他标签仅保留为观察，不猜测容器或资源身份。
     * 共享节点预算覆盖根字段、82 条目及条目字段，而非只限制每一层。
     * 不符合 Core.resource 的有效文本仍可显示为不支持的条目；
     * 所选替换目标另由 VersionPolicy/Core.resource 校验。
     */
    public static List<TlvView.Face> faces(List<TlvView.Part> fields) throws IOException {
        TlvView.Part root = unique(fields, 0x81);
        if (root == null) throw new Core.Fault("WIRE_FACE_LIST_ROOT_MISSING");
        Budget budget = new Budget();
        budget.initial(fields);
        List<TlvView.Part> entries = budget.decode(root.value);
        List<TlvView.Face> out = new ArrayList<TlvView.Face>();
        for (TlvView.Part entry : entries) {
            if (entry.tag != 0x82) continue;
            List<TlvView.Part> data = budget.decode(entry.value);
            String id = text(data, 3);
            String ver = text(data, 4);
            Long type = number(data, 5);
            Long expand = number(data, 7);
            if (id == null || ver == null || type == null) {
                throw new Core.Fault("WIRE_FACE_REQUIRED_FIELD_MISSING");
            }
            out.add(new TlvView.Face(id, ver, type, expand));
        }
        return Collections.unmodifiableList(out);
    }

    private static void validateParts(List<TlvView.Part> fields) throws IOException {
        if (fields == null) throw new Core.Fault("WIRE_FIELDS_REQUIRED");
        if (fields.size() > TlvView.MAX_FIELDS) throw new Core.Fault("WIRE_FIELDS_LIMIT");
        long bytes = 0;
        for (TlvView.Part field : fields) {
            if (field == null || field.tag < 0 || field.tag > 255 || field.value == null) {
                throw new Core.Fault("WIRE_FIELD_INVALID");
            }
            bytes += field.value.length;
            if (bytes > TlvView.MAX_BYTES) throw new Core.Fault("WIRE_FIELDS_BYTES_LIMIT");
        }
    }

    private static final class Budget {
        private int nodesLeft = TlvView.MAX_FIELDS;
        // 只处理已知的三层；此总处理上限也限制所有解码值副本的规模。
        private int bytesLeft = TlvView.MAX_BYTES * TlvView.MAX_DEPTH;

        void initial(List<TlvView.Part> fields) throws IOException {
            takeNodes(fields.size());
            for (TlvView.Part field : fields) takeBytes(field.value.length);
        }

        List<TlvView.Part> decode(byte[] bytes) throws IOException {
            if (bytes == null || bytes.length > TlvView.MAX_BYTES) {
                throw new Core.Fault("TLV_VIEW_BYTES_LIMIT");
            }
            takeBytes(bytes.length);
            List<TlvView.Part> decoded = TlvView.decode(bytes);
            takeNodes(decoded.size());
            return decoded;
        }

        private void takeNodes(int count) throws IOException {
            if (count > nodesLeft) throw new Core.Fault("WIRE_TOTAL_FIELDS_LIMIT");
            nodesLeft -= count;
        }

        private void takeBytes(int count) throws IOException {
            if (count > bytesLeft) throw new Core.Fault("WIRE_TOTAL_BYTES_LIMIT");
            bytesLeft -= count;
        }
    }
}
