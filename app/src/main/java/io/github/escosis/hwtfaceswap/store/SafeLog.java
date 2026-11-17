package io.github.escosis.hwtfaceswap.store;

import android.content.Context;
import io.github.escosis.hwtfaceswap.protocol.TlvView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;

/** 有界保存脱敏通信元数据，由用户主动导出；排除认证正文、令牌、密钥、配对码与账户正文。 */
public final class SafeLog {
    private static final int MAX_FILE=256*1024,MAX_ROWS=400;
    private final File file;
    private final ArrayDeque<String> rows=new ArrayDeque<>();
    private final SimpleDateFormat date=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.ROOT);
    private Consumer<List<String>> listener;
    public SafeLog(Context c){File dir=new File(c.getFilesDir(),"diagnostics");if(!dir.isDirectory())dir.mkdirs();file=new File(dir,"connection.log");}
    public synchronized void setListener(Consumer<List<String>> cb){listener=cb;publish();}
    private static String sanitize(String s){if(s==null)return "";s=s.replace('\r',' ').replace('\n',' ');s=s.replaceAll("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}","<device>");s=s.replaceAll("(?i)(pin|secret|auth[_-]?key|huawei[_-]?id|authToken|encAuthToken)\\s*[:=]\\s*[^ ,;]+","$1=<redacted>");s=s.replaceAll("(?<![0-9a-zA-Z])[0-9]{12,20}(?![0-9a-zA-Z])","<long-number>");return s.length()>1200?s.substring(0,1200)+" [truncated]":s;}
    public synchronized void record(String event,String metadata){String line=date.format(new Date())+" "+sanitize(event)+" "+sanitize(metadata);rows.addLast(line);while(rows.size()>MAX_ROWS)rows.removeFirst();
        try{byte[] bytes=(line+"\n").getBytes(StandardCharsets.UTF_8);if(file.length()+bytes.length>MAX_FILE){StringBuilder b=new StringBuilder();for(String r:rows)b.append(r).append('\n');byte[] kept=b.toString().getBytes(StandardCharsets.UTF_8);try(OutputStream out=new FileOutputStream(file,false)){out.write(kept,Math.max(0,kept.length-MAX_FILE/2),Math.min(kept.length,MAX_FILE/2));}}try(OutputStream out=new FileOutputStream(file,true)){out.write(bytes);}}catch(IOException ignored){}
        publish();
    }
    public void packet(boolean incoming,int service,int command,int bytes,List<TlvView.Part> fields,boolean encrypted){record(incoming?"RX":"TX",String.format(Locale.ROOT,"service=%02X command=%02X bytes=%d encrypted=%s tags=%s",service,command,bytes,encrypted,fields==null?"raw":TlvView.tags(fields)));}
    private void publish(){if(listener!=null)listener.accept(Collections.unmodifiableList(new ArrayList<>(rows)));}
    public synchronized String snapshot(){StringBuilder b=new StringBuilder("腕镜诊断日志\n日志包含通信元数据，认证正文、账户 ID、配对码与会话密钥已排除。\n本地查询序号用于关联操作，不代表手表报文中的随机数。\n\n");for(String r:rows)b.append(r).append('\n');return b.toString();}
    public synchronized void clear(){rows.clear();try(OutputStream out=new FileOutputStream(file,false)){out.write(new byte[0]);}catch(IOException ignored){}publish();}
}
