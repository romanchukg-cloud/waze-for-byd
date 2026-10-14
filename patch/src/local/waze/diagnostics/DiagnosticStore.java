package local.waze.diagnostics;

import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

/** All calls run on the diagnostics executor. Files are private and excluded from backup. */
final class DiagnosticStore {
    static final long DAY=86400000L, RETENTION=600000L;
    private final AtomicFile eventsFile, queueFile, choiceFile;
    final Context context;
    JSONArray events=new JSONArray(), queue=new JSONArray();
    JSONObject choice=new JSONObject();
    boolean truncated;
    DiagnosticStore(Context c) throws Exception {
        context=c;
        File dir=new File(c.getNoBackupFilesDir(),"byd-diagnostics");
        if(!dir.isDirectory()&&!dir.mkdirs()) throw new IOException("Storage unavailable");
        eventsFile=new AtomicFile(new File(dir,"events.json"));
        queueFile=new AtomicFile(new File(dir,"queue.json"));
        choiceFile=new AtomicFile(new File(dir,"choice.json"));
        // Fail closed if an approved queue is corrupt; never silently lose reports.
        if(queueFile.getBaseFile().exists()) queue=new JSONArray(read(queueFile, 1100000));
        if(queue.length()>3) throw new IOException("Invalid queue");
        if(choiceFile.getBaseFile().exists()) choice=new JSONObject(read(choiceFile,4096));
        try { if(eventsFile.getBaseFile().exists()) events=new JSONArray(read(eventsFile,1048576)); }
        catch(Exception ignored) { events=new JSONArray(); truncated=true; }
        prune(System.currentTimeMillis());
    }
    static String read(AtomicFile f,int max) throws IOException {
        try(InputStream in=f.openRead(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096]; int n;
            while((n=in.read(b))!=-1) { if(out.size()+n>max) throw new IOException("Size limit"); out.write(b,0,n); }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static void write(AtomicFile f,String text) throws IOException {
        FileOutputStream out=null;
        try {out=f.startWrite();out.write(text.getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}
        catch(IOException e) {if(out!=null)f.failWrite(out);throw e;}
    }
    private void prune(long now) throws Exception {
        JSONArray keep=new JSONArray();
        for(int i=0;i<events.length();i++) {
            JSONObject e=events.getJSONObject(i); long at=e.getLong("at");
            if(at<=now && now-at<RETENTION) keep.put(e);
            else truncated=true;
        }
        events=keep;
        // Bounded even under extreme repeated callbacks; newest events survive.
        while(events.length()>2000 || events.toString().getBytes(StandardCharsets.UTF_8).length>1000000) {
            events.remove(0); truncated=true;
        }
    }
    void record(String type,JSONObject data,long now) throws Exception {
        ReportSchema.event(type,data);
        events.put(ReportSchema.obj("at",now,"type",type,"data",data));
        prune(now); write(eventsFile,events.toString());
    }
    String report(String category,String cluster,String hud,String stock,long now) throws Exception {
        return reportWindow(category,cluster,hud,stock,now,now-RETENTION,now,false);
    }
    String reportWindow(String category,String cluster,String hud,String stock,long now,long since,long until,boolean anchored) throws Exception {
        if(since>until||until>now||until-since>RETENTION)throw new JSONException("Invalid capture window");
        prune(now); write(eventsFile,events.toString());
        int from=0,to=events.length();
        while(from<to && events.getJSONObject(from).getLong("at")<since)from++;
        while(to>from && events.getJSONObject(to-1).getLong("at")>until)to--;
        int first=from;
        from=Math.max(from,to-ReportSchema.MAX_EVENTS);
        long start=anchored?since:from==to?until:events.getJSONObject(from).getLong("at");
        int duration=(int)Math.min(600,(until-start+999)/1000);
        JSONArray capture=new JSONArray();
        for(int i=from;i<to;i++) {
            JSONObject e=events.getJSONObject(i);
            capture.put(ReportSchema.obj("elapsed_ms",e.getLong("at")-start,"type",e.getString("type"),"data",e.getJSONObject("data")));
        }
        JSONObject r=ReportSchema.obj("schema_version",1,"project_id",ReportSchema.PROJECT,"consent","public_issue",
            "app",ReportSchema.obj("version","5.22.90.123","build",ReportSchema.BUILD),
            "device",ReportSchema.obj("android_api",Build.VERSION.SDK_INT,
                "android_version",ReportSchema.safeText(Build.VERSION.RELEASE,160),
                "manufacturer",ReportSchema.safeText(Build.MANUFACTURER,160),"model",ReportSchema.safeText(Build.MODEL,160),"firmware","unknown"),
            "problem",ReportSchema.obj("category",category,"cluster_guidance",cluster,"hud_guidance",hud,"stock_navigation_hud",stock),
            "settings",new JSONObject(),"components",new JSONArray(),
            "capture",ReportSchema.obj("duration_seconds",duration,"truncated",truncated || from>first || (anchored&&now-since>RETENTION)),"events",capture);
        String body=r.toString(); ReportSchema.validate(body); return body;
    }
    boolean noticed() {return choice.optInt("notice_version",0)>=2;}
    boolean automaticChoice() {return choice.optBoolean("automatic_choice",true);}
    void saveChoice(boolean automatic,boolean continued) throws Exception {
        choice.put("automatic_choice",automatic);
        if(continued) choice.put("notice_version",2);
        persistChoice();
        if(!automatic)stopAutomatic(System.currentTimeMillis());
    }
    void persistChoice() throws IOException {write(choiceFile,choice.toString());}
    void stopAutomatic(long now) throws Exception {
        for(int i=queue.length()-1;i>=0;i--) {
            JSONObject r=queue.getJSONObject(i);
            if(!r.optBoolean("automatic")||!ReportEngine.active(r))continue;
            if(!r.has("id")) {
                if(r.optInt("attempts")==0){queue.remove(i);continue;}
                // Never replay POST after opt-out: a lost acceptance cannot safely be recalled.
                r.put("state","stopped");r.remove("body");r.remove("token");
            } else if("queued".equals(r.optString("state"))||"pending".equals(r.optString("state"))) {
                if(!r.optBoolean("cancel"))r.put("cancel",true).put("cancel_until",now+3600000).put("next",now);
            } else {r.put("state","stopped");r.remove("body");r.remove("token");}
        }
        save();
    }
    JSONObject enqueue(String body,long now) throws Exception {
        JSONObject validated=ReportSchema.validate(body);
        boolean automatic="automatic".equals(validated.optString("report_source"));
        if(automatic) {
            if(!noticed()||!automaticChoice())throw new IOException("Automatic consent required");
            for(int i=0;i<queue.length();i++)if(queue.getJSONObject(i).optBoolean("automatic"))throw new IOException("Automatic slot full");
        }
        if(queue.length()>=3) throw new IOException("Queue full");
        byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);
        JSONObject r=ReportSchema.obj("key",UUID.randomUUID().toString(),
            "token",Base64.encodeToString(secret,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),
            "body",body,"automatic",automatic,"created",now,"next",now,"attempts",0,"state","pending","cancel",false);
        queue.put(r); save(); return r;
    }
    void save() throws IOException {write(queueFile,queue.toString());}
    JSONObject find(String key) throws JSONException {
        for(int i=0;i<queue.length();i++) if(key.equals(queue.getJSONObject(i).getString("key")))return queue.getJSONObject(i);
        return null;
    }
    void remove(String key) throws Exception {
        for(int i=0;i<queue.length();i++) if(key.equals(queue.getJSONObject(i).getString("key"))) {queue.remove(i);save();return;}
    }
}
