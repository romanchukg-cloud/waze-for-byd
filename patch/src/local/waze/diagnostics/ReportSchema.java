package local.waze.diagnostics;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed navigation-profile schema. Reject extras rather than upload arbitrary data. */
public final class ReportSchema {
    public static final String PROJECT = "waze-for-byd", BUILD = "byd.test.6";
    public static final int MAX_BYTES = 262144, MAX_EVENTS = 500;
    public static final String[] CATEGORIES = {"hud", "cluster_map", "wheel_buttons", "navigation", "other"};
    private static final String[] OBSERVED = {"yes", "no", "unknown"};
    private ReportSchema() { }
    public static JSONObject obj(Object... pairs) throws JSONException {
        JSONObject o = new JSONObject();
        for (int i=0;i<pairs.length;i+=2) o.put((String)pairs[i], pairs[i+1]);
        return o;
    }
    static void keys(JSONObject o, String... names) throws JSONException {
        Set<String> allowed = new HashSet<>(Arrays.asList(names));
        Iterator<String> it = o.keys();
        while (it.hasNext()) if (!allowed.contains(it.next())) throw new JSONException("Unexpected field");
        for (String n : names) if (!o.has(n)) throw new JSONException("Missing field");
    }
    static String choice(JSONObject o, String key, String... choices) throws JSONException {
        Object v = o.get(key);
        if (!(v instanceof String) || !Arrays.asList(choices).contains(v)) throw new JSONException("Invalid enum");
        return (String)v;
    }
    static long number(JSONObject o, String k, long lo, long hi) throws JSONException {
        Object v=o.get(k);
        if (!(v instanceof Integer || v instanceof Long)) throw new JSONException("Expected integer");
        long n=((Number)v).longValue();
        if(n<lo || n>hi) throw new JSONException("Out of range");
        return n;
    }
    static void bool(JSONObject o, String k) throws JSONException {
        if (!(o.get(k) instanceof Boolean)) throw new JSONException("Expected boolean");
    }
    static void text(JSONObject o, String key, int limit) throws JSONException {
        Object v=o.get(key);
        if (!(v instanceof String)) throw new JSONException("Expected text");
        String s=(String)v;
        if(s.isEmpty() || s.length()>limit || !s.matches("[a-zA-Z0-9 ._()+/-]+")) throw new JSONException("Unsafe text");
    }
    public static String safeText(String s, int max) {
        if (s == null) return "unknown";
        s=s.replaceAll("[^a-zA-Z0-9 ._()+/-]", "_").trim();
        return s.isEmpty()?"unknown":s.substring(0,Math.min(max,s.length()));
    }
    public static void event(String type, JSONObject d) throws JSONException {
        switch(type) {
            case "lifecycle": keys(d,"state"); choice(d,"state","started","stopped"); break;
            case "navigation_source":
                keys(d,"mode","providers","age_ms"); choice(d,"mode","OFF","GUIDING","CRUISING","UNKNOWN");
                number(d,"providers",0,100); number(d,"age_ms",0,86400000); break;
            case "guidance_send":
                keys(d,"outcome","icon","turn_meters","route_meters","route_seconds","reason");
                choice(d,"outcome","sent","failed"); choice(d,"reason","changed","keepalive","end");
                number(d,"icon",-1,100); number(d,"turn_meters",-1,1000000);
                number(d,"route_meters",-1,100000000); number(d,"route_seconds",-1,31536000); break;
            case "projection":
                keys(d,"state","display_id","width","height"); choice(d,"state","created","closed","failed");
                number(d,"display_id",0,1000); number(d,"width",0,16384); number(d,"height",0,16384); break;
            case "wheel_key":
                keys(d,"action","key_code","scan_code","consumed"); choice(d,"action","down","up");
                number(d,"key_code",0,65535); number(d,"scan_code",0,65535); bool(d,"consumed"); break;
            case "failure":
                keys(d,"stage","code"); choice(d,"stage","source","adapter","projection","wheel","upload");
                choice(d,"code","unavailable","permission_denied","schema_mismatch","timeout","connection_lost","internal_error"); break;
            default: throw new JSONException("Unsupported navigation event");
        }
    }
    public static JSONObject validate(String body) throws JSONException {
        if(body.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw new JSONException("Report too large");
        JSONObject r=new JSONObject(body);
        // Vehicle/freeform/component packages are deliberately not collected by this client.
        long version=number(r,"schema_version",1,2);
        if(version==2) {
            keys(r,"schema_version","report_source","project_id","consent","app","device","problem","settings","components","capture","events");
            choice(r,"report_source","manual","automatic");
        } else keys(r,"schema_version","project_id","consent","app","device","problem","settings","components","capture","events");
        choice(r,"project_id",PROJECT); choice(r,"consent","public_issue");
        JSONObject a=r.getJSONObject("app"); keys(a,"version","build"); text(a,"version",80); text(a,"build",80);
        JSONObject d=r.getJSONObject("device"); keys(d,"android_api","android_version","manufacturer","model","firmware");
        number(d,"android_api",1,100);
        for(String k:new String[]{"android_version","manufacturer","model","firmware"}) text(d,k,160);
        JSONObject p=r.getJSONObject("problem"); keys(p,"category","cluster_guidance","hud_guidance","stock_navigation_hud");
        choice(p,"category",CATEGORIES);
        for(String k:new String[]{"cluster_guidance","hud_guidance","stock_navigation_hud"}) choice(p,k,OBSERVED);
        JSONObject s=r.getJSONObject("settings");
        Iterator<String> it=s.keys();
        while(it.hasNext()) {String k=it.next(); if(!k.equals("map_enabled")&&!k.equals("guidance_enabled")) throw new JSONException("Unsupported setting"); bool(s,k);}
        if(r.getJSONArray("components").length()!=0) throw new JSONException("Components not registered");
        JSONObject c=r.getJSONObject("capture"); keys(c,"duration_seconds","truncated");
        long duration=number(c,"duration_seconds",0,600); bool(c,"truncated");
        JSONArray events=r.getJSONArray("events"); if(events.length()>MAX_EVENTS) throw new JSONException("Too many events");
        boolean failure=false;
        for(int i=0;i<events.length();i++) {
            JSONObject e=events.getJSONObject(i); keys(e,"elapsed_ms","type","data");
            number(e,"elapsed_ms",0,duration*1000); event(e.getString("type"),e.getJSONObject("data"));
            if("failure".equals(e.getString("type"))&&!"upload".equals(e.getJSONObject("data").getString("stage")))failure=true;
        }
        if(version==2&&"automatic".equals(r.getString("report_source"))&&!failure)throw new JSONException("Automatic failure required");
        return r;
    }
}
