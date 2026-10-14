package local.waze.diagnostics;

import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import org.json.*;
import java.io.File;
import java.util.*;

/** Instrumentation uses only fake delivery; never contacts production or GitHub. */
public final class DiagnosticsTests extends Instrumentation {
    private int checks;
    interface Checked {void run() throws Exception;}
    private void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    private void reject(Checked task,String name)throws Exception{boolean rejected=false;try{task.run();}catch(Exception expected){rejected=true;}check(rejected,name);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){
        Bundle result=new Bundle();
        try{tests();automaticTests();uiTests();result.putString("summary","DiagnosticsTests: "+checks+" checks passed; fake transport only");finish(0,result);}
        catch(Throwable e){result.putString("failure",e.getClass().getSimpleName()+": "+e.getMessage()+"; checks="+checks+"; UI="+uiSummary(getUiAutomation().getRootInActiveWindow()));finish(1,result);}
    }
    private DiagnosticStore store()throws Exception{
        final File directory=new File(getTargetContext().getNoBackupFilesDir(),"test-"+UUID.randomUUID());
        return new DiagnosticStore(new ContextWrapper(getTargetContext()) {public File getNoBackupFilesDir(){return directory;}});
    }
    private static final String ID="9bf5df36-5b0c-4141-922a-a41dc4047d5f";
    private static final String ISSUE="https://github.com/romanchukg-cloud/waze-for-byd/issues/123";
    private static String reply(String state)throws Exception{return ReportSchema.obj("report_id",ID,"status",state,"issue_url",ISSUE,"poll_after_seconds",10).toString();}
    static final class Fake implements ReportTransport {
        List<String> methods=new ArrayList<>(),bodies=new ArrayList<>(),keys=new ArrayList<>(),tokens=new ArrayList<>();
        Reply reply;boolean offline;long delayMillis;int capabilityCalls;
        Reply capability=new Reply(200,"{\"schema_versions\":[1,2],\"automatic_reports\":true,\"automatic_grouping\":\"project_build_failure_utc_day\",\"intake_enabled\":true,\"delivery_enabled\":true}",0);
        public Reply capabilities() throws Exception {capabilityCalls++;if(offline)throw new java.io.IOException();return capability;}
        public Reply request(String m,String id,String body,String key,String token)throws Exception {
            methods.add(m);bodies.add(body);keys.add(key);tokens.add(token);
            if(delayMillis>0)Thread.sleep(delayMillis);
            if(offline)throw new java.io.IOException("SECRET exception must never enter a report");
            return reply;
        }
    }
    private void due(ReportEngine e,JSONObject r,long now)throws Exception{r.put("next",now);e.store.save();e.tick(now);}
    private Context sameDirectory(DiagnosticStore s) throws Exception {
        java.lang.reflect.Field field=DiagnosticStore.class.getDeclaredField("context");field.setAccessible(true);
        return (Context)field.get(s);
    }
    private void tests()throws Exception{
        long now=System.currentTimeMillis();DiagnosticStore s=store();
        check(!s.noticed()&&s.automaticChoice(),"default selected; notice unanswered");
        s.saveChoice(false,true);DiagnosticStore reopened=new DiagnosticStore(sameDirectory(s));
        check(reopened.noticed()&&!reopened.automaticChoice(),"opt-out persists");
        check(!AutomaticPolicy.supported(new ReportTransport.Reply(200,"{\"schema_versions\":[1]}",0)),"v1 automatic gate closed");
        s.record("guidance_send",ReportSchema.obj("outcome","sent","icon",2,"turn_meters",300,"route_meters",5700,"route_seconds",589,"reason","changed"),now-1000);
        String body=s.report("hud","yes","no","unknown",now);
        JSONObject report=ReportSchema.validate(body);
        check(report.getString("project_id").equals("waze-for-byd"),"project routing");
        check(report.getJSONArray("components").length()==0,"components empty");
        check(report.getJSONObject("problem").getString("hud_guidance").equals("no"),"independent observations");
        check(!body.contains("SECRET")&&!body.contains("token")&&!body.contains("next_road"),"safe report");
        reject(() -> ReportSchema.event("playback",ReportSchema.obj("state","playing","bridge_connected",true)),"playback rejected");
        reject(() -> ReportSchema.event("failure",ReportSchema.obj("stage","source","code","SECRET")),"freeform failure rejected");
        reject(() -> ReportSchema.event("lifecycle",ReportSchema.obj("state","started","road","SECRET")),"unknown event field rejected");
        reject(() -> ReportSchema.event("wheel_key",ReportSchema.obj("action","down","key_code",305.0,"scan_code",300,"consumed",true)),"integer required");
        reject(() -> ReportSchema.event("projection",ReportSchema.obj("state","created","display_id",2,"width",20000,"height",720)),"bounds");
        JSONObject attack=new JSONObject(body);attack.put("preferences","SECRET");final String extra=attack.toString();
        reject(() -> ReportSchema.validate(extra),"unknown top-level field");
        attack=new JSONObject(body);attack.getJSONObject("app").put("build","@everyone\nSECRET");final String unsafe=attack.toString();
        reject(() -> ReportSchema.validate(unsafe),"markdown/control attack");
        attack=new JSONObject(body);attack.getJSONObject("settings").put("bridge_enabled",true);final String wrong=attack.toString();
        reject(() -> ReportSchema.validate(wrong),"wrong settings profile");
        attack=new JSONObject(body);attack.getJSONArray("components").put(ReportSchema.obj("role","navigation_adapter","package","com.byd.amapservice","version","1"));final String comps=attack.toString();
        reject(() -> ReportSchema.validate(comps),"components not registered");
        attack=new JSONObject(body);attack.getJSONArray("events").getJSONObject(0).put("elapsed_ms",600001);final String outOfCapture=attack.toString();
        reject(() -> ReportSchema.validate(outOfCapture),"capture bound");
        check(!ReportEngine.validIssue("https://github.com/evil/repo/issues/1")&&!ReportEngine.validIssue(ISSUE+"?token=x")&&!ReportEngine.validIssue("http://github.com/romanchukg-cloud/waze-for-byd/issues/1"),"URL allowlist");
        check(ReportEngine.validIssue(ISSUE)&&ReportEngine.validId(ID),"valid receipt and issue");
        Fake f=new Fake();f.offline=true;ReportEngine e=new ReportEngine(s,f);JSONObject r=s.enqueue(body,now);
        check(ReportEngine.validId(r.getString("key"))&&r.getString("token").matches("[a-zA-Z0-9_-]{43}"),"random identifiers");
        check(!s.automaticChoice(),"manual with auto off");
        e.tick(now);check(r.getString("state").equals("pending"),"offline pending");
        long next=r.getLong("next");check(next>=now+10000&&next<=now+12500,"backoff");
        String key=r.getString("key"),token=r.getString("token");
        DiagnosticStore afterRestart=new DiagnosticStore(sameDirectory(s));
        e=new ReportEngine(afterRestart,f);r=afterRestart.find(key);due(e,r,now+20000);
        check(f.bodies.get(0).equals(f.bodies.get(1))&&f.keys.get(1).equals(key)&&f.tokens.get(1).equals(token),"restart exact retry");
        f.offline=false;f.reply=new ReportTransport.Reply(202,reply("queued"),0);due(e,r,now+30000);
        check(r.getString("state").equals("queued")&&!r.has("issue"),"queued is not sent");
        f.reply=new ReportTransport.Reply(200,reply("submitted"),0);due(e,r,now+40000);
        check(r.getString("state").equals("submitted")&&r.getString("issue").equals(ISSUE)&&f.methods.get(f.methods.size()-1).equals("GET"),"confirmed delivery");
        int sent=f.methods.size();e.tick(now+60000);check(f.methods.size()==sent,"terminal no polling");
        JSONObject fresh=afterRestart.enqueue(body,now);e.cancel(fresh.getString("key"),now);check(afterRestart.find(fresh.getString("key"))==null,"cancel never-attempted local");
        fresh=afterRestart.enqueue(body,now);f.offline=true;due(e,fresh,now);String cancelKey=fresh.getString("key");e.cancel(cancelKey,now+1000);
        check(afterRestart.find(cancelKey)!=null&&fresh.getBoolean("cancel"),"lost POST retained");
        f.offline=false;f.reply=new ReportTransport.Reply(202,reply("queued"),0);due(e,fresh,now+2000);
        check(f.methods.get(f.methods.size()-1).equals("POST")&&fresh.getBoolean("cancel"),"cancel replays same POST");
        f.reply=new ReportTransport.Reply(204,"",0);due(e,fresh,now+3000);
        check(f.methods.get(f.methods.size()-1).equals("DELETE")&&fresh.getString("state").equals("cancelled"),"queued cancelled");
        JSONObject third=afterRestart.enqueue(body,now);
        final DiagnosticStore full=afterRestart;reject(() -> full.enqueue(body,now),"three-report bound");
        check(afterRestart.find(key)!=null,"no manual eviction");
        f.reply=new ReportTransport.Reply(429,"",7200000);due(e,third,now+5000);check(third.getLong("next")>=now+7205000,"Retry-After respected");
        String conflictKey=third.getString("key");
        f.reply=new ReportTransport.Reply(409,"",0);due(e,third,now+6000);check(third.getString("state").equals("stopped")&&third.getString("key").equals(conflictKey),"conflict stops without re-key");
        e.cancel(third.getString("key"),now+7000);f.reply=new ReportTransport.Reply(202,reply("queued"),0);due(e,third,now+8000);
        f.reply=new ReportTransport.Reply(409,"",0);due(e,third,now+9000);check(third.getString("state").equals("delivering")&&!third.getBoolean("cancel"),"cancel/delivery race");
        
        f.reply=new ReportTransport.Reply(200,reply("delivery_uncertain"),0);due(e,third,now+10000);
        check(ReportEngine.active(third)&&third.getLong("next")>=now+610000,"uncertain delivery polls same receipt slowly");
        int uncertainCalls=f.methods.size();e.cancel(third.getString("key"),now+11000);
        check(!third.getBoolean("cancel"),"cannot cancel already claimed uncertain delivery");
        f.reply=new ReportTransport.Reply(200,reply("submitted"),0);due(e,third,now+700000);
        check(third.getString("state").equals("submitted")&&f.methods.size()==uncertainCalls+1&&f.methods.get(f.methods.size()-1).equals("GET"),"uncertain reconciliation uses GET not new POST");
        afterRestart.remove(third.getString("key"));JSONObject expired=afterRestart.enqueue(body,now-DiagnosticStore.DAY);e.tick(now);check(expired.getString("state").equals("expired"),"retry window bounded");
        afterRestart.remove(expired.getString("key"));JSONObject clock=afterRestart.enqueue(body,now+1000);due(e,clock,now);check(clock.getString("state").equals("expired"),"clock rollback fails closed");

        for(int code:new int[]{400,404,409,413,415,422}) {
            DiagnosticStore invalidStore=store();JSONObject invalidReport=invalidStore.enqueue(body,now);
            Fake invalidFake=new Fake();invalidFake.reply=new ReportTransport.Reply(code,"{}",0);
            ReportEngine invalidEngine=new ReportEngine(invalidStore,invalidFake);invalidEngine.tick(now);
            check(invalidReport.getString("state").equals("stopped")&&invalidFake.methods.size()==1,"terminal HTTP "+code);
            invalidEngine.tick(now+10000);check(invalidFake.methods.size()==1,"no retry for terminal HTTP "+code);
        }
        DiagnosticStore links=store();JSONObject badLink=links.enqueue(body,now);Fake unsafeLink=new Fake();
        unsafeLink.reply=new ReportTransport.Reply(200,new JSONObject(reply("submitted")).put("issue_url","https://github.com/evil/repo/issues/123").toString(),0);
        new ReportEngine(links,unsafeLink).tick(now);
        check(badLink.getString("state").equals("stopped")&&!badLink.has("issue"),"server cannot supply foreign issue link");
        DiagnosticStore win=store();
        win.record("failure",ReportSchema.obj("stage","source","code","timeout"),now-35000);
        win.record("failure",ReportSchema.obj("stage","source","code","unavailable"),now-5000);
        String frozen=win.reportWindow("other","unknown","unknown","unknown",now,now-60000,now-10000,true);
        check(new JSONObject(frozen).getJSONObject("capture").getInt("duration_seconds")==50&&new JSONObject(frozen).getJSONArray("events").length()==1,"anchored diagnostic session excludes later events");
        check(win.reportWindow("other","unknown","unknown","unknown",now+20000,now-60000,now-10000,true).equals(frozen),"delayed preview keeps original session window");
        check(android.util.Base64.decode(token,android.util.Base64.URL_SAFE|android.util.Base64.NO_PADDING|android.util.Base64.NO_WRAP).length==32,"256-bit report token");
        DiagnosticStore serialStore=store();
        JSONObject one=serialStore.enqueue(body,now),two=serialStore.enqueue(body,now);
        Fake slow=new Fake();slow.delayMillis=30;slow.reply=new ReportTransport.Reply(429,"{}",60000);
        new ReportEngine(serialStore,slow).tick(now);
        check(two.getLong("next")>=one.getLong("next")+20,"Retry-After starts after each response even in a multi-report worker");
        check(ReportTransport.Https.retryAfter("120",now)==120000,"Retry-After seconds");
        check(ReportTransport.Https.retryAfter("invalid",now)==0,"invalid Retry-After");
        s.record("failure",ReportSchema.obj("stage","source","code","timeout"),now+2000);
        String back=s.report("other","unknown","unknown","unknown",now);check(!back.contains("timeout"),"future events removed");
        s.record("lifecycle",ReportSchema.obj("state","started"),now-600001);
        check(!s.report("other","unknown","unknown","unknown",now).contains("started"),"ten-minute retention");
        for(int i=0;i<510;i++)s.record("lifecycle",ReportSchema.obj("state","started"),now);
        JSONObject bounded=new JSONObject(s.report("other","unknown","unknown","unknown",now));
        check(bounded.getJSONArray("events").length()==500&&bounded.getJSONObject("capture").getBoolean("truncated"),"500-event capture cap");
        check(s.report("other","unknown","unknown","unknown",now).getBytes("UTF-8").length<=262144,"report byte cap");
    }
    private JSONObject auto(DiagnosticStore s,long now)throws Exception {
        s.record("failure",ReportSchema.obj("stage","projection","code","permission_denied"),now);
        return AutomaticPolicy.failure(s,"projection","permission_denied",now);
    }
    private void automaticTests()throws Exception {
        long now=System.currentTimeMillis();DiagnosticStore s=store();
        check(auto(s,now)==null&&s.queue.length()==0,"automatic blocked before Continue");
        s.saveChoice(true,true);
        JSONObject a=auto(s,now);check(a!=null&&a.getBoolean("automatic"),"measured failure admitted after consent");
        JSONObject payload=ReportSchema.validate(a.getString("body"));
        check(payload.getInt("schema_version")==2&&payload.getString("report_source").equals("automatic"),"explicit v2 automatic origin");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(getTargetContext().getFilesDir(),"automatic-contract.json"))){out.write(payload.toString().getBytes("UTF-8"));}
        check(payload.getJSONObject("problem").getString("hud_guidance").equals("unknown"),"auto never infers physical output");
        String fp=AutomaticPolicy.fingerprint("projection","permission_denied");
        check(fp.length()==64&&fp.equals(AutomaticPolicy.fingerprint("projection","permission_denied"))&&!fp.equals(AutomaticPolicy.fingerprint("source","timeout")),"bounded identity-free fingerprint");
        check(auto(s,now+1)==null,"fingerprint cooldown");
        check(AutomaticPolicy.failure(s,"source","timeout",now+2)==null,"installation daily cap across failures");
        check(AutomaticPolicy.failure(s,"upload","timeout",now)==null,"upload failures never recurse");
        DiagnosticStore again=new DiagnosticStore(sameDirectory(s));
        check(auto(again,now+10)==null,"cooldown persists across restart");
        String manual=s.report("hud","unknown","no","unknown",now);
        s.enqueue(manual,now);s.enqueue(manual,now);
        check(s.queue.length()==3,"automatic reserves two manual slots");
        reject(() -> s.enqueue(manual,now),"combined three-report cap");
        s.saveChoice(false,false);
        check(s.queue.length()==2&&!s.queue.getJSONObject(0).optBoolean("automatic"),"opt-out removes unsent auto only");
        check(auto(s,now)==null,"opt-out blocks new auto");
        JSONObject old=ReportSchema.obj("notice_version",1,"automatic_choice",true);
        check(!AutomaticPolicy.allowed(old,now,now,1),"old unavailable-build notice cannot authorize new uploads");
        old.put("notice_version",2).put("last_auto",now).put("auto_elapsed",1000).put("auto_boot",2);
        check(!AutomaticPolicy.allowed(old,now-1,2000,2),"wall rollback blocks admission");
        check(!AutomaticPolicy.allowed(old,now+DiagnosticStore.DAY,2000,2),"wall fast-forward cannot bypass same-boot cap");
        check(AutomaticPolicy.allowed(old,now+DiagnosticStore.DAY,1000+DiagnosticStore.DAY,2),"cooldown releases after full day");
        check(AutomaticPolicy.allowed(old,now+DiagnosticStore.DAY,100,3),"full-day cooldown survives reboot");
        JSONObject noFailure=new JSONObject(manual).put("schema_version",2).put("report_source","automatic").put("events",new JSONArray());
        reject(() -> ReportSchema.validate(noFailure.toString()),"automatic needs measured failure");
        JSONObject uploadOnly=new JSONObject(noFailure.toString());uploadOnly.getJSONArray("events").put(ReportSchema.obj("elapsed_ms",0,"type","failure","data",ReportSchema.obj("stage","upload","code","timeout")));
        reject(() -> ReportSchema.validate(uploadOnly.toString()),"upload event cannot authorize auto");
        check(ReportSchema.validate(new JSONObject(manual).put("schema_version",2).put("report_source","manual").toString())!=null,"v2 manual accepted");
        DiagnosticStore offline=store();offline.saveChoice(true,true);JSONObject r=auto(offline,now);Fake f=new Fake();f.offline=true;ReportEngine e=new ReportEngine(offline,f);
        e.tick(now);check(f.methods.isEmpty()&&f.capabilityCalls==1&&r.getInt("attempts")==0,"offline capability blocks unsupported uploads");
        String body=r.getString("body"),key=r.getString("key"),token=r.getString("token");
        f.offline=false;f.capability=new ReportTransport.Reply(200,"{\"schema_versions\":[1],\"automatic_reports\":false}",0);
        due(e,r,now+1000);check(f.methods.isEmpty(),"old server receives no v2 payload");
        f.capability=new Fake().capability;f.reply=new ReportTransport.Reply(503,"{}",0);due(e,r,now+2000);
        check(f.methods.size()==1&&r.getInt("attempts")==1,"verified capability enables auto POST");
        DiagnosticStore restored=new DiagnosticStore(sameDirectory(offline));e=new ReportEngine(restored,f);r=restored.find(key);
        f.reply=new ReportTransport.Reply(202,reply("queued"),0);due(e,r,now+3000);
        check(f.bodies.get(1).equals(body)&&f.keys.get(1).equals(key)&&f.tokens.get(1).equals(token),"automatic exact restart replay");
        restored.saveChoice(false,false);f.reply=new ReportTransport.Reply(204,"",0);due(e,r,now+4000);
        check(f.methods.get(2).equals("DELETE")&&r.getString("state").equals("cancelled")&&!r.has("body")&&!r.has("token"),"opt-out cancels known queue and clears payload");
        DiagnosticStore lost=store();lost.saveChoice(true,true);JSONObject lostReport=auto(lost,now);Fake lostFake=new Fake();lostFake.reply=new ReportTransport.Reply(503,"{}",0);ReportEngine lostEngine=new ReportEngine(lost,lostFake);lostEngine.tick(now);
        lost.saveChoice(false,false);lostEngine.tick(now+10000);
        check(lostFake.methods.size()==1&&lostReport.getString("state").equals("stopped")&&!lostReport.has("body"),"lost acceptance is not replayed after opt-out");
        DiagnosticStore exp=store();exp.saveChoice(true,true);JSONObject er=auto(exp,now);Fake ef=new Fake();ef.reply=new ReportTransport.Reply(429,"{}",31536000000L);ReportEngine ee=new ReportEngine(exp,ef);ee.tick(now);
        check(ee.next(now)<=now+DiagnosticStore.DAY,"Retry-After cannot retain auto content indefinitely");
        ee.tick(now+DiagnosticStore.DAY);check(er.getString("state").equals("expired")&&!er.has("body")&&!er.has("token"),"automatic expires and erases unsent content");
        DiagnosticStore delivered=store();delivered.saveChoice(true,true);JSONObject dr=auto(delivered,now);Fake df=new Fake();df.reply=new ReportTransport.Reply(200,reply("submitted"),0);new ReportEngine(delivered,df).tick(now);
        check(dr.getString("issue").equals(ISSUE)&&!dr.has("body"),"grouped canonical receipt URL retained without payload");
        DiagnosticStore race=store();race.saveChoice(true,true);JSONObject rr=auto(race,now);Fake rf=new Fake();rf.reply=new ReportTransport.Reply(202,reply("queued"),0);ReportEngine re=new ReportEngine(race,rf);re.tick(now);race.saveChoice(false,false);long deadline=rr.getLong("cancel_until");rf.reply=new ReportTransport.Reply(503,"{}",0);re.tick(now+10000);
        check(rr.getLong("cancel_until")==deadline,"repeated opt-out worker does not extend cancellation deadline");
        rf.reply=new ReportTransport.Reply(409,"{}",0);due(re,rr,now+20000);
        check(rr.getString("state").equals("stopped")&&!rr.has("body"),"claimed auto cannot be recalled; opt-out stops polling");
        DiagnosticStore pause=store();pause.saveChoice(true,true);JSONObject pr=auto(pause,now);Fake pf=new Fake();pf.reply=new ReportTransport.Reply(202,reply("queued"),0);ReportEngine pe=new ReportEngine(pause,pf);pe.automaticPaused=true;pe.tick(now);
        check(pf.methods.isEmpty()&&pf.capabilityCalls==0,"immediate UI opt-out blocks new POST before disk save");
        check(pe.next(now-1000)==now-1000,"clock rollback schedules immediate expiry instead of retaining content");
        for(String bad:new String[]{"{}","{\"schema_versions\":[\"2\"],\"automatic_reports\":true}","{\"schema_versions\":[2],\"automatic_reports\":\"true\"}"})check(!AutomaticPolicy.supported(new ReportTransport.Reply(200,bad,0)),"capability strict types");
    }
    private String uiSummary(android.view.accessibility.AccessibilityNodeInfo n) {
        if(n==null)return "null";
        StringBuilder out=new StringBuilder();
        if(n.getText()!=null)out.append(n.getText().toString().substring(0,Math.min(80,n.getText().length()))).append("|");
        for(int i=0;i<n.getChildCount();i++) {if(out.length()>400)break;out.append(uiSummary(n.getChild(i)));}
        return out.toString();
    }
    private android.view.accessibility.AccessibilityNodeInfo find(android.view.accessibility.AccessibilityNodeInfo n,String text) {
        if(n==null)return null;
        if(n.getText()!=null&&text.equalsIgnoreCase(n.getText().toString()))return n;
        for(int i=0;i<n.getChildCount();i++) {android.view.accessibility.AccessibilityNodeInfo f=find(n.getChild(i),text);if(f!=null)return f;}
        return null;
    }
    private android.view.accessibility.AccessibilityNodeInfo waitNode(String text)throws Exception {
        for(int i=0;i<60;i++){android.view.accessibility.AccessibilityNodeInfo n=null;for(android.view.accessibility.AccessibilityWindowInfo w:getUiAutomation().getWindows()){n=find(w.getRoot(),text);if(n!=null)return n;}n=find(getUiAutomation().getRootInActiveWindow(),text);if(n!=null)return n;Thread.sleep(100);}
        throw new AssertionError("UI missing: "+text);
    }
    private android.widget.Button button(android.view.View v,String label) {
        if(v instanceof android.widget.Button&&label.equals(((android.widget.Button)v).getText().toString()))return (android.widget.Button)v;
        if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){android.widget.Button b=button(g.getChildAt(i),label);if(b!=null)return b;}}
        return null;
    }
    private void press(SupportActivity a,String label){runOnMainSync(() -> {android.widget.Button b=button(a.getWindow().getDecorView(),label);if(b==null)throw new AssertionError("Missing button: "+label);b.performClick();});}
    private void uiTests()throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(info);
        Diagnostics d=Diagnostics.get(getTargetContext());
        Fake fake=new Fake();fake.offline=true;
        d.io.submit(() -> {try{d.store.queue=new JSONArray();d.store.save();d.store.choice=new JSONObject();d.store.saveChoice(true,false);d.engine=new ReportEngine(d.store,fake);}catch(Exception e){throw new RuntimeException(e);}}).get();
        SupportActivity activity=(SupportActivity)startActivitySync(new Intent(getTargetContext(),SupportActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitNode("Diagnostics choice");
        android.view.accessibility.AccessibilityNodeInfo checkbox=waitNode("Automatically send diagnostics");
        check(checkbox.isChecked(),"notice checkbox selected");
        check(d.io.submit(() -> d.store.queue.length()==0).get(),"no uploads before Continue");
        activity=(SupportActivity)startActivitySync(new Intent(getTargetContext(),SupportActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        waitNode("Diagnostics choice");
        checkbox=waitNode("Automatically send diagnostics");
        int notices=0;
        for(android.view.accessibility.AccessibilityWindowInfo window:getUiAutomation().getWindows())
            if(find(window.getRoot(),"Diagnostics choice")!=null)notices++;
        check(notices==1&&checkbox.isChecked(),"notice follows foreground activity without a hidden-owner lock");
        check(d.io.submit(() -> !d.store.noticed()&&d.store.queue.length()==0).get(),"paused unanswered notice is not consent");
        checkbox.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        waitNode("Continue").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        Thread.sleep(300);
        check(d.io.submit(() -> d.store.noticed()&&!d.store.automaticChoice()).get(),"Continue saves opt-out");
        check(!waitNode("Automatically send diagnostics").isChecked(),"Support reflects notice opt-out immediately");
        final SupportActivity support=activity;
        waitForIdleSync();
        Thread.sleep(500);
        check(d.io.submit(() -> {try{return new DiagnosticStore(sameDirectory(d.store)).noticed()&&!new DiagnosticStore(sameDirectory(d.store)).automaticChoice();}catch(Exception e){throw new RuntimeException(e);}}).get(),"UI opt-out survives reopening private store");
        Diagnostics.failure("projection","permission_denied");
        Thread.sleep(200);
        check(d.io.submit(() -> d.store.queue.length()==0).get()&&fake.methods.isEmpty(),"measured failure respects opt-out");
        check(d.io.submit(() -> {try{ReportSchema.validate(d.store.report("hud","unknown","unknown","unknown",System.currentTimeMillis()));return true;}catch(Exception bad){throw new RuntimeException(bad);}}).get(),"UI draft builds");
        press(support,"Send diagnostics");
        waitNode("Preview public diagnostics report");
        check(d.io.submit(() -> d.store.queue.length()==0).get(),"preview does not send");
        waitNode("Cancel").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        Thread.sleep(150);
        press(support,"Send diagnostics");waitNode("Send").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        Thread.sleep(400);
        check(d.io.submit(() -> d.store.queue.length()==1&&!d.store.automaticChoice()).get(),"manual send with auto off");
        String queued=d.io.submit(() -> d.store.queue.getJSONObject(0).getString("body")).get();
        check(ReportSchema.validate(queued).getString("project_id").equals("waze-for-byd"),"UI queues validated navigation report");
        check(!queued.contains("token")&&!queued.contains("SECRET"),"preview/export secret-free");
        IntentFilter filter=new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("application/json");
        Intent answer=new Intent().setData(android.net.Uri.parse("content://local.waze.diagnostics.tests.export/report"));
        ActivityMonitor monitor=addMonitor(filter,new ActivityResult(android.app.Activity.RESULT_OK,answer),true);
        press(support,"Export diagnostics");
        Thread.sleep(600);
        File exported=new File(getTargetContext().getFilesDir(),"export.json");
        check(monitor.getHits()==1&&exported.isFile(),"SAF export path");
        String exportedBody=DiagnosticStore.read(new android.util.AtomicFile(exported),262144);
        check(ReportSchema.validate(exportedBody).getString("project_id").equals("waze-for-byd")&&!exportedBody.contains("token"),"export contains report only");
        removeMonitor(monitor);
        waitNode("Automatically send diagnostics").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        Thread.sleep(200);
        check(d.io.submit(() -> d.store.automaticChoice()).get(),"Support enables auto after answered notice");
        Diagnostics.event("lifecycle","state","started");
        Diagnostics.event("guidance_send","outcome","sent","icon",2,"turn_meters",100,"route_meters",1000,"route_seconds",60,"reason","changed");
        Thread.sleep(200);
        check(d.io.submit(() -> d.store.queue.length()==1).get(),"ordinary successful sessions never enqueue auto");
        Diagnostics.failure("projection","permission_denied");Thread.sleep(300);
        check(d.io.submit(() -> d.store.queue.length()==2&&d.store.queue.getJSONObject(1).optBoolean("automatic")).get(),"real event hook admits measured failure");
        waitNode("Automatically send diagnostics").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        Thread.sleep(300);
        check(d.io.submit(() -> d.store.queue.length()==1&&!d.store.automaticChoice()&&!d.store.queue.getJSONObject(0).optBoolean("automatic")).get(),"Support opt-out preserves approved manual report");
        runOnMainSync(support::finish);
        // Cancel test-only jobs before exiting; this APK has no INTERNET permission as a second guard.
        d.io.submit(() -> {try{d.store.queue=new JSONArray();d.store.save();d.schedule();}catch(Exception e){throw new RuntimeException(e);}}).get();
    }

}
