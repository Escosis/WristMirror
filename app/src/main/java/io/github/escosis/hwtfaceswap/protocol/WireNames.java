package io.github.escosis.hwtfaceswap.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 独立处理 27/06 TLV 数据体；协议依据为运动健康 sendGetNameCommand()/handleOneTlv()，
 * 以及原生实现中明确解析 82/83 名称响应的流程；不调用厂商组件、Android 或网络。
 * 此命令没有 version/nonce；调用方必须关联设备、尚未完成请求的协议 ID
 * 及对应的列表观察，绝不据此判定认证结果。
 */
public final class WireNames {
    public static final int LANGUAGE_OTHER=1, LANGUAGE_CHINESE=2;
    public static final int MAX_NAME_BYTES=256, MAX_NAME_CHARACTERS=128;
    public static final int MAX_REQUEST_IDS=(TlvView.MAX_FIELDS-2)/2;
    private static final int MAX_ID_TEXT_BYTES=32;
    private WireNames() {}

    private static boolean validId(String id) {
        try{Core.resource(id,"0.0");return true;}catch(IOException e){return false;}
    }
    private static void append(ByteArrayOutputStream out,int tag,byte[] value) throws IOException {
        byte[] bytes=Core.tlv(tag,value);out.write(bytes,0,bytes.length);
    }

    /**
     * 仅生成数据体：01/len1/language + 82/{83/{04/id UTF-8}...}。
     * CN=2/other=1 来自已读取的原生 Manager 调用方，不是编造的 ISO 语言代码。
     * 没有可查询名称的 ID 时返回零长度数据体，调用方必须跳过发送。
     * 不同版本的同一 ID 只查询一次，因为实际名称命令没有版本字段。
     * 不支持或无法解释的 ID 仍可用于界面回退展示，但不编造或改写。
     */
    public static byte[] request(List<TlvView.Face> faces,int language) throws IOException {
        if(language!=LANGUAGE_OTHER && language!=LANGUAGE_CHINESE)throw new Core.Fault("NAMES_LANGUAGE_INVALID");
        if(faces==null || faces.isEmpty())return new byte[0];
        if(faces.size()>TlvView.MAX_FIELDS)throw new Core.Fault("NAMES_REQUEST_FIELDS_LIMIT");
        LinkedHashSet<String> ids=new LinkedHashSet<String>();
        for(TlvView.Face face:faces)if(face!=null && validId(face.id)){
            ids.add(face.id);if(ids.size()>MAX_REQUEST_IDS)throw new Core.Fault("NAMES_REQUEST_IDS_LIMIT");
        }
        if(ids.isEmpty())return new byte[0];
        ByteArrayOutputStream entries=new ByteArrayOutputStream();
        for(String id:ids){byte[] item=Core.tlv(4,id.getBytes(StandardCharsets.UTF_8));append(entries,0x83,item);}
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        append(out,1,new byte[]{(byte)language});append(out,0x82,entries.toByteArray());
        if(out.size()>TlvView.MAX_BYTES)throw new Core.Fault("NAMES_REQUEST_BYTES_LIMIT");
        return out.toByteArray();
    }

    /**
     * 调用方验证原生 27/06 包头后传入的字段。
     * 解析全部已知 82 根及重复 83 条目；不递归猜测未知容器。
     * 条目 04 是 ID，05 是名称，06 简介、07 大小、08 实例仅作为元数据。
     * 名称空或缺失、ID 不支持或缺失时只省略该关联，绝不编造名称。
     * UTF-8 非法或超限、关键标签重复、有效 ID 重复时均报错。
     * 存在但为空的 82 表示空结果；缺失 82 明确表示可选查询失败。
     * 多个根或页面可在本次有界解析内合并；跨包重复 ID
     * 及迟到响应由请求拥有者另行处理。
     */
    public static Map<String,String> parse(List<TlvView.Part> fields) throws IOException {
        Budget budget=new Budget();budget.initial(fields);
        LinkedHashMap<String,String> result=new LinkedHashMap<String,String>();
        HashSet<String> seenIds=new HashSet<String>();boolean container=false;
        for(TlvView.Part root:fields){
            if(root.tag!=0x82)continue;container=true;
            for(TlvView.Part entry:budget.decode(root.value)){
                if(entry.tag!=0x83)continue;
                List<TlvView.Part> data=budget.decode(entry.value);
                TlvView.Part idField=unique(data,4),nameField=unique(data,5);
                String id=null,name=null;
                if(idField!=null && idField.value.length!=0){
                    if(idField.value.length>MAX_ID_TEXT_BYTES)throw new Core.Fault("NAMES_ID_BYTES_LIMIT");
                    id=Core.utf8(idField.value);
                }
                if(nameField!=null){
                    if(nameField.value.length>MAX_NAME_BYTES)throw new Core.Fault("NAMES_NAME_BYTES_LIMIT");
                    name=Core.utf8(nameField.value);
                    if(name.codePointCount(0,name.length())>MAX_NAME_CHARACTERS)throw new Core.Fault("NAMES_NAME_CHARACTER_LIMIT");
                }
                if(!validId(id))continue; // 没有可建立的名称关联，不表示权限失败
                if(!seenIds.add(id))throw new Core.Fault("NAMES_DUPLICATE_ID");
                if(!hasVisibleText(name))continue;
                result.put(id,name); // 保留实际 UTF-8 名称，由调用方整理为单行界面文本
            }
        }
        if(!container)throw new Core.Fault("NAMES_RESPONSE_CONTAINER_MISSING");
        return Collections.unmodifiableMap(result);
    }

    private static boolean hasVisibleText(String name) {
        if(name==null)return false;
        for(int i=0;i<name.length();){int cp=name.codePointAt(i);i+=Character.charCount(cp);
            if(!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && !Character.isISOControl(cp))return true;
        }return false;
    }
    private static TlvView.Part unique(List<TlvView.Part> fields,int tag) throws IOException {
        TlvView.Part found=null;for(TlvView.Part p:fields)if(p.tag==tag){
            if(found!=null)throw new Core.Fault("NAMES_DUPLICATE_TAG");found=p;
        }return found;
    }
    private static final class Budget {
        int nodes=TlvView.MAX_FIELDS,bytes=TlvView.MAX_BYTES*TlvView.MAX_DEPTH;
        void takeNodes(int count) throws IOException {if(count>nodes)throw new Core.Fault("NAMES_TOTAL_FIELDS_LIMIT");nodes-=count;}
        void takeBytes(int count) throws IOException {if(count>bytes)throw new Core.Fault("NAMES_TOTAL_BYTES_LIMIT");bytes-=count;}
        void initial(List<TlvView.Part> fields) throws IOException {
            if(fields==null)throw new Core.Fault("NAMES_FIELDS_REQUIRED");
            if(fields.size()>TlvView.MAX_FIELDS)throw new Core.Fault("NAMES_FIELDS_LIMIT");
            takeNodes(fields.size());long encoded=0;
            for(TlvView.Part p:fields){
                if(p==null || p.tag<0 || p.tag>255 || p.value==null)throw new Core.Fault("NAMES_FIELD_INVALID");
                encoded+=1+Core.length(p.value.length).length+p.value.length;
                if(encoded>TlvView.MAX_BYTES)throw new Core.Fault("NAMES_FIELDS_BYTES_LIMIT");takeBytes(p.value.length);
            }
        }
        List<TlvView.Part> decode(byte[] value) throws IOException {
            takeBytes(value.length);List<TlvView.Part> fields=TlvView.decode(value);takeNodes(fields.size());return fields;
        }
    }
}
