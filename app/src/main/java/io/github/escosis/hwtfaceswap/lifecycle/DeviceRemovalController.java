package io.github.escosis.hwtfaceswap.lifecycle;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 无 Android/手表报文依赖的解除配对生命周期。运行时拥有它，界面模型关闭不取消系统请求。
 * 只接受已消费的应用确认令牌；系统返回 true、广播 extra、设置页关闭都不是解除配对证据。
 * 平台边界每次必须真实读取启用且有权限的适配器上、匹配 MAC 的当前 bond state。
 */
public final class DeviceRemovalController {
    public enum Bond { NONE, BONDED, BONDING, ADAPTER_OFF, PERMISSION_REQUIRED, UNKNOWN }
    public enum Request { ACCEPTED, REJECTED, UNSUPPORTED, ADAPTER_OFF, PERMISSION_REQUIRED, UNKNOWN }
    public interface Platform {
        Bond readBond(String address);
        Request requestRemoval(String address);
        /** 事件只触发重新读取，不提供成功证据；null 表示适配器状态变化。 */
        void observe(Event listener);
        boolean openSettings();
    }
    public interface Event { void changed(String address); }
    public interface Queue { void post(Runnable task); void delay(Runnable task,long millis); long now(); }
    public interface Transport {
        boolean busy();
        /** 在同一协议串行队列内完成；不得断开不同 MAC。 */
        void stopTarget(String address);
    }
    public static final class Record {
        public final String address;public final long token,generation;public final boolean attempted,cleanupStarted;
        public Record(String address,long token,long generation,boolean attempted,boolean cleanupStarted) {
            this.address=address;this.token=token;this.generation=generation;this.attempted=attempted;this.cleanupStarted=cleanupStarted;
        }
    }
    public interface Records {
        List<Record> pending()throws Exception;
        Record begin(String address,long token)throws Exception;
        Record markAttempted(String address,long token)throws Exception;
        Record markCleanupStarted(String address,long token)throws Exception;
        void clearCredentials(String address)throws Exception;
        void removeHistory(String address)throws Exception;
        void finish(String address,long token)throws Exception;
        void abandon(String address,long token)throws Exception;
    }
    public static final class Snapshot {
        public final boolean pending,working,needsSettings,success;
        public final String address,message;public final long token,revision;
        private Snapshot(boolean pending,boolean working,boolean settings,boolean success,String address,String message,long token,long revision) {
            this.pending=pending;this.working=working;needsSettings=settings;this.success=success;
            this.address=address;this.message=message;this.token=token;this.revision=revision;
        }
    }
    public interface Listener { void changed(Snapshot snapshot); }
    private static final long TIMEOUT_MS=20000L, POLL_MS=500L;
    private final Platform platform;private final Queue queue;private final Transport transport;private final Records records;
    private final CopyOnWriteArrayList<Listener> listeners=new CopyOnWriteArrayList<>();
    private volatile Snapshot state=new Snapshot(false,false,false,false,null,"",0,0);
    // 以下字段仅在串行队列使用；reservation/state 的消费以 synchronized 保证一次性。
    private volatile Record record;private boolean markerStarted;private long watchSerial;private Request requestResult;

    public DeviceRemovalController(Platform platform,Queue queue,Transport transport,Records records) {
        this.platform=Objects.requireNonNull(platform);this.queue=Objects.requireNonNull(queue);
        this.transport=Objects.requireNonNull(transport);this.records=Objects.requireNonNull(records);
        restore();
    }
    public Snapshot snapshot(){return state;}
    public boolean blocksAddress(String address) {
        Snapshot s=state;return s.pending&&s.address!=null&&s.address.equalsIgnoreCase(address==null?"":address);
    }
    public void addListener(Listener listener){listeners.addIfAbsent(listener);listener.changed(state);}
    public void removeListener(Listener listener){listeners.remove(listener);}
    private synchronized void publish(boolean pending,boolean working,boolean settings,boolean success,String address,String message,long token) {
        Snapshot next=new Snapshot(pending,working,settings,success,address,message,token,state.revision+1);
        state=next;for(Listener listener:listeners)listener.changed(next);
    }
    private void restore() {
        try {
            List<Record> saved=records.pending();if(saved.isEmpty())return;
            record=saved.get(0);markerStarted=true;
            publish(true,true,false,false,record.address,"正在重新核实待处理设备的系统配对状态",record.token);
            String address=record.address;long token=record.token;
            queue.post(()->start(address,token,false));
        } catch(Exception e) {
            publish(true,false,false,false,null,"无法读取待处理解除配对标记；未执行系统请求或资料清理，请重新打开应用后重试",0);
        }
    }
    /** 返回 false 表示未接受新的任务。确认本身由应用 UI 的一次 token 消费完成。 */
    public synchronized boolean request(String address,long token) {
        String mac=canonical(address);
        if(mac==null||state.pending||transport.busy())return false;
        publish(true,true,false,false,mac,"正在保存待处理标记并停止该设备的本应用连接",token);
        queue.post(()->start(mac,token,true));return true;
    }
    public void retry(){Snapshot s=state;if(s.pending&&s.address!=null)queue.post(()->start(s.address,s.token,true));}
    /** 界面探测：只有尚未开始资料清理时才允许撤回，避免把已部分清理的设备放回可用状态。 */
    public boolean cancelable(){Record current=record;return state.pending&&current!=null&&!current.cleanupStarted;}
    /** 撤回待处理解除配对：一次都没有清除凭据/历史，因此设备可以直接恢复使用。 */
    public void cancel(){
        Snapshot s=state;if(!s.pending||s.address==null)return;
        queue.post(()->{
            if(!owned(s.address,s.token))return;
            Record current=record;
            if(current==null||current.cleanupStarted){pending(s.address,s.token,false,"清理已经开始，无法撤回解除配对；请完成系统解除配对后重新检查");return;}
            try{records.abandon(s.address,s.token);}
            catch(Exception e){pending(s.address,s.token,false,"撤回解除配对失败，请重试（"+code(e)+"）");return;}
            record=null;markerStarted=false;requestResult=null;++watchSerial;
            publish(false,false,false,false,s.address,"已取消解除配对；系统配对与本应用资料均保持原状，可以重新连接",s.token);
        });
    }
    /** 设置页返回/活动恢复只核实，不再次提交隐藏方法。 */
    public void verify(){Snapshot s=state;if(s.pending&&s.address!=null)queue.post(()->start(s.address,s.token,false));}
    public void openSettings() {
        Snapshot s=state;if(!s.pending||s.address==null)return;
        queue.post(()->{
            if(!owned(s.address,s.token))return;
            if(!platform.openSettings())pending(s.address,s.token,true,"无法打开系统蓝牙设置；请手动进入系统设置解除此 MAC 的配对，再点击重新检查");
        });
    }
    private boolean owned(String address,long token){Snapshot s=state;return s.pending&&s.token==token&&address.equals(s.address);}
    private void start(String address,long token,boolean mayAttempt) {
        if(!owned(address,token))return;
        ++watchSerial;
        if(transport.busy()) {
            if(!markerStarted)publish(false,false,false,false,address,"当前表盘操作尚未结束，未提交解除配对请求",token);
            else pending(address,token,false,"请先结束当前表盘操作；待处理解除配对资料保持不变");
            return;
        }
        publish(true,true,false,false,address,"正在核实该设备的系统配对状态",token);
        try {
            markerStarted=true; // commit(false) 也可能已改变 Journal 内存；不能把 record==null 当作“没有标记”。
            record=records.begin(address,token); // 返回之前须真实 commit 成功；失败不允许调用系统方法。
            transport.stopTarget(address);      // 同队列屏障，之后的旧帧/配对回调必须失效。
        } catch(Exception e) {
            pending(address,token,false,"待处理标记或连接停止未可靠完成；未继续提交系统请求，请重试（"+code(e)+"）");return;
        }
        try { platform.observe(this::event); }
        catch(RuntimeException e) { /* 监听不可用时仍可真实读取/手动重试，不能假设成功。 */ }
        Bond bond=read(address);
        if(bond==Bond.NONE){cleanup(address,token);return;}
        if(unavailable(address,token,bond))return;
        if(!mayAttempt||record.attempted) {
            pending(address,token,true,record.cleanupStarted?
                "上次清理尚未可靠完成，系统当前仍显示配对；请核实此 MAC 的系统配对后重试":
                "系统仍显示此 MAC 已配对或正在配对；请在系统蓝牙设置解除配对，再点击重新检查");
            return;
        }
        try {
            // 在调用之前持久记录“已尝试”。崩溃窗口可能少执行一次，但绝不会自动重发一次。
            record=records.markAttempted(address,token);
        } catch(Exception e) {
            pending(address,token,true,"系统请求尚未提交，尝试标记无法可靠保存；请在系统蓝牙设置处理后重新检查（"+code(e)+"）");return;
        }
        try { requestResult=platform.requestRemoval(address); }
        catch(RuntimeException e){requestResult=Request.UNKNOWN;}
        // 无论返回值如何都重新读真实状态；只有 NONE 才允许本应用资料清理。
        bond=read(address);
        if(bond==Bond.NONE){cleanup(address,token);return;}
        if(unavailable(address,token,bond))return;
        if(requestResult==Request.ACCEPTED) {
            pending(address,token,false,"已向系统提交解除配对请求，尚未证实成功；正在等待真实配对状态");
            long serial=++watchSerial,started=queue.now();
            queue.delay(()->poll(address,token,serial,started),POLL_MS);
        } else {
            String detail=requestResult==Request.UNSUPPORTED?"系统不允许本应用直接解除配对":
                requestResult==Request.REJECTED?"系统拒绝直接解除配对请求":"无法确定系统是否接受解除配对请求";
            pending(address,token,true,detail+"；资料尚未清除。请在系统蓝牙设置解除此 MAC 的配对后重新检查");
        }
    }
    private void event(String address) {
        Snapshot s=state;
        if(!s.pending||s.address==null||(address!=null&&!s.address.equalsIgnoreCase(address)))return;
        queue.post(()->{
            if(!owned(s.address,s.token))return;
            Bond bond=read(s.address);
            if(bond==Bond.NONE)cleanup(s.address,s.token);
            else unavailable(s.address,s.token,bond);
        });
    }
    private void poll(String address,long token,long serial,long started) {
        if(!owned(address,token)||watchSerial!=serial)return;
        Bond bond=read(address);
        if(bond==Bond.NONE){cleanup(address,token);return;}
        if(unavailable(address,token,bond)){++watchSerial;return;}
        if(queue.now()-started>=TIMEOUT_MS) {
            ++watchSerial;pending(address,token,true,"等待系统解除配对超时，当前仍未证实 BOND_NONE；资料尚未清除，请在系统蓝牙设置处理后重新检查");return;
        }
        queue.delay(()->poll(address,token,serial,started),POLL_MS);
    }
    private Bond read(String address) {try {Bond value=platform.readBond(address);return value==null?Bond.UNKNOWN:value;}catch(RuntimeException e){return Bond.UNKNOWN;}}
    private boolean unavailable(String address,long token,Bond bond) {
        if(bond==Bond.ADAPTER_OFF)pending(address,token,true,"系统蓝牙未开启，无法确认解除配对；资料尚未清除，请开启蓝牙后重新检查");
        else if(bond==Bond.PERMISSION_REQUIRED)pending(address,token,true,"附近设备连接权限不可用，无法确认解除配对；资料尚未清除，请在系统应用权限中为腕镜授予附近设备权限后重新检查");
        else if(bond==Bond.UNKNOWN)pending(address,token,true,"无法可靠读取此 MAC 的当前系统配对状态；资料尚未清除，请核实系统蓝牙设置后重新检查");
        else return false;
        return true;
    }
    private void cleanup(String address,long token) {
        if(!owned(address,token))return;
        ++watchSerial;publish(true,true,false,false,address,"系统读到未配对，正在持久清理此 MAC 的本应用资料",token);
        try {
            record=records.markCleanupStarted(address,token);
            // 提交清理标记期间适配器或权限也可能变化，紧邻清理再读一次真实状态。
            Bond bond=read(address);
            if(bond!=Bond.NONE) {
                if(!unavailable(address,token,bond))pending(address,token,true,"系统配对状态已变化，未继续清理；请核实此 MAC 后重试");
                return;
            }
            transport.stopTarget(address);
            records.clearCredentials(address);
            records.removeHistory(address);
            records.finish(address,token);
            record=null;markerStarted=false;requestResult=null;
            publish(false,false,false,true,address,"系统已确认解除配对；此设备在腕镜的账号、认证、本机身份和连接记录已清除",token);
        } catch(Exception e) {
            pending(address,token,false,"系统曾读到未配对，但本应用清理尚未可靠保存，可能已部分清理；此 MAC 仍被阻断，请重新检查并重试（"+code(e)+"）");
        }
    }
    private void pending(String address,long token,boolean settings,String message){if(owned(address,token))publish(true,false,settings,false,address,message,token);}
    private static String code(Exception e){String value=e.getMessage();return value!=null&&value.matches("[A-Z0-9_]{1,80}")?value:e.getClass().getSimpleName();}
    private static String canonical(String address){return address!=null&&address.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")?address.toUpperCase(Locale.ROOT):null;}
}
