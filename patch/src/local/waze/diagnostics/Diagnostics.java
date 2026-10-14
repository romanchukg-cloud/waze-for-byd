package local.waze.diagnostics;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional diagnostics never throw into navigation. All disk/network work is serialized off-main. */
public final class Diagnostics {
    static final int JOB_ID=0x575A4404;
    private static Diagnostics instance;
    final Application app;
    final ExecutorService io=Executors.newSingleThreadExecutor(r -> new Thread(r,"WazeSupport"));
    final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicInteger eventsPending=new AtomicInteger();
    DiagnosticStore store; ReportEngine engine;
    private boolean showingNotice;
    private Activity foreground,noticeOwner;
    private AlertDialog noticeDialog;
    private long scheduledDue=Long.MIN_VALUE;
    volatile boolean schedulingAvailable=true;
    interface Task {void run() throws Exception;}
    interface Value<T> {void accept(T value);}
    private Diagnostics(Application application) {
        app=application;
        io.execute(() -> {
            try {store=new DiagnosticStore(app);engine=new ReportEngine(store,new ReportTransport.Https());schedule();}
            catch(Exception ignored){store=null;engine=null;}
        });
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a,Bundle b) {
                if("com.waze.settings.SettingsMainActivity".equals(a.getClass().getName()))a.getWindow().getDecorView().post(() -> attachSupport(a));
            }
            public void onActivityResumed(Activity a) {
                foreground=a;
                if("com.waze.MainActivity".equals(a.getClass().getName()) || "com.waze.FreeMapAppActivity".equals(a.getClass().getName()) || a instanceof SupportActivity)notice(a);
            }
            public void onActivityStarted(Activity a) { }
            public void onActivityPaused(Activity a) {
                if(foreground==a)foreground=null;
                if(noticeOwner==a && noticeDialog!=null)noticeDialog.dismiss();
            }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a,Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }
    public static synchronized void install(Application app) {
        try {if(instance==null&&app.getPackageName().equals(Application.getProcessName()))instance=new Diagnostics(app);}
        catch(Exception ignored) { }
        event("lifecycle","state","started");
    }
    static Diagnostics get(Context c) {
        if(instance==null)install((Application)c.getApplicationContext());
        return instance;
    }
    static void open(Activity a){a.startActivity(new Intent(a,SupportActivity.class));}
    private void attachSupport(Activity a) {
        if(a.isFinishing()||a.isDestroyed())return;
        ViewGroup content=a.findViewById(android.R.id.content);
        if(content==null||content.findViewWithTag("byd-support-entry")!=null)return;
        FrameLayout original=new FrameLayout(a);
        while(content.getChildCount()>0) {
            View child=content.getChildAt(0);ViewGroup.LayoutParams params=child.getLayoutParams();
            content.removeView(child);original.addView(child,params);
        }
        LinearLayout wrapper=new LinearLayout(a);wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setTag("byd-support-entry");
        wrapper.addView(original,new LinearLayout.LayoutParams(-1,0,1));
        Button support=new Button(a);support.setText("Support · Waze for BYD");support.setAllCaps(false);
        support.setOnClickListener(v -> open(a));wrapper.addView(support,new LinearLayout.LayoutParams(-1,-2));
        content.addView(wrapper,new ViewGroup.LayoutParams(-1,-1));
    }
    void notice(Activity a) {
        io.execute(() -> {
            if(store==null)return;
            boolean needed=!store.noticed(),selected=store.automaticChoice();
            main.post(() -> {
                if(!needed||showingNotice||foreground!=a||a.isFinishing()||a.isDestroyed())return;
                showingNotice=true;
                LinearLayout layout=new LinearLayout(a);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,16,24,16);
                TextView text=new TextView(a);text.setText("Help improve Waze for BYD by sending technical reports when an integration error occurs. Reports go to diagnostics.localcharge.app and a limited summary may appear in public GitHub Issues for romanchukg-cloud/waze-for-byd. Reports exclude routes, coordinates, account information and road names. You can turn this off in Support. Technical metadata can identify an unusual configuration.\n\nAutomatic sending starts after Continue with this choice and verification of server support. Ordinary successful sessions are not sent. Manual reports always require Preview and Send.");
                layout.addView(text);CheckBox automatic=new CheckBox(a);automatic.setText("Automatically send diagnostics");automatic.setChecked(selected);layout.addView(automatic);
                AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Diagnostics choice").setView(layout)
                    .setPositiveButton("Continue",(d,w) -> {
                        boolean selectedChoice=automatic.isChecked();
                        choiceChanged(selectedChoice);
                        execute(() -> {store.saveChoice(selectedChoice,true);schedule();},() -> {
                            if(a instanceof SupportActivity&&!a.isFinishing()&&!a.isDestroyed())((SupportActivity)a).showAutomaticChoice(selectedChoice);
                        },() -> Toast.makeText(a,"Could not save your diagnostics choice. Open Support to try again.",Toast.LENGTH_LONG).show());
                    })
                    .setNegativeButton("Later",null).create();
                noticeOwner=a;noticeDialog=dialog;
                dialog.setOnDismissListener(d -> {if(noticeDialog==dialog){showingNotice=false;noticeOwner=null;noticeDialog=null;}});dialog.show();
            });
        });
    }
    void execute(Task task,Runnable done) {execute(task,done,() -> {});}
    void execute(Task task,Runnable done,Runnable failed) {
        io.execute(() -> {try {if(store==null)throw new IllegalStateException();task.run();main.post(done);}catch(Exception ignored){android.util.Log.w("WazeSupport","Operation failed ("+ignored.getClass().getSimpleName()+")");main.post(failed);}});
    }
    public static void event(String type,Object... pairs) {
        Diagnostics d=instance;if(d==null)return;
        if(d.eventsPending.incrementAndGet()>64){d.eventsPending.decrementAndGet();return;}
        final long at=System.currentTimeMillis();
        final JSONObject data;
        try {data=ReportSchema.obj(pairs);ReportSchema.event(type,data);}catch(Exception invalid){d.eventsPending.decrementAndGet();return;}
        d.io.execute(() -> {try {if(d.store!=null){
            d.store.record(type,data,at);
            if("failure".equals(type)&&AutomaticPolicy.failure(d.store,data.getString("stage"),data.getString("code"),at)!=null)d.schedule();
        }}catch(Exception ignored){}finally{d.eventsPending.decrementAndGet();}});
    }
    public static void failure(String stage,String code){event("failure","stage",stage,"code",code);}
    void choiceChanged(boolean selected){if(engine!=null)engine.automaticPaused=!selected;}
    void schedule() {
      try {
        JobScheduler scheduler=app.getSystemService(JobScheduler.class);
        long now=System.currentTimeMillis(),due=engine.next(now);
        if(due==Long.MAX_VALUE){scheduler.cancel(JOB_ID);scheduledDue=due;schedulingAvailable=true;return;}
        if(due==scheduledDue && scheduler.getPendingJob(JOB_ID)!=null)return;
        JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(app,DiagnosticsJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setMinimumLatency(Math.max(1000,Math.min(DiagnosticStore.DAY,due-now))).build();
        // Failure leaves the private queue intact; opening Support or restarting retries scheduling.
        schedulingAvailable=scheduler.schedule(job)==JobScheduler.RESULT_SUCCESS;
        if(schedulingAvailable)scheduledDue=due;
      }catch(Exception unavailable){schedulingAvailable=false;}
    }
    void work(Runnable finished) {
        io.execute(() -> {try{if(engine!=null)engine.tick(System.currentTimeMillis());}catch(Exception ignored){}
            main.post(() -> {finished.run();io.execute(() -> {try{if(engine!=null)schedule();}catch(Exception ignored){}});});
        });
    }
}
