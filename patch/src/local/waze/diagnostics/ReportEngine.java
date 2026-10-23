// SPDX-License-Identifier: GPL-3.0-only
package local.waze.diagnostics;

import org.json.*;
import java.util.Random;

/** Durable versioned state machine; network replies never become instructions or URLs to fetch. */
final class ReportEngine {
    final DiagnosticStore store;
    final ReportTransport transport;
    volatile boolean automaticPaused;
    private final Random random=new Random();
    ReportEngine(DiagnosticStore s,ReportTransport t){store=s;transport=t;}
    static boolean validId(String s){return s!=null&&s.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");}
    static boolean validIssue(String s){return s!=null&&s.matches("https://github\\.com/romanchukg-cloud/waze-for-byd/issues/[1-9][0-9]{0,18}");}
    static boolean active(JSONObject r){return !ArraysHolder.TERMINAL.contains(r.optString("state"));}
    private static final class ArraysHolder {
        static final java.util.Set<String> TERMINAL=new java.util.HashSet<>(java.util.Arrays.asList("submitted","cancelled","failed","stopped","expired"));
    }
    void cancel(String key,long now) throws Exception {
        JSONObject r=store.find(key);if(r==null)return;
        if(!r.has("body")||!r.has("token"))return;
        if(r.optInt("attempts")==0 && !r.has("id")){store.remove(key);return;}
        if("submitted".equals(r.optString("state"))||"delivering".equals(r.optString("state"))||"delivery_uncertain".equals(r.optString("state")))return;
        r.put("cancel",true).put("cancel_until",now+3600000).put("state","pending").put("next",now);store.save();
        // If a POST reply was lost, replay with its original body/key/token to obtain the receipt,
        // then DELETE. Removing this local record immediately could leave a live public submission.
    }
    long next(long now) throws Exception {
        long due=Long.MAX_VALUE;
        for(int i=0;i<store.queue.length();i++) {
            JSONObject r=store.queue.getJSONObject(i);
            if(active(r))due=Math.min(due,r.getLong("created")>now?now:Math.min(r.getLong("next"),r.optBoolean("cancel")?r.optLong("cancel_until",r.getLong("created")+DiagnosticStore.DAY):r.getLong("created")+DiagnosticStore.DAY));
        }
        return due;
    }
    void tick(long now) throws Exception {
        if(!store.automaticChoice()||!store.noticed())store.stopAutomatic(now);
        long tickStarted=System.nanoTime();
        for(int i=0;i<store.queue.length();i++) {
            JSONObject r=store.queue.getJSONObject(i);
            long current=now+(System.nanoTime()-tickStarted)/1000000;
            if(!active(r))continue;
            long age=current-r.getLong("created");
            if((!r.optBoolean("cancel") && (age<0||age>=DiagnosticStore.DAY)) ||
                (r.optBoolean("cancel") && current>=r.optLong("cancel_until",r.getLong("created")+DiagnosticStore.DAY))) {r.put("state","expired");clearAutomatic(r);store.save();continue;}
            if(r.getLong("next")>current)continue;
            step(r,current);
        }
    }
    private void step(JSONObject r,long now) throws Exception {
        String id=r.optString("id",null);
        boolean cancel=r.optBoolean("cancel");
        String method=id==null?"POST":cancel?"DELETE":"GET";
        String body=r.getString("body");
        try{ReportSchema.validate(body);}catch(JSONException invalid){r.put("state","stopped");store.save();return;}
        if(r.optBoolean("automatic")&&"POST".equals(method)) {
            if(automaticPaused){r.put("next",now+3600000);store.save();return;}
            long started=System.nanoTime();
            ReportTransport.Reply capability;
            try {capability=transport.capabilities();}
            catch(Exception unavailable){capability=new ReportTransport.Reply(503,"{}",0);}
            now+=(System.nanoTime()-started)/1000000;
            if(!AutomaticPolicy.supported(capability)) {
                r.put("next",now+Math.max(3600000,capability.retryMillis));store.save();return;
            }
            if(automaticPaused){r.put("next",now+3600000);store.save();return;}
            // Capabilities may have taken the report past its retention deadline.
            if(now<r.getLong("created")||now-r.getLong("created")>=DiagnosticStore.DAY) {
                r.put("state","expired");clearAutomatic(r);store.save();return;
            }
        }
        r.put("attempts",r.getInt("attempts")+1);
        // Persist BEFORE any bytes leave the process, including for a lost response.
        store.save();
        ReportTransport.Reply reply;
        long started=System.nanoTime();
        try {reply=transport.request(method,id,body,r.getString("key"),r.getString("token"));}
        catch(Exception network){retry(r,now+(System.nanoTime()-started)/1000000,0);return;}
        now+=(System.nanoTime()-started)/1000000;
        if(reply.code==429||reply.code==503||reply.code>=500) {retry(r,now,reply.retryMillis);return;}
        if("DELETE".equals(method)&&reply.code==409) {
            r.put("cancel",false).put("state",r.optBoolean("automatic")&&!store.automaticChoice()?"stopped":"delivering").put("next",now+10000);clearAutomatic(r);store.save();return;
        }
        if("DELETE".equals(method)&&reply.code==204) {
            r.put("state","cancelled");clearAutomatic(r);store.save();return;
        }
        if(reply.code!=200 && reply.code!=202) {r.put("state","stopped");clearAutomatic(r);store.save();return;}
        try {
            JSONObject response=new JSONObject(reply.body);
            String received=response.getString("report_id");
            if(!validId(received) || (id!=null&&!id.equals(received)))throw new JSONException("Invalid receipt");
            String state=response.getString("status");
            if(!java.util.Arrays.asList("queued","delivering","submitted","delivery_uncertain","failed","cancelled").contains(state))throw new JSONException("Unknown state");
            if("submitted".equals(state)) {
                if(reply.code!=200)throw new JSONException("Unconfirmed delivery");
                String url=response.getString("issue_url");
                if(!validIssue(url))throw new JSONException("Untrusted link");
                r.put("issue",url);
            }
            r.put("id",received).put("state",state);
            Object hint=response.opt("poll_after_seconds");
            long seconds=hint instanceof Integer||hint instanceof Long?((Number)hint).longValue():10;
            seconds=Math.max("delivery_uncertain".equals(state)?600:10,Math.min(3600,seconds));
            if(cancel && "queued".equals(state))r.put("next",now); // Immediately try DELETE next.
            else {r.put("next",now+Math.max(seconds*1000,reply.retryMillis));if(cancel)r.put("cancel",false);}
            clearAutomatic(r);store.save();
        } catch(JSONException invalid){r.put("state","stopped");clearAutomatic(r);store.save();}
    }
    private static void clearAutomatic(JSONObject r) {
        if(r.optBoolean("automatic")&&!active(r)){r.remove("body");r.remove("token");}
    }
    private void retry(JSONObject r,long now,long retryAfter) throws Exception {
        long[] delays={10000,30000,120000,600000,3600000};
        long delay=delays[Math.min(delays.length-1,Math.max(0,r.getInt("attempts")-1))];
        delay+=random.nextInt((int)(delay/5)+1);
        r.put("next",now+Math.max(delay,retryAfter));store.save();
    }
}
