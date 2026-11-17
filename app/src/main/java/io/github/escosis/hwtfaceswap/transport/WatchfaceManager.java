package io.github.escosis.hwtfaceswap.transport;

import android.os.SystemClock;
import io.github.escosis.hwtfaceswap.protocol.*;
import io.github.escosis.hwtfaceswap.store.SafeLog;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 在本应用串行连接执行器中实现既有表盘内容替换流程。 */
public final class WatchfaceManager {
    public interface Listener {
        void onFaces(List<TlvView.Face> faces,Map<String,String> names,boolean fresh,long epoch,long generation);
        void onParameters(Integer width,Integer height,Long capability);
        void onOperation(String kind,String stage,String detail,Integer progress,boolean active,boolean failed);
        void onPreparedDeletion(TlvView.Face face,long epoch);
        void onError(String error);
        default void onRefreshing(boolean refreshing,long epoch) {}
        /** 身份与操作快照在同一回调发布；旧监听者仍走既有操作边界。 */
        default void onReplacementOperation(ReplacementIdentity identity,String stage,String detail,Integer progress,boolean active,boolean failed){onOperation("replacement",stage,detail,progress,active,failed);}
        /** 未取得原生操作所有权的排队请求，只结束自己的界面会话。 */
        default void onReplacementRequestFinished(ReplacementIdentity identity,String stage,String detail,boolean failed) {}
        default void onReplacementError(ReplacementIdentity identity,String error){onError(error);}
    }
    /** 本地身份不是线上的 nonce；一项任务（包括排队/前检结果）终身使用同一对象。 */
    public static final class ReplacementIdentity {
        public final long token,epoch;
        public final String id,version;
        private ReplacementIdentity(String id,String version,long epoch){this.token=replacementTokens.incrementAndGet();this.id=id;this.version=version;this.epoch=epoch;}
    }
    /** 确认后、进入 actor 前的取消门；不能借此访问另一项原生操作。 */
    public static final class ReplacementRequest {
        public final ReplacementIdentity identity;
        private final AtomicBoolean submitted=new AtomicBoolean(),interruptRequested=new AtomicBoolean();
        private final boolean legacy;
        private final Object previousOwner;
        private ReplacementRequest(String id,String version,long epoch,boolean legacy,Object previousOwner){identity=new ReplacementIdentity(id,version,epoch);this.legacy=legacy;this.previousOwner=previousOwner;}
    }
    private static final AtomicLong replacementTokens=new AtomicLong(System.nanoTime());
    private volatile Object summaryOwner;
    private volatile ReplacementRequest shownReplacementRequest;
    /** 仅返回已经一致发布的身份，界面不能读 actor 的可变 Replace。 */
    public ReplacementIdentity replacementIdentity(){ReplacementRequest r=shownReplacementRequest;return r==null?null:r.identity;}
    public ReplacementRequest currentReplacementRequest(){return shownReplacementRequest;}
    public ReplacementRequest newReplacementRequest(TlvView.Face face,String version,long epoch){return new ReplacementRequest(face==null?"":face.id,version,epoch,false,summaryOwner);}
    // 替换列表有效期为五分钟；删除的最终确认使用独立三十秒有效期。
    private static final long REPLACEMENT_LIST_AGE=300000L,DELETE_LIST_AGE=30000L,POST_TIMEOUT=90000L,TOTAL_TIMEOUT=900000L,DELETE_OPERATION_TIMEOUT=15000L;
    private static final int POST_QUERY_BUDGET=40;
    private final WatchConnection connection;
    private final SafeLog log;
    private Listener listener;
    private volatile boolean busy;
    private volatile List<TlvView.Face> latest=Collections.emptyList();
    private final Map<String,String> names=new HashMap<>();
    private long listAt,listEpoch,serial;
    private long parametersRequestedEpoch = -1;
    private Query query;
    private Op active,last;
    private final ArrayDeque<Op> history=new ArrayDeque<>();
    private PreDelete preDelete;
    private boolean precheckFailure;
    private volatile long reconnectEpoch=-1;
    public boolean reconnectRequired(long expectedEpoch){return reconnectEpoch==expectedEpoch;}
    private static final class ListObservation {
        final long at,epoch; final boolean usable;
        ListObservation(long at,long epoch,boolean usable){this.at=at;this.epoch=epoch;this.usable=usable;}
    }
    private volatile ListObservation observation=new ListObservation(0,0,false);
    public WatchfaceManager(WatchConnection connection,SafeLog log){this.connection=connection;this.log=log;}
    public void setListener(Listener listener){this.listener=listener;}
    public boolean busy(){return busy||preDelete!=null;}
    public List<TlvView.Face> faces(){return latest;}
    /** 界面读取已发布的不可变观察值，不直接访问串行执行器的可变查询状态。 */
    public boolean replacementListFresh(long expectedEpoch){
        ListObservation o=observation;long age=SystemClock.elapsedRealtime()-o.at;
        return o.usable&&o.at!=0&&o.epoch==expectedEpoch&&connection.epoch()==expectedEpoch&&connection.ready()&&age>=0&&age<=REPLACEMENT_LIST_AGE;
    }
    private abstract static class Op {
        final String id,version; final long epoch,started=SystemClock.elapsedRealtime();long finished;int listQueries;
        Op(String i,String v,long e){id=i;version=v;epoch=e;}
        boolean matches(String i,String v){return id.equals(i)&&version.equals(v);}
    }
    private static final class Replace extends Op {
        final ReplacementState state;final HwtSource.Snapshot source;final ReplacementRequest request;FileTransfer file;long postAt;boolean fileResultSeen;
        Replace(TlvView.Face face,String version,HwtSource.Snapshot source,long epoch,ReplacementRequest request)throws IOException{
            super(face.id,version,epoch);state=new ReplacementState(face.id,face.version,version);this.source=source;this.request=request;
        }
    }
    private static final class Delete extends Op {
        final DeletionState state;final Set<String> otherIds;
        Delete(TlvView.Face f,List<TlvView.Face> all,long epoch)throws IOException{
            super(f.id,f.version,epoch);state=new DeletionState(f.id,f.version,FacePolicy.current(f));
            Set<String> ids=new HashSet<>();for(TlvView.Face x:all)if(!f.id.equals(x.id))ids.add(x.id);otherIds=ids;
        }
    }
    private static final class Query {
        final long serial,epoch;final Op op;final int generation;
        Query(long serial,long epoch,Op op){this.serial=serial;this.epoch=epoch;this.op=op;generation=op instanceof Replace?((Replace)op).state.postGeneration:op instanceof Delete?((Delete)op).state.signalGeneration:0;}
    }
    private static final class PreDelete {final String id,version;final long epoch;PreDelete(String i,String v,long e){id=i;version=v;epoch=e;}}
    public void authenticated(long epoch){connection.execute(()->{
        if(epoch!=connection.epoch())return;
        if(active!=null){if(active.epoch==epoch)return;retire(active,"CONNECTION_REPLACED");}
        summaryOwner=null;shownReplacementRequest=null;reconnectEpoch=-1;latest=Collections.emptyList();names.clear();listAt=0;listEpoch=epoch;query=null;preDelete=null;busy=false;refreshActor();
    });}
    public void closed(long closedEpoch,String reason){connection.execute(()->{
        if(query!=null&&query.epoch==closedEpoch)query=null;
        if(preDelete!=null&&preDelete.epoch==closedEpoch)preDelete=null;
        if(listEpoch==closedEpoch)listAt=0;
        if(active!=null&&active.epoch==closedEpoch)retire(active,"CONNECTION_CLOSED_"+reason);
        publishFaces();
    });}
    private void retire(Op old,String reason){
        if(old instanceof Replace){Replace r=(Replace)old;r.state.fail(reason,null);if(r.file!=null)try{r.file.close();}catch(Exception ignored){}}
        else((Delete)old).state.fail(reason,null);
        old.finished=SystemClock.elapsedRealtime();if(active==old){active=null;busy=false;}if(last==old)show(old);
        log.record("OLD_OPERATION_RETIRED","epoch_bound=true reason="+reason+" no_new_connection_failure=true");
    }
    public void refresh(){connection.post(()->{if(busy())error("WATCHFACE_OPERATION_BUSY");else if(reconnectRequired(connection.epoch()))error("RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");else refreshActor();});}
    private void refreshActor(){try{
        if(!connection.ready())throw new Core.Fault("CONNECT_AND_AUTHENTICATE_FIRST");
        if(listener!=null){listener.onError("");if(precheckFailure){shownReplacementRequest=null;listener.onOperation("","","",null,false,false);}}
        if(query!=null){log.record("REFRESH_MERGED","pending_query=true rows_retained=true");return;}
        // 刷新先使操作资格失效，保留已显示的列表和名称，直到收到完整的新列表。
        precheckFailure=false;listAt=0;
        // 屏幕/能力信息每个连接批次只读取一次，刷新表盘不重复探测设备参数。
        if(parametersRequestedEpoch != connection.epoch()) {
            parametersRequestedEpoch = connection.epoch();
            connection.sendControl(0x27,1,Wire.params());
        }
        requestList(null);
    }catch(IOException e){publishFaces();error(Core.code(e));}}
    private void publishFaces(){
        long own=connection.epoch(),age=SystemClock.elapsedRealtime()-listAt;
        boolean fresh=listAt!=0&&listEpoch==own&&age>=0&&age<=REPLACEMENT_LIST_AGE&&query==null&&connection.ready();
        observation=new ListObservation(listAt,listEpoch,fresh);
        if(listener!=null){
            listener.onRefreshing(query!=null&&query.epoch==own,own);
            boolean sameSession=listEpoch==own;
            listener.onFaces(sameSession?latest:Collections.emptyList(),sameSession?Collections.unmodifiableMap(new HashMap<>(names)):Collections.emptyMap(),fresh,own,serial);
        }
    }
    private void expireObservation(List<TlvView.Face> faces,long at,long epoch){
        connection.delay(()->{if(latest==faces&&listAt==at&&listEpoch==epoch&&connection.epoch()==epoch){
            log.record("LIST_OBSERVATION_EXPIRED","replacement_limit_ms="+REPLACEMENT_LIST_AGE+" rows_retained=true no_operation_sent=true");publishFaces();
        }},REPLACEMENT_LIST_AGE+1L);
    }
    private void error(String code){log.record("WATCHFACE_ERROR","code="+code);if(listener!=null)listener.onError(code);}
    private void replacementRequestFinished(ReplacementRequest request,String stage,String code){
        boolean failed=!"CANCELLED".equals(stage);
        log.record(failed?"REPLACE_PRECHECK_FAILED":"REPLACE_QUEUED_CANCELLED","code="+code+" command_sent=false upload_sent=false reconnect_required=false");
        // 连接已变化、或另一个操作/删除已持有 actor 时，不覆盖其状态或错误。
        boolean ownsSummary=!busy()&&request.identity.epoch==connection.epoch()&&summaryOwner==request.previousOwner;
        if(ownsSummary){
            summaryOwner=request;shownReplacementRequest=request;precheckFailure=failed;
            if(listener!=null){listener.onReplacementOperation(request.identity,stage,code,null,false,failed);listener.onReplacementError(request.identity,failed?code:"");}
        }else if(listener!=null)listener.onReplacementRequestFinished(request.identity,stage,code,failed);
        if(failed&&request.legacy&&!ownsSummary)error(code);
    }
    private void validate(TlvView.Face face,long epoch,long maxAge,boolean strictObservation)throws IOException{
        if(!connection.ready()||connection.epoch()!=epoch)throw new Core.Fault("SELECTED_CONNECTION_CHANGED");
        if(reconnectRequired(epoch))throw new Core.Fault("RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");
        if(!FacePolicy.eligible(face))throw new Core.Fault("SELECT_EXISTING_ORDINARY_FACE");
        long age=SystemClock.elapsedRealtime()-listAt;
        if(listAt==0||listEpoch!=epoch||age<0||age>maxAge||query!=null)throw new Core.Fault("FRESH_DEVICE_LIST_REQUIRED");
        int count=0;
        for(TlvView.Face x:latest)if(face.id.equals(x.id)){
            count++;
            // 普通表盘的正在使用位变化（4 与 5 互换）不改变替换目标身份。
            if(!face.version.equals(x.version)||!FacePolicy.eligible(x)||(strictObservation&&(!Objects.equals(face.type,x.type)||!Objects.equals(face.expandType,x.expandType))))throw new Core.Fault("SELECTED_FACE_CHANGED");
        }
        if(count!=1)throw new Core.Fault("SELECTED_ID_NOT_UNIQUE");
    }
    /** 保留旧四参数入口；新界面先分配身份，再用带取消门的重载入队。 */
    public void beginReplacement(TlvView.Face face,String version,HwtSource.Snapshot source,long epoch){beginReplacement(face,version,source,epoch,new ReplacementRequest(face==null?"":face.id,version,epoch,true,summaryOwner));}
    public void beginReplacement(TlvView.Face face,String version,HwtSource.Snapshot source,long epoch,ReplacementRequest request){
        Objects.requireNonNull(request);
        if(!request.submitted.compareAndSet(false,true))return;
        connection.post(()->{
            if(request.interruptRequested.get()){replacementRequestFinished(request,"CANCELLED","USER_CANCELLED_BEFORE_SEND");return;}
            if(busy()){replacementRequestFinished(request,"PRECHECK_FAILED","WATCHFACE_OPERATION_BUSY");return;}
            if(reconnectRequired(connection.epoch())){replacementRequestFinished(request,"PRECHECK_FAILED","RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");return;}
            log.record("REPLACE_CONFIRM_ACCEPTED","id="+(face==null?"absent":Core.clean(face.id))+" baseline="+(face==null?"absent":Core.clean(face.version))+" version="+Core.clean(version)+" list_age_ms="+(SystemClock.elapsedRealtime()-listAt)+" replacement_limit_ms="+REPLACEMENT_LIST_AGE);
            try{
                if(face==null||request.identity.epoch!=epoch||!Objects.equals(request.identity.id,face.id)||!Objects.equals(request.identity.version,version))throw new Core.Fault("SELECTED_CONNECTION_CHANGED");
                validate(face,epoch,REPLACEMENT_LIST_AGE,false);
                if(!Objects.equals(version,VersionPolicy.next(face.id,face.version,latest)))throw new Core.Fault("VERSION_CHANGED");
                // 确认前已在文件线程校验并冻结，串行连接执行器不重复计算全文件摘要。
                if(source==null||source.mode!=HwtSource.Mode.BIN||source.file==null||!source.file.isFile()||source.file.length()!=source.size)throw new Core.Fault("PAYLOAD_SNAPSHOT_UNAVAILABLE");
                if(request.interruptRequested.get()){replacementRequestFinished(request,"CANCELLED","USER_CANCELLED_BEFORE_SEND");return;}
                Replace r=new Replace(face,version,source,epoch,request);start(r);
                log.record("REPLACE_SEND_OPERATION","id="+r.id+" version="+r.version+" op=1 source_bytes="+source.size+" source_sha256="+source.hash+" pre_operation_budget=1 upload_budget=1");
                connection.sendControl(0x27,3,Wire.operation(r.id,r.version));show(r);timer(r);
            }catch(Exception e){
                String code=e instanceof SecurityException?"BLUETOOTH_PERMISSION_REQUIRED":Core.code(e);
                if(active instanceof Replace&&((Replace)active).request==request)failActive(code,null);
                else replacementRequestFinished(request,"PRECHECK_FAILED",code);
            }
        });
    }
    /** 即时置位仅属于此请求的取消门；真正原生停止必须在 actor 再验对象、代次与终态。 */
    public void cancelReplacement(ReplacementRequest request){
        if(request==null)return;request.interruptRequested.set(true);
        connection.post(()->{
            Op op=active;
            if(!(op instanceof Replace))return;
            Replace r=(Replace)op;ReplacementIdentity identity=request.identity;
            if(r.request!=request||r.request.identity!=identity||r.epoch!=identity.epoch||connection.epoch()!=identity.epoch||r.state.terminal())return;
            r.state.cancel();finish(r);
        });
    }
    public void prepareDeletion(String id,String version,long epoch){connection.post(()->{
        if(busy()||query!=null){error("WAIT_FOR_CURRENT_OPERATION_OR_QUERY");return;}
        try{if(!connection.ready()||connection.epoch()!=epoch)throw new Core.Fault("SELECTED_CONNECTION_CHANGED");if(reconnectRequired(epoch))throw new Core.Fault("RECONNECT_AFTER_UNCERTAIN_FILE_RESULT");preDelete=new PreDelete(id,version,epoch);listAt=0;requestList(null);}
        catch(IOException e){preDelete=null;error(Core.code(e));}
    });}
    public void beginDeletion(TlvView.Face face,long epoch){connection.post(()->{
        if(busy()){error("WATCHFACE_OPERATION_BUSY");return;}
        try{
            validate(face,epoch,DELETE_LIST_AGE,true);
            for(Op old:history)if(old instanceof Delete&&old.epoch==epoch&&old.matches(face.id,face.version)&&old.finished!=0&&SystemClock.elapsedRealtime()-old.finished<=POST_TIMEOUT)throw new Core.Fault("RECENT_SAME_DELETE_WAIT_OR_RECONNECT");
            Delete d=new Delete(face,latest,epoch);start(d);connection.sendControl(0x27,3,Wire.deletion(d.id,d.version));show(d);timer(d);
        }catch(IOException e){if(active!=null)failActive(Core.code(e),null);else error(Core.code(e));}
    });}
    private void start(Op op){
        precheckFailure=false;active=op;last=op;busy=true;summaryOwner=op instanceof Replace?((Replace)op).request:op;
        shownReplacementRequest=op instanceof Replace?((Replace)op).request:null;
        if(listener!=null){if(op instanceof Replace)listener.onReplacementError(((Replace)op).request.identity,"");else listener.onError("");}
        history.addFirst(op);while(history.size()>4)history.removeLast();
        log.record(op instanceof Delete?"DELETE_START":"REPLACE_START","id="+op.id+" version="+op.version+" file_budget="+(op instanceof Delete?0:1)+" auth08_09_not_fabricated=true");
    }
    private void requestList(Op op)throws IOException{
        if(op!=null&&op.epoch!=connection.epoch())throw new Core.Fault("OPERATION_EPOCH_CHANGED");
        if(query!=null){log.record("LIST_QUERY_MERGED","pending_generation="+query.generation);return;}
        if(op!=null&&++op.listQueries>POST_QUERY_BUDGET){
            log.record("LIST_QUERY_BUDGET_EXHAUSTED","id="+op.id+" version="+op.version+" query_budget="+POST_QUERY_BUDGET+" no_more_query=true");
            if(op instanceof Delete)throw new Core.Fault("DELETE_LIST_QUERY_BUDGET_EXHAUSTED");
            return; // 等待既有的后置或总超时，不自行推断最终结果。
        }
        Query q=new Query(++serial,connection.epoch(),op);query=q;publishFaces();
        try{connection.sendControl(0x27,2,Wire.list(2));}catch(IOException e){if(query==q)query=null;listAt=0;publishFaces();throw e;}
        log.record("LIST_QUERY","serial="+q.serial+" generation="+q.generation+" local_not_wire_nonce=true");
        connection.delay(()->{if(query==q&&q.epoch==connection.epoch()){
            query=null;listAt=0;if(active==op&&op!=null)failActive("LIST_QUERY_TIMEOUT",null);
            else{preDelete=null;error("LIST_QUERY_TIMEOUT_RECONNECT");connection.protocolFailure("LIST_QUERY_TIMEOUT");}publishFaces();
        }},15000);
    }
    public void packet(long epoch,int service,int command,byte[] body,boolean encrypted){
        if(epoch!=connection.epoch()||!connection.ready())return;Op op=active;if(op!=null&&op.epoch!=epoch)return;boolean owned=false;
        try{
            if(service==0x28){
                if(!(op instanceof Replace)||command<2||command>8)return;Replace r=(Replace)op;if(r.file==null)return;
                if(command==6){log.record("RAW_FILE_DATA_OBSERVED","bytes="+body.length+" no_ack_or_completion_inference=true");return;}owned=true;
                for(FileTransfer.Outgoing out:r.file.onPacket(command,body,true))connection.sendFile(out.command,out.body(),out.encrypt);
                if(command==3){List<TlvView.Part> hashFields=TlvView.decode(body);log.record("FILE_HASH_REQUEST","file_id="+r.file.fileId()+" check_mode="+Wire.number(hashFields,2)+" missing_not_defaulted_to_three=true");}
                if(command==7&&r.file.resultObserved()){
                    r.fileResultSeen=true;
                    Integer raw=r.file.peerResultRaw();String failure=r.file.completionFailure();
                    log.record("FILE_RESULT_OBSERVED","file_id="+r.file.fileId()+" raw="+raw+" completion_failure="+failure+" ack_dispatched_first=true transfer_only=true");
                    if(raw!=null&&raw==0)r.state.nativeResult(0);
                    else if(failure!=null)throw new Core.Fault(failure);
                    else if(raw!=null)r.state.nativeResult(raw);
                }
                show(r);finish(r);return;
            }
            if(service!=0x27||(command!=1&&command!=2&&command!=3&&command!=5&&command!=6))return;
            if(command==2)owned=query!=null&&query.epoch==epoch&&query.op==op&&op!=null;
            List<TlvView.Part> fields=TlvView.decode(body);
            if(command==1){Long w=Wire.number(fields,2),h=Wire.number(fields,3),cap=Wire.number(fields,0x11);if(listener!=null)listener.onParameters(w==null?null:w.intValue(),h==null?null:h.intValue(),cap);return;}
            if(command==2){handleList(fields,epoch);return;}
            if(command==6){Map<String,String> found=WireNames.parse(fields);for(TlvView.Face f:latest){String name=found.get(f.id);if(name!=null)names.put(f.id+"_"+f.version,Core.clean(name));}publishFaces();return;}
            String id=Wire.text(fields,1),version=Wire.text(fields,2);owned=op!=null&&op.matches(id,version);
            Long error=Wire.number(fields,0x7f),value=Wire.number(fields,command==3?4:3);
            if(!owned){late(id,version,command,error,value,fields,epoch);return;}
            if(command==5&&value!=null&&value>Integer.MAX_VALUE)throw new Core.Fault("REPORT_VALUE_OVERFLOW");
            if(command==5&&(error==null||error==100000))connection.sendControl(0x27,5,concat(Core.tlv(1,id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Core.tlv(2,version.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Core.tlv(0x7f,num(100000,4))));
            if(op instanceof Replace){
                Replace r=(Replace)op;
                // 替换流程不将 27/03 的 03 字段作为必需操作回显。
                ReplacementState.Action action=command==3?r.state.operation(error,value):r.state.report(value==null?null:value.intValue(),error);
                log.record(command==3?"OPERATION_RESPONSE":"WATCH_REPORT","id="+id+" version="+version+" error="+error+" value="+value+" "+r.state.summary());
                actReplace(r,action);show(r);finish(r);
            }else{
                Delete d=(Delete)op;DeletionState.Action action;
                if(command==3){Long echo=Wire.number(fields,3);if(echo!=null&&echo!=2)return;action=d.state.operation(error,value,echo);}
                else action=d.state.report(value==null?null:value.intValue(),error);
                if(action==DeletionState.Action.QUERY)requestList(d);show(d);finish(d);
            }
        }catch(IOException e){if(owned&&active==op)failActive(Core.code(e),null);else{
            error(Core.code(e));if(service==0x27&&command==2&&query!=null&&query.epoch==epoch){query=null;preDelete=null;listAt=0;publishFaces();}
        }}
    }
    private void handleList(List<TlvView.Part> fields,long epoch)throws IOException{
        Query q=query;if(q==null||q.epoch!=epoch)return;List<TlvView.Face> faces=Wire.faces(fields);Long err=Wire.number(fields,0x7f);
        if(err!=null&&err!=0&&err!=100000)throw new Core.Fault("DEVICE_LIST_ERROR");
        query=null;latest=faces;listAt=SystemClock.elapsedRealtime();listEpoch=epoch;
        Set<String> listedKeys=new HashSet<>();for(TlvView.Face f:faces)listedKeys.add(f.id+"_"+f.version);
        names.keySet().retainAll(listedKeys);
        publishFaces();expireObservation(faces,listAt,epoch);
        log.record("RESOURCE_LIST","entries="+faces.size()+" serial="+q.serial+" generation="+q.generation);
        for(TlvView.Face f:faces)log.record("LIST_ENTRY","id="+f.id+" version="+f.version+" type="+f.type+" expand="+f.expandType);
        if(preDelete!=null){
            PreDelete p=preDelete;preDelete=null;if(p.epoch!=epoch)throw new Core.Fault("DELETE_CONNECTION_CHANGED");
            TlvView.Face selected=null;int count=0;for(TlvView.Face f:faces)if(p.id.equals(f.id)){selected=f;count++;}
            if(count!=1||selected==null||!p.version.equals(selected.version)||!FacePolicy.eligible(selected))throw new Core.Fault("DELETE_PREPARATION_CHANGED");
            if(listener!=null)listener.onPreparedDeletion(selected,epoch);
        }
        if(q.op!=null&&q.op==active){Op op=q.op;if(op instanceof Replace){
            Replace r=(Replace)op;r.state.list(faces,q.generation,true);show(r);finish(r);
            if(active==r&&r.state.postObserved)connection.delay(()->{if(active==r)try{requestList(r);}catch(IOException e){failActive(Core.code(e),null);}},q.generation<r.state.postGeneration?1:1500);
        }else{
            Delete d=(Delete)op;Set<String> ids=new HashSet<>();for(TlvView.Face f:faces)ids.add(f.id);
            if(ids.containsAll(d.otherIds))d.state.list(faces,q.generation,true,q.serial);else{d.state.consecutiveAbsences=0;d.state.reason="DELETE_NON_TARGET_LIST_INCOMPLETE";}
            show(d);finish(d);
            if(active==d)connection.delay(()->{if(active==d)try{requestList(d);}catch(IOException e){failActive(Core.code(e),null);}},q.generation<d.state.signalGeneration?1:d.state.consecutiveAbsences==1?500:1500);
        }}
        if(active==null)requestNames();
    }
    private void requestNames(){try{
        if(latest.isEmpty()||!connection.capabilities().hasCommand(0x27,6))return;List<TlvView.Face> requested=new ArrayList<>();
        for(TlvView.Face f:latest)if(f.validResource()&&requested.size()<64)requested.add(f);
        if(!requested.isEmpty())connection.sendControl(0x27,6,WireNames.request(requested,"zh".equals(Locale.getDefault().getLanguage())?2:1));
    }catch(IOException e){log.record("NAMES_UNAVAILABLE","code="+Core.code(e));}}
    private void actReplace(Replace r,ReplacementState.Action action)throws IOException{
        if(active!=r||r.epoch!=connection.epoch())return;
        if(action==ReplacementState.Action.UPLOAD){
            if(r.file!=null)throw new Core.Fault("UPLOAD_BUDGET_EXHAUSTED");r.file=new FileTransfer(r.id,r.version,r.source);
            log.record("UPLOAD_START","id="+r.id+" version="+r.version+" bytes="+r.source.size+" sha256="+r.source.hash+" file_type=1 upload_budget=1");
            connection.sendFile(2,r.file.metadataBody(),true);
        }else if(action==ReplacementState.Action.ACTIVATE){
            r.postAt=SystemClock.elapsedRealtime();log.record("POST_UPLOAD_ACTIVATION","id="+r.id+" version="+r.version+" report_two_drives_post=true file_result_is_separate=true post_timeout_ms="+POST_TIMEOUT);
            connection.sendControl(0x27,3,Wire.operation(r.id,r.version));
        }else if(action==ReplacementState.Action.QUERY)requestList(r);
    }
    private void timer(Op op){connection.delay(()->{
        if(active!=op||op.epoch!=connection.epoch())return;long now=SystemClock.elapsedRealtime(),age=now-op.started;
        boolean timed=age>(op instanceof Delete?POST_TIMEOUT:TOTAL_TIMEOUT)||(op instanceof Replace&&((Replace)op).postAt!=0&&now-((Replace)op).postAt>POST_TIMEOUT)||(op instanceof Delete&&((Delete)op).state.phase==DeletionState.Phase.WAIT_DEVICE&&age>DELETE_OPERATION_TIMEOUT);
        if(timed){if(op instanceof Delete)((Delete)op).state.timeout();else((Replace)op).state.timeout();finish(op);return;}timer(op);
    },1000);}
    private void failActive(String reason,Long err){Op op=active;if(op==null)return;if(op instanceof Replace)((Replace)op).state.fail(reason,err);else((Delete)op).state.fail(reason,err);finish(op);}
    private void finish(Op op){
        if(active!=op)return;boolean terminal=op instanceof Replace?((Replace)op).state.terminal():((Delete)op).state.terminal();if(!terminal)return;
        boolean resultSettled=op instanceof Replace&&((Replace)op).fileResultSeen;
        show(op);op.finished=SystemClock.elapsedRealtime();active=null;busy=false;query=null;
        if(op instanceof Replace&&((Replace)op).file!=null)try{((Replace)op).file.close();}catch(Exception ignored){}
        boolean ok=op instanceof Replace?(((Replace)op).state.phase==ReplacementState.Phase.LISTED||((Replace)op).state.phase==ReplacementState.Phase.NO_TRANSFER):((Delete)op).state.phase==DeletionState.Phase.DELETED;
        log.record(op instanceof Replace?"REPLACE_SUMMARY":"DELETE_SUMMARY","id="+op.id+" version="+op.version+" reliable_protocol_end="+ok+" visual_application_unverified=true");
        if(!ok&&op.epoch==connection.epoch()){
            listAt=0;reconnectEpoch=op.epoch;
            if(!resultSettled)connection.protocolFailure("WATCHFACE_OPERATION_UNCERTAIN_RECONNECT");
            else log.record("FILE_RESULT_RECONNECT_REQUIRED","ack_queue_preserved=true no_more_operation_until_new_authenticated_epoch=true");
        }
        publishFaces();
    }
    private void show(Op op){if(listener==null||summaryOwner!=(op instanceof Replace?((Replace)op).request:op))return;if(op instanceof Replace){
        Replace r=(Replace)op;shownReplacementRequest=r.request;Integer progress=r.file==null?null:(int)Math.min(100,r.file.bytesSentOrPrepared()*100/r.file.totalSize());
        listener.onReplacementOperation(r.request.identity,r.state.phase.name(),r.state.summary(),progress,!r.state.terminal(),r.state.phase==ReplacementState.Phase.FAILED||r.state.phase==ReplacementState.Phase.TIMED_OUT||r.state.phase==ReplacementState.Phase.CANCELLED);
    }else{shownReplacementRequest=null;Delete d=(Delete)op;listener.onOperation("deletion",d.state.phase.name(),d.state.summary(),null,!d.state.terminal(),d.state.phase==DeletionState.Phase.FAILED||d.state.phase==DeletionState.Phase.TIMED_OUT);}}
    private void late(String id,String version,int command,Long error,Long value,List<TlvView.Part> fields,long epoch)throws IOException{
        boolean badError=error!=null&&error!=0&&(command!=5||error!=100000),reportThree=command==5&&value!=null&&value==3;
        if(!badError&&!reportThree)return;
        for(Op old:history)if(old!=active&&old.epoch==epoch&&old.matches(id,version)&&old.finished!=0&&SystemClock.elapsedRealtime()-old.finished<POST_TIMEOUT){
            if(old instanceof Delete){
                if(!badError)continue;if(command==5&&!reportThree)return;
                if(command==3){Long echo=Wire.number(fields,3);if(echo!=null&&echo!=2)return;}
                ((Delete)old).state.fail("LATE_DEVICE_ERROR",error);
            }else ((Replace)old).state.fail(reportThree?"LATE_WATCH_REPORT_FAILURE":"LATE_DEVICE_ERROR",error);
            log.record("LATE_ERROR","id="+id+" version="+version+" report_three="+reportThree+" no_retry_no_ack_no_stop=true");
            if(active==null&&last==old&&summaryOwner==(old instanceof Replace?((Replace)old).request:old)){show(old);if(old instanceof Delete)connection.protocolFailure("LATE_OPERATION_ERROR_RECONNECT");}
            return;
        }
    }
    private static byte[] num(long n,int len){byte[] b=new byte[len];for(int i=len-1;i>=0;i--){b[i]=(byte)n;n>>>=8;}return b;}
    private static byte[] concat(byte[]... p)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] x:p)b.write(x);return b.toByteArray();}
}
