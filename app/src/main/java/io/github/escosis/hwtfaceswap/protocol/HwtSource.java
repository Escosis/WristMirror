package io.github.escosis.hwtfaceswap.protocol;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** 仅处理通过系统文件选择器取得的本地输入；复制或提取原字节，绝不修改 XML、BIN 头或 ZIP 条目。 */
public final class HwtSource {
    public enum Mode { BIN }
    public final File directory, original, resource, bin;
    public final String sourceHash, binHash, resourceHash, xmlVersion, screen, title;
    public final boolean resourceIsZip;
    private HwtSource(File dir,File outer,File resource,File bin,boolean zip,String[] xml) throws IOException {
        this.directory=dir;this.original=outer;this.resource=resource;this.bin=bin;this.resourceIsZip=zip;
        this.sourceHash=Core.hex(Core.digest(outer));this.resourceHash=Core.hex(Core.digest(resource));
        this.binHash=bin==null?null:Core.hex(Core.digest(bin));this.xmlVersion=xml[0];this.screen=xml[1];this.title=xml[2];
    }
    public static HwtSource load(InputStream input,File privateFiles) throws IOException {
        File parent=new File(privateFiles,"local-hwt");
        if(!parent.isDirectory() && !parent.mkdirs()) throw new Core.Fault("PRIVATE_DIRECTORY_UNAVAILABLE");
        File dir=new File(parent,"source-"+UUID.randomUUID().toString());
        if(!dir.mkdir()) throw new Core.Fault("PRIVATE_DIRECTORY_UNAVAILABLE");
        File outer=new File(dir,"source.hwt");
        try {
            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(outer))) { Core.copy(input,out,Core.MAX_SOURCE); }
            return inspect(outer,dir);
        } catch(IOException | RuntimeException e) { try{cleanup(privateFiles,dir);}catch(IOException cleanup){e.addSuppressed(cleanup);}throw e; }
    }
    public static HwtSource inspect(File outer,File dir) throws IOException {
        if(outer.length()<=0 || outer.length()>Core.MAX_SOURCE) throw new Core.Fault("SOURCE_SIZE_LIMIT");
        File resource=new File(dir,"resource.data"), bin=null;
        String[] xml={"unknown","unknown","本地 HWT"};
        try(ZipFile zip=new ZipFile(outer)) {
            Map<String,ZipEntry> entries=index(zip);
            boolean root=entries.containsKey("com.huawei.watchface"), snapshot=entries.containsKey("watchFace/com.huawei.watchface");
            if(root==snapshot) throw new Core.Fault("HWT_RESOURCE_MISSING_OR_AMBIGUOUS");
            String prefix=root?"":"watchFace/";
            ZipEntry description=entries.get(prefix+"description.xml");
            if(description!=null) xml=description(readMember(zip,description,65536));
            extract(zip,entries.get(prefix+"com.huawei.watchface"),resource);
        } catch(ZipException e) { throw new Core.Fault("SOURCE_NOT_READABLE_HWT"); }
        boolean zip=zipMagic(resource);
        if(zip) {
            try(ZipFile inner=new ZipFile(resource)) {
                ZipEntry e=index(inner).get("watchface.bin");
                if(e!=null) { bin=new File(dir,"watchface.bin");extract(inner,e,bin); }
            } catch(ZipException e) { throw new Core.Fault("INNER_RESOURCE_ZIP_INVALID"); }
        } else bin=resource;
        return new HwtSource(dir,outer,resource,bin,zip,xml);
    }
    private static Map<String,ZipEntry> index(ZipFile zip) throws IOException {
        Map<String,ZipEntry> map=new HashMap<String,ZipEntry>();Enumeration<? extends ZipEntry> all=zip.entries();int count=0;
        while(all.hasMoreElements()) { ZipEntry e=all.nextElement();if(++count>12000)throw new Core.Fault("ZIP_ENTRY_LIMIT");
            if(map.put(e.getName(),e)!=null)throw new Core.Fault("ZIP_DUPLICATE_ENTRY"); }
        return map;
    }
    private static byte[] readMember(ZipFile zip,ZipEntry e,int max) throws IOException {
        if(e.isDirectory() || e.getSize()<=0 || e.getSize()>max)throw new Core.Fault("DESCRIPTION_SIZE_LIMIT");
        byte[] b;try(InputStream in=zip.getInputStream(e)){b=Core.read(in,max);}
        CRC32 crc=new CRC32();crc.update(b);if(b.length!=e.getSize() || crc.getValue()!=e.getCrc())throw new Core.Fault("DESCRIPTION_CRC");return b;
    }
    private static void extract(ZipFile zip,ZipEntry e,File output) throws IOException {
        if(e==null || e.isDirectory() || e.getSize()<=0 || e.getSize()>Core.MAX_MEMBER)throw new Core.Fault("RESOURCE_SIZE_LIMIT");
        if(e.getCompressedSize()>0 && e.getSize()>e.getCompressedSize()*500L)throw new Core.Fault("ZIP_EXPANSION_LIMIT");
        CRC32 crc=new CRC32();long size=0;byte[] buf=new byte[65536];int n;
        try(InputStream in=zip.getInputStream(e);OutputStream out=new BufferedOutputStream(new FileOutputStream(output))) {
            while((n=in.read(buf))!=-1) { size+=n;if(size>Core.MAX_MEMBER)throw new Core.Fault("RESOURCE_SIZE_LIMIT");crc.update(buf,0,n);out.write(buf,0,n); }
        }
        if(size!=e.getSize() || crc.getValue()!=e.getCrc())throw new Core.Fault("RESOURCE_CRC");
    }
    private static boolean zipMagic(File file) throws IOException {
        try(InputStream in=new FileInputStream(file)) {
            if(in.read()!=80 || in.read()!=75)return false;
            int a=in.read(),b=in.read();return (a==3&&b==4)||(a==5&&b==6)||(a==7&&b==8);
        }
    }
    private static String[] description(byte[] bytes) throws IOException {
        String text=Core.utf8(bytes),upper=text.toUpperCase(Locale.US);
        if(upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))throw new Core.Fault("XML_DTD_FORBIDDEN");
        try {
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setValidating(false);f.setNamespaceAware(false);f.setExpandEntityReferences(false);
            DocumentBuilder b=f.newDocumentBuilder();b.setEntityResolver(new EntityResolver(){public InputSource resolveEntity(String p,String s){return new InputSource(new StringReader(""));}});
            b.setErrorHandler(new DefaultHandler(){public void warning(SAXParseException e)throws SAXException{throw e;}public void error(SAXParseException e)throws SAXException{throw e;}public void fatalError(SAXParseException e)throws SAXException{throw e;}});
            Element root=b.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if(root==null || !"HwTheme".equals(root.getTagName()))throw new Core.Fault("DESCRIPTION_ROOT_UNSUPPORTED");
            return new String[]{field(root,"version","unknown"),field(root,"screen","unknown"),field(root,"title","本地 HWT")};
        } catch(Core.Fault e){throw e;}catch(Exception e){throw new Core.Fault("DESCRIPTION_XML_INVALID");}
    }
    private static String field(Element root,String name,String fallback) throws IOException {
        NodeList all=root.getChildNodes();String result=null;
        for(int i=0;i<all.getLength();i++){Node n=all.item(i);if(n.getNodeType()!=Node.ELEMENT_NODE || !name.equals(n.getNodeName()))continue;
            if(result!=null)throw new Core.Fault("DESCRIPTION_DUPLICATE_FIELD");
            for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())if(c.getNodeType()==Node.ELEMENT_NODE)throw new Core.Fault("DESCRIPTION_NESTED_FIELD");
            result=n.getTextContent().trim();if(result.length()>160)result=result.substring(0,160);
        }
        return result==null||result.isEmpty()?fallback:Core.clean(result);
    }
    public Snapshot freeze(Mode mode) throws IOException {
        if(mode!=Mode.BIN)throw new Core.Fault("BIN_MODE_REQUIRED");
        File input=bin;
        if(input==null)throw new Core.Fault("ROOT_WATCHFACE_BIN_MISSING");
        File file=new File(directory,"payload-"+UUID.randomUUID().toString()+".bin");
        long count;
        try(InputStream in=new BufferedInputStream(new FileInputStream(input));OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){count=Core.copy(in,out,Core.MAX_MEMBER);}
        String hash=Core.hex(Core.digest(file));String expected=binHash;
        if(count<=0 || count!=input.length() || !hash.equals(expected))throw new Core.Fault("SOURCE_CHANGED_DURING_FREEZE");
        if(!file.setReadOnly())throw new Core.Fault("PRIVATE_PAYLOAD_READONLY_FAILED");
        return new Snapshot(this,file,(int)count,hash,mode);
    }
    public static final class Snapshot {
        public final HwtSource source;public final File file;public final int size;public final String hash;public final Mode mode;
        Snapshot(HwtSource source,File file,int size,String hash,Mode mode){this.source=source;this.file=file;this.size=size;this.hash=hash;this.mode=mode;}
        public void verify() throws IOException {if(file.length()!=size || !Core.hex(Core.digest(file)).equals(hash))throw new Core.Fault("PAYLOAD_SNAPSHOT_CHANGED");}
    }
    /** 只删除经验证、由本应用生成的直接子目录；绝不沿用外部目录路径。 */
    public static void cleanup(File privateFiles,File directory) throws IOException {
        File parent=new File(privateFiles,"local-hwt").getCanonicalFile(),target=directory.getCanonicalFile();
        if(!parent.equals(target.getParentFile()) || !target.getName().matches("source-[0-9a-f-]{36}"))throw new Core.Fault("CLEANUP_PATH_REJECTED");
        File[] children=target.listFiles();if(children!=null)for(File file:children){
            if(!target.equals(file.getCanonicalFile().getParentFile()) || file.isDirectory())throw new Core.Fault("CLEANUP_PATH_REJECTED");
            if(!file.delete())throw new Core.Fault("CLEANUP_FAILED");
        }
        if(target.exists() && !target.delete())throw new Core.Fault("CLEANUP_FAILED");
    }
}
