package io.github.escosis.hwtfaceswap;

import android.content.Context;
import android.os.SystemClock;
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalController;
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalJournal;
import io.github.escosis.hwtfaceswap.platform.AndroidSystemUnpair;
import io.github.escosis.hwtfaceswap.protocol.*;
import io.github.escosis.hwtfaceswap.store.*;
import io.github.escosis.hwtfaceswap.transport.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内运行状态，由界面与设备连接前台服务共享。 */
public final class AppRuntime implements WatchConnection.Listener,WatchfaceManager.Listener {
    private static volatile AppRuntime instance;
    public static AppRuntime get(Context c){if(instance==null)synchronized(AppRuntime.class){if(instance==null)instance=new AppRuntime(c.getApplicationContext());}return instance;}
    public interface Observer {void changed(Snapshot state);void rebindRequested(String message);void deletionPrepared(TlvView.Face face,long epoch);}
    public static final class Snapshot {
        public final String code,detail,deviceAddress,serialNumber,firmware,deviceModel,kind,stage,operationDetail,message,pinMessage;
        public final boolean ready,connecting,needsPin,listReady,listRefreshing,active,failed;
        public final int rebind;public final long epoch,generation,operationRevision,replacementDialogRevision;
        public final WatchfaceManager.ReplacementIdentity replacementIdentity,replacementDialogIdentity;
        public final ReplacementDialogInfo replacementDialog;
        public final Integer width,height,progress,batteryPercent;public final Long capability,freeMemoryBytes;
        public final List<TlvView.Face> faces;public final Map<String,String> names;
        Snapshot(AppRuntime r){
            code=r.code;detail=r.detail;deviceAddress=r.deviceAddress;serialNumber=r.serialNumber;firmware=r.firmware;deviceModel=r.deviceModel;kind=r.kind;stage=r.stage;
            operationDetail=r.operationDetail;message=r.message;pinMessage=r.pinMessage;
            ready=r.ready;connecting=r.connecting;needsPin=r.needsPin;listReady=r.listReady;
            listRefreshing=r.listRefreshing;active=r.active;failed=r.failed;rebind=r.rebind;
            epoch=r.epoch;generation=r.generation;operationRevision=r.operationRevision;replacementDialogRevision=r.replacementDialogRevision;
            replacementIdentity=r.replacementIdentity;replacementDialogIdentity=r.replacementRequest==null?null:r.replacementRequest.identity;replacementDialog=r.replacementDialog;
            width=r.width;height=r.height;progress=r.progress;
            capability=r.capability;batteryPercent=r.batteryPercent;freeMemoryBytes=r.freeMemoryBytes;faces=Collections.unmodifiableList(new ArrayList<>(r.visible));
            names=Collections.unmodifiableMap(new HashMap<>(r.names));
        }
    }
    public final SecureStore secure;public final SafeLog logs;
    public final WatchConnection connection;public final WatchfaceManager watchfaces;
    public final DeviceRemovalController unpair;
    private final CopyOnWriteArrayList<Observer> observers=new CopyOnWriteArrayList<>();
    private String code="IDLE",detail="尚未连接",deviceAddress="",serialNumber="未知",firmware="未知",kind="",stage="",operationDetail="",message="",pinMessage="";
    private String deviceModel;
    private boolean ready,connecting,needsPin,listReady,listRefreshing,active,failed;
    private int rebind;private long epoch,generation,operationRevision,replacementDialogRevision;private Integer width,height,progress,batteryPercent;private Long capability,freeMemoryBytes;
    private WatchfaceManager.ReplacementIdentity replacementIdentity;
    // 进程内会话只保存标题/文件名与不可变本地身份；不保存 URI、账户、密钥或可恢复页面数据。
    private WatchfaceManager.ReplacementRequest replacementRequest;
    private ReplacementDialogInfo replacementDialog;
    private List<TlvView.Face> visible=Collections.emptyList();private Map<String,String> names=Collections.emptyMap();
    private AppRuntime(Context c){this(c,new SecureStore(c),new AndroidSystemUnpair(c));}
    /** 包内装配边界供真实运行时代码的普通 JVM/Android 边界测试；应用始终使用 get。 */
    AppRuntime(Context c,SecureStore secure,DeviceRemovalController.Platform platform){
        this.secure=secure;logs=new SafeLog(c);connection=new WatchConnection(c,secure,logs);watchfaces=new WatchfaceManager(connection,logs);connection.setListener(this);watchfaces.setListener(this);
        SavedWatchStore history=new SavedWatchStore(c);
        connection.setKnownDeviceModelGuard(address->{
            try{return history.devices().stream().anyMatch(watch->watch.getAddress().equalsIgnoreCase(address)&&watch.getModel()!=null);}
            catch(RuntimeException ignored){return false;} // 本地缓存不可读不能推进或中断认证。
        });
        unpair=new DeviceRemovalController(platform,new DeviceRemovalController.Queue(){
            public void post(Runnable task){connection.post(task);}public void delay(Runnable task,long ms){connection.delay(task,ms);}public long now(){return SystemClock.elapsedRealtime();}
        },new DeviceRemovalController.Transport(){
            public boolean busy(){Snapshot s=snapshot();return watchfaces.busy()||s.active||(s.replacementDialog!=null&&s.replacementDialog.getOperation().getActive());}
            public void stopTarget(String address){connection.stopForUnpair(address);}
        },new DeviceRemovalController.Records(){
            public List<DeviceRemovalController.Record> pending()throws Exception{List<DeviceRemovalController.Record> result=new ArrayList<>();for(DeviceRemovalJournal.Pending p:secure.removals().pending())result.add(removalRecord(p));return result;}
            public DeviceRemovalController.Record begin(String address,long token)throws Exception{return removalRecord(secure.removals().begin(address,token));}
            public DeviceRemovalController.Record markAttempted(String address,long token)throws Exception{return removalRecord(secure.removals().markAttempted(address,token));}
            public DeviceRemovalController.Record markCleanupStarted(String address,long token)throws Exception{return removalRecord(secure.removals().markCleanupStarted(address,token));}
            public void clearCredentials(String address)throws Exception{secure.clearDevice(address);}
            public void removeHistory(String address)throws Exception{history.removeDevice(address);}
            public void finish(String address,long token)throws Exception{secure.removals().finish(address,token);}
        });
        connection.setPendingRemovalGuard(address->unpair.blocksAddress(address));
        unpair.addListener(s->{if(s.message!=null&&!s.message.isEmpty())logs.record("SYSTEM_UNPAIR",s.message);});
    }
    private static DeviceRemovalController.Record removalRecord(DeviceRemovalJournal.Pending p){return new DeviceRemovalController.Record(p.address,p.token,p.generation,p.attempted,p.cleanupStarted);}
    private boolean removalBlocksCurrent(){String address=connection.address();return secure.removals().blocked(address)||(unpair!=null&&unpair.blocksAddress(address));}
    public synchronized Snapshot snapshot(){return new Snapshot(this);}
    public void addObserver(Observer o){observers.addIfAbsent(o);o.changed(snapshot());}
    public void removeObserver(Observer o){observers.remove(o);}
    private void publish(){Snapshot s=snapshot();for(Observer o:observers)o.changed(s);ConnectionService.runtimeState(s.connecting||s.ready,s.active||(s.replacementDialog!=null&&s.replacementDialog.getOperation().getActive()),s.code);}
    /** 确认立即创建 WAIT_OPERATION，旧 LISTED 摘要不能被借给尚未进入 actor 的新请求。 */
    public synchronized boolean openReplacementDialog(WatchfaceManager.ReplacementRequest request,String title,String fileName){
        if(request==null||(replacementDialog!=null&&replacementDialog.getOperation().getActive()))return false;
        replacementRequest=request;WatchfaceManager.ReplacementIdentity identity=request.identity;
        replacementDialog=new ReplacementDialogInfo(identity.token,identity.epoch,identity.id,identity.version,title,fileName,new OperationInfo("replacement","WAIT_OPERATION","正在开始替换",null,true,false),false);
        replacementDialogRevision++;publish();return true;
    }
    private boolean ownsReplacementDialog(WatchfaceManager.ReplacementIdentity identity){
        return identity!=null&&replacementRequest!=null&&replacementRequest.identity==identity&&replacementDialog!=null&&replacementDialog.getToken()==identity.token&&replacementDialog.getConnectionEpoch()==identity.epoch&&Objects.equals(replacementDialog.getTargetId(),identity.id)&&Objects.equals(replacementDialog.getTargetVersion(),identity.version);
    }
    private void updateReplacementDialog(WatchfaceManager.ReplacementIdentity identity,String stage,String detail,Integer progress,boolean active,boolean failed){
        if(!ownsReplacementDialog(identity))return;
        ReplacementDialogInfo old=replacementDialog;
        replacementDialog=new ReplacementDialogInfo(old.getToken(),old.getConnectionEpoch(),old.getTargetId(),old.getTargetVersion(),old.getTitle(),old.getFileName(),new OperationInfo("replacement",stage,detail,progress,active,failed),old.getInterruptRequested());
        replacementDialogRevision++;
    }
    /** 仅界面廉价前检拒绝，不改变原生操作、错误或隔离。 */
    public synchronized void rejectReplacementDialog(WatchfaceManager.ReplacementIdentity identity,String code){
        if(!ownsReplacementDialog(identity)||!replacementDialog.getOperation().getActive()||watchfaces.replacementIdentity()==identity)return;
        updateReplacementDialog(identity,"PRECHECK_FAILED",code,null,false,true);publish();
    }
    /** main 已核对可见令牌；此处再核对同一请求对象与连接代次，actor 最后核对实际 Replace。 */
    public synchronized boolean interruptReplacement(WatchfaceManager.ReplacementIdentity identity){
        if(!ownsReplacementDialog(identity)||identity.epoch!=connection.epoch()||epoch!=identity.epoch||!replacementDialog.getOperation().getActive()||replacementDialog.getInterruptRequested())return false;
        // 即时门先置位，排队 begin 尚未执行时能做到零 TX；终态只能由 actor 真正停止后发布。
        WatchfaceManager.ReplacementRequest own=replacementRequest;
        watchfaces.cancelReplacement(own);
        // 即使边界同步重入发布了其他任务，也不能给新所有者标记中断请求。
        if(!ownsReplacementDialog(identity))return true;
        ReplacementDialogInfo old=replacementDialog;
        replacementDialog=new ReplacementDialogInfo(old.getToken(),old.getConnectionEpoch(),old.getTargetId(),old.getTargetVersion(),old.getTitle(),old.getFileName(),old.getOperation(),true);
        replacementDialogRevision++;publish();return true;
    }
    /** 关闭只是 UI 会话退休；终态晚到错误仍按原协议处理，不清错误或隔离。 */
    public synchronized boolean dismissReplacementResult(WatchfaceManager.ReplacementIdentity identity){
        if(!ownsReplacementDialog(identity)||replacementDialog.getOperation().getActive())return false;
        replacementDialog=null;replacementRequest=null;replacementDialogRevision++;publish();return true;
    }
    public synchronized void onConnection(String code,String detail,boolean ready,boolean connecting,int rebindCount,long epoch){
        if(epoch!=connection.epoch()||((ready||connecting)&&removalBlocksCurrent()))return;
        if(this.epoch!=epoch){
            // 设备连接已变化，上一会话的参数与表盘不能用于新设备。
            deviceAddress="";serialNumber="未知";firmware="未知";deviceModel=null;batteryPercent=null;freeMemoryBytes=null;
            width=height=null;capability=null;visible=Collections.emptyList();
            names=Collections.emptyMap();listReady=listRefreshing=false;message="";
        }
        this.code=code;this.detail=detail;this.ready=ready;this.connecting=!ready&&connecting;
        deviceAddress=ready?connection.address():"";
        this.rebind=rebindCount;this.epoch=epoch;
        if(ready)message="";
        publish();
        // 跳过协议声明，界面关闭后仍由运行时完成同一分支。
        if("AGREEMENT_CONFIRM".equals(code))connection.respondAgreements(epoch,false);
    }
    public synchronized void onPin(boolean needed,String msg){if(needed&&removalBlocksCurrent())return;needsPin=needed;pinMessage=msg;publish();}
    public void onRebind(String message){if(removalBlocksCurrent())return;for(Observer o:observers)o.rebindRequested(message);}
    public synchronized void onDeviceInfo(long epoch,String version){
        if(this.epoch!=epoch||epoch!=connection.epoch()||removalBlocksCurrent()||!ready||!connection.ready()||version==null||version.isEmpty())return;
        firmware=version;publish();
    }
    public synchronized void onDeviceModel(long epoch,String model){
        if(this.epoch!=epoch||epoch!=connection.epoch()||removalBlocksCurrent()||!ready||!connection.ready()||
                !deviceAddress.equalsIgnoreCase(connection.address())||deviceModel!=null)return;
        try{String known=DeviceInfo.modelText(model);if(known==null)return;deviceModel=known;}
        catch(java.io.IOException ignored){return;}
        publish();
    }
    public synchronized void onDeviceStats(long epoch,String serial,Integer battery,Long freeBytes){
        if(this.epoch!=epoch||epoch!=connection.epoch()||removalBlocksCurrent()||!ready||!connection.ready())return;
        if(serial!=null&&!serial.isEmpty())serialNumber=serial;
        if(battery!=null&&battery>=0&&battery<=100)batteryPercent=battery;
        if(freeBytes!=null&&freeBytes>0)freeMemoryBytes=freeBytes;
        publish();
    }
    public synchronized void onReady(long epoch,LinkCapabilities caps){
        if(epoch!=connection.epoch()||this.epoch!=epoch||removalBlocksCurrent())return;
        kind="";stage="";operationDetail="";message="";active=false;failed=false;progress=null;replacementIdentity=null;operationRevision++;
        publish();watchfaces.authenticated(epoch);
    }
    public void onPacket(long epoch,int service,int command,byte[] body,boolean encrypted){if(removalBlocksCurrent())return;watchfaces.packet(epoch,service,command,body,encrypted);}
    public void onClosed(long closedEpoch,String reason){watchfaces.closed(closedEpoch,reason);}
    public synchronized void onFaces(List<TlvView.Face> faces,Map<String,String> names,boolean fresh,long epoch,long gen){
        if(epoch!=connection.epoch())return;
        visible=faces;this.names=names;listReady=fresh;generation=gen;publish();
    }
    public synchronized void onRefreshing(boolean refreshing,long epoch){
        if(epoch!=connection.epoch())return;
        listRefreshing=refreshing;
        if(!refreshing&&listReady)message="";
        publish();
    }
    public synchronized void onParameters(Integer width,Integer height,Long capability){this.width=width;this.height=height;this.capability=capability;publish();}
    public synchronized void onOperation(String kind,String stage,String detail,Integer progress,boolean active,boolean failed){operationRevision++;replacementIdentity=null;this.kind=kind;this.stage=stage;operationDetail=detail;this.progress=progress;this.active=active;this.failed=failed;publish();}
    @Override public synchronized void onReplacementOperation(WatchfaceManager.ReplacementIdentity identity,String stage,String detail,Integer progress,boolean active,boolean failed){
        if(identity==null||watchfaces.replacementIdentity()!=identity)return;
        // 旧回调只能更新仍由 manager 发布的同一摘要；新会话的标题、文件名不从当前选择重建。
        if(!ownsReplacementDialog(identity)&&active&&identity.epoch==connection.epoch()){
            WatchfaceManager.ReplacementRequest current=watchfaces.currentReplacementRequest();
            if(current!=null&&current.identity==identity){replacementRequest=current;replacementDialog=new ReplacementDialogInfo(identity.token,identity.epoch,identity.id,identity.version,identity.id,"本地 HWT",new OperationInfo(),false);}
        }
        if(replacementIdentity!=identity)message="";
        operationRevision++;replacementIdentity=identity;kind="replacement";this.stage=stage;operationDetail=detail;this.progress=progress;this.active=active;this.failed=failed;
        updateReplacementDialog(identity,stage,detail,progress,active,failed);publish();
    }
    @Override public synchronized void onReplacementRequestFinished(WatchfaceManager.ReplacementIdentity identity,String stage,String detail,boolean failed){
        if(!ownsReplacementDialog(identity))return;
        updateReplacementDialog(identity,stage,detail,null,false,failed);publish();
    }
    @Override public synchronized void onReplacementError(WatchfaceManager.ReplacementIdentity identity,String error){
        if(identity==null||replacementIdentity!=identity||watchfaces.replacementIdentity()!=identity)return;
        message=error;publish();
    }
    public void onPreparedDeletion(TlvView.Face face,long epoch){for(Observer o:observers)o.deletionPrepared(face,epoch);}
    public synchronized void onError(String msg){message=msg;publish();}
}
