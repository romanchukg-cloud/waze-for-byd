package local.waze.diagnostics;

import android.os.SystemClock;
import android.provider.Settings;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Local admission only. Server computes its own grouping; fingerprints contain no identity. */
final class AutomaticPolicy {
    static String fingerprint(String stage,String code) throws Exception {
        ReportSchema.event("failure",ReportSchema.obj("stage",stage,"code",code));
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(
            (ReportSchema.PROJECT+"\n"+ReportSchema.BUILD+"\n"+stage+"\n"+code).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    static boolean allowed(JSONObject choice,long now,long elapsed,int boot) {
        if(choice.optInt("notice_version",0)<2||!choice.optBoolean("automatic_choice",true))return false;
        if(!choice.has("last_auto"))return true;
        long last=choice.optLong("last_auto",Long.MAX_VALUE);
        if(now<last||now-last<DiagnosticStore.DAY)return false;
        // Wall-clock fast-forward cannot reset the cap within the same boot.
        if(boot>=0&&boot==choice.optInt("auto_boot",-1)) {
            long previous=choice.optLong("auto_elapsed",Long.MAX_VALUE);
            if(elapsed<previous||elapsed-previous<DiagnosticStore.DAY)return false;
        }
        return true;
    }
    static JSONObject failure(DiagnosticStore store,String stage,String code,long now) throws Exception {
        if("upload".equals(stage))return null;
        String fingerprint=fingerprint(stage,code);
        long elapsed=SystemClock.elapsedRealtime();
        int boot=Settings.Global.getInt(store.context.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        if(!allowed(store.choice,now,elapsed,boot))return null;
        for(int i=store.queue.length()-1;i>=0;i--) {
            JSONObject r=store.queue.getJSONObject(i);
            if(!r.optBoolean("automatic"))continue;
            if(ReportEngine.active(r))return null;
            store.queue.remove(i); // Only retire finished automatic receipts; manual copies remain.
        }
        if(store.queue.length()>=3)return null;
        String category="projection".equals(stage)?"cluster_map":"wheel".equals(stage)?"wheel_buttons":"navigation";
        JSONObject report=new JSONObject(store.report(category,"unknown","unknown","unknown",now));
        report.put("schema_version",2).put("report_source","automatic");
        String body=report.toString();ReportSchema.validate(body);
        // Persist admission first. A crash may lose one report, but cannot create a second one.
        store.choice.put("last_auto",now).put("auto_elapsed",elapsed).put("auto_boot",boot).put("auto_fingerprint",fingerprint);
        store.persistChoice();
        return store.enqueue(body,now);
    }
    static boolean supported(ReportTransport.Reply reply) {
        try {
            if(reply.code!=200)return false;
            JSONObject c=new JSONObject(reply.body);
            if(!Boolean.TRUE.equals(c.opt("automatic_reports"))||!Boolean.TRUE.equals(c.opt("intake_enabled"))
                ||!Boolean.TRUE.equals(c.opt("delivery_enabled"))
                ||!"project_build_failure_utc_day".equals(c.opt("automatic_grouping")))return false;
            JSONArray versions=c.getJSONArray("schema_versions");
            for(int i=0;i<versions.length();i++)if(versions.get(i) instanceof Integer&&versions.getInt(i)==2)return true;
        }catch(Exception invalid) { }
        return false;
    }
}
