package local.waze.diagnostics;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import local.waze.cluster.ClusterStatus;

/** Framework-only English support screen; exports only validated report bodies through SAF. */
public final class SupportActivity extends Activity {
    private Diagnostics d;
    private LinearLayout layout, reports;
    private CheckBox automatic;
    private TextView status,clusterStatus;
    private Spinner category,cluster,hud,stock;
    private String exportBody;
    private boolean busy,updating,resumed;
    private long captureUntil;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){if(resumed){load();handler.postDelayed(this,5000);}}};
    private final Runnable clusterRefresh=new Runnable(){public void run(){if(resumed){clusterStatus.setText(ClusterStatus.describe(SupportActivity.this));handler.postDelayed(this,1000);}}};
    public void onCreate(Bundle saved) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(saved);
        d=Diagnostics.get(this);
        if(saved!=null){exportBody=saved.getString("export");captureUntil=saved.getLong("capture_until",0);}
        ScrollView scroll=new ScrollView(this);layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(16*getResources().getDisplayMetrics().density);layout.setPadding(pad,pad,pad,pad);scroll.addView(layout);setContentView(scroll);
        text("Support · Waze for BYD",24);
        text("Build "+ReportSchema.BUILD+" · Support and diagnostics\nTechnical reports go to diagnostics.localcharge.app. A limited summary may appear in public GitHub Issues for romanchukg-cloud/waze-for-byd. Queued does not mean sent, and a successful guidance call does not prove anything appeared on the HUD.",16);
        clusterSection();
        automatic=new CheckBox(this);automatic.setText("Automatically send diagnostics");layout.addView(automatic);
        text("After Continue, measured integration failures can be sent automatically when server support is verified, at most one new report per 24 hours. Successful sessions are not uploaded. Turning this off stops automatic retries, removes unsent automatic reports and requests cancellation of known queued reports. Published, delivering or unconfirmed accepted reports may not be recalled. Manual reports stay independent.",16);
        automatic.setOnCheckedChangeListener((v,on) -> {if(!updating && d!=null){d.choiceChanged(on);d.execute(() -> {d.store.saveChoice(on,false);d.schedule();},this::load,() -> message("Could not save diagnostics choice."));}});
        category=select("Problem category",new String[]{"HUD","Cluster map","Wheel buttons","Navigation","Other"});
        cluster=select("Cluster guidance observed",new String[]{"Unknown","Yes","No"});
        hud=select("HUD guidance observed",new String[]{"Unknown","Yes","No"});
        stock=select("Stock navigation HUD observed",new String[]{"Unknown","Yes","No"});
        text("These three observations are independent. Leave unknown unless you personally checked. Reports exclude routes, coordinates, road names, account information, tokens and screenshots. No freeform notes or raw logcat are sent.",16);
        button("Send diagnostics",() -> draft(false));
        button("Export diagnostics",() -> draft(true));
        button("Record diagnostic session (60 seconds)",() -> {captureUntil=System.currentTimeMillis()+60000;load();});
        status=text("Loading support status…",16);
        reports=new LinearLayout(this);reports.setOrientation(LinearLayout.VERTICAL);layout.addView(reports);
        button("Back",this::finish);
        if(d==null)message("Diagnostics storage is unavailable.");
    }
    private void clusterSection(){
        text("Instrument cluster",20);
        text("Cluster settings are in Instrument cluster, at the top right of the Waze map. The status below stays on this device and is never uploaded.",14);
        clusterStatus=text("Loading cluster status…",14);clusterStatus.setTextIsSelectable(true);
    }
    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setPadding(0,8,0,8);layout.addView(v);return v;}
    private void button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v -> action.run());layout.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private Spinner select(String label,String[] options){text(label,16);Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));layout.addView(s);return s;}
    private String value(Spinner s){return ((String)s.getSelectedItem()).toLowerCase(java.util.Locale.ROOT);}
    private void message(String s){if(!isFinishing()&&!isDestroyed())Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    protected void onResume(){super.onResume();resumed=true;refresh.run();clusterRefresh.run();}
    protected void onPause(){resumed=false;handler.removeCallbacks(refresh);handler.removeCallbacks(clusterRefresh);super.onPause();}
    protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("export",exportBody);b.putLong("capture_until",captureUntil);}
    void showAutomaticChoice(boolean selected){updating=true;automatic.setChecked(selected);updating=false;}
    private void load(){
        if(d==null||busy)return;
        d.execute(() -> {
            boolean selected=d.store.automaticChoice();JSONArray rows=new JSONArray();
            for(int i=0;i<d.store.queue.length();i++) {
                JSONObject r=d.store.queue.getJSONObject(i);
                rows.put(ReportSchema.obj("key",r.getString("key"),"state",r.getString("state"),"issue",r.optString("issue"),"cancel",r.optBoolean("cancel"),"automatic",r.optBoolean("automatic"),"export",r.has("body")));
            }
            d.schedule();
            d.main.post(() -> {if(!resumed||isDestroyed())return;showAutomaticChoice(selected);showReports(rows);});
        },() -> {},() -> {if(resumed)status.setText("Diagnostics storage is unavailable. No report was sent.");});
    }
    private void draft(boolean export){
        if(d==null||busy)return;
        if(captureUntil>System.currentTimeMillis()){message("Recording technical events. Preview will be available after 60 seconds.");return;}
        if(captureUntil>0 && System.currentTimeMillis()-captureUntil>=600000){captureUntil=0;message("Recorded session expired. Record again or send recent diagnostics.");return;}
        busy=true;
        String ca=ReportSchema.CATEGORIES[Math.max(0,Math.min(4,category.getSelectedItemPosition()))],cl=value(cluster),hu=value(hud),st=value(stock);
        d.execute(() -> {
            long now=System.currentTimeMillis();
            String body=captureUntil==0?d.store.report(ca,cl,hu,st,now):d.store.reportWindow(ca,cl,hu,st,now,captureUntil-60000,captureUntil,true);
            d.main.post(() -> {busy=false;if(isFinishing()||isDestroyed())return;if(export)export(body);else preview(body);});
        },() -> {},() -> {busy=false;message("Could not build a validated report. Nothing was sent.");});
    }
    private void preview(String body){
        ScrollView scroll=new ScrollView(this);TextView preview=new TextView(this);preview.setTextIsSelectable(true);
        String pretty;try{pretty=new JSONObject(body).toString(2);}catch(Exception ignored){return;}
        preview.setText("Pressing Send uploads this exact report to diagnostics.localcharge.app and authorizes publication of a limited summary in public GitHub Issues. No GitHub account is required. A queued report can be cancelled only before delivery is claimed.\n\n"+pretty);preview.setPadding(24,16,24,16);scroll.addView(preview);
        new AlertDialog.Builder(this).setTitle("Preview public diagnostics report").setView(scroll)
            .setNegativeButton("Cancel",null).setNeutralButton("Export",(x,y) -> export(body))
            .setPositiveButton("Send",(x,y) -> {
                if(busy)return;busy=true;
                d.execute(() -> {d.store.enqueue(body,System.currentTimeMillis());d.schedule();},
                    () -> {busy=false;message("Pending: report saved for delivery.");load();},
                    () -> {busy=false;message("Report not added. The queue may be full (three reports) or storage unavailable. Remove a local report or export.");});
            }).show();
    }
    private void showReports(JSONArray rows){
        reports.removeAllViews();
        long remaining=captureUntil-System.currentTimeMillis();
        status.setText((!d.schedulingAvailable?"Background scheduling is unavailable. Reopen Support or restart the app to retry.\n":"")+(remaining>0?"Recording technical session: "+((remaining+999)/1000)+" seconds remaining.\n":"")+"Reports: "+rows.length()+" / 3. Retries stop after 24 hours. Export remains available.");
        for(int i=0;i<rows.length();i++)try {
            JSONObject r=rows.getJSONObject(i);String key=r.getString("key"),state=r.getString("state"),url=r.getString("issue");
            TextView label=new TextView(this);
            String readable;
            switch(state){case "pending":readable="Pending — waiting for server acceptance";break;case "queued":readable="Queued — server accepted; GitHub delivery pending";break;case "submitted":readable="Report sent — GitHub issue confirmed";break;case "delivering":readable="Delivering — cancellation unavailable";break;case "delivery_uncertain":readable="Delivery uncertain — server reconciliation required";break;case "expired":readable="Retry period ended — delivery may need checking";break;case "stopped":readable="Stopped — validation, project or receipt error; export available";break;default:readable=state;}
            label.setText((r.getBoolean("automatic")?"Automatic · ":"Manual · ")+key.substring(0,8)+": "+readable+(r.getBoolean("cancel")?" · Cancellation requested":""));reports.addView(label);
            if(ReportEngine.validIssue(url))rowButton("Open GitHub issue",() -> {try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){message("No browser is available.");}});
            if(r.getBoolean("export"))rowButton("Export this report",() -> d.execute(() -> {JSONObject item=d.store.find(key);if(item!=null){String body=item.getString("body");ReportSchema.validate(body);d.main.post(() -> export(body));}},() -> {},() -> message("Export unavailable.")));
            if(r.getBoolean("export")&&!state.equals("submitted")&&!state.equals("delivering")&&!state.equals("cancelled")&&!state.equals("delivery_uncertain"))rowButton("Cancel pending report",() -> {
                new AlertDialog.Builder(this).setTitle("Cancel pending report?")
                    .setMessage("If acceptance is uncertain, the original request must be checked before cancellation. Reports already being delivered or published cannot be recalled.")
                    .setNegativeButton("Keep",null).setPositiveButton("Request cancellation",(a,b) -> d.execute(() -> {d.engine.cancel(key,System.currentTimeMillis());d.schedule();},this::load,() -> message("Cancellation could not be saved."))).show();
            });
            rowButton("Remove local copy",() -> new AlertDialog.Builder(this).setTitle("Remove local copy?")
                .setMessage("This removes the saved report and its receipt access from this device. It does not cancel server delivery or remove a public issue. Use Cancel pending report first when possible.")
                .setNegativeButton("Keep",null).setPositiveButton("Remove local copy",(a,b) -> d.execute(() -> {d.store.remove(key);d.schedule();},this::load,() -> message("Could not remove local copy."))).show());
        }catch(Exception ignored){}
    }
    private void rowButton(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v -> action.run());reports.addView(b);}
    private void export(String body){
        if(isFinishing()||isDestroyed())return;
        try{ReportSchema.validate(body);exportBody=body;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"waze-for-byd-diagnostics.json"),47);}
        catch(Exception e){message("No document picker is available. Export requires an Android document provider.");}
    }
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=47||result!=RESULT_OK||data==null||data.getData()==null||exportBody==null)return;
        final String body=exportBody;exportBody=null;final Uri uri=data.getData();
        d.execute(() -> {ReportSchema.validate(body);try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException();out.write(body.getBytes(StandardCharsets.UTF_8));}},() -> message("Sanitized diagnostics exported."),() -> message("Export failed."));
    }
}
