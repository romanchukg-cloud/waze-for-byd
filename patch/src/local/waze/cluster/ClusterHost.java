package local.waze.cluster;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.location.Location;
import android.os.*;
import android.util.Log;
import android.view.*;
import androidx.car.app.*;
import local.waze.diagnostics.Diagnostics;
import androidx.car.app.navigation.INavigationHost;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.Trip;
import androidx.car.app.serialization.Bundleable;

/** Test host: uses the app's own Car App library; no duplicate AndroidX classes. */
public final class ClusterHost implements DisplayManager.DisplayListener {
    private static final String TAG = "WazeClusterTest";
    private static ClusterHost instance;
    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DisplayManager displays;
    private final BydGuidanceBridge guidance;
    private Presentation presentation;
    private SurfaceView view;
    private ICarApp car;
    private IAppManager manager;
    private ISurfaceCallback callback;
    private Intent sessionIntent;
    private ServiceConnection connection;
    private int generation;
    private boolean ready, templatePending, templateDirty, navigationEnded;
    private long lastTripAt = -GuidanceSession.STALE_MS;
    private long lastStateAt = -GuidanceSession.STALE_MS;
    private WazeClusterState stateReader;
    private SurfaceContainer delivered;
    private final Runnable refresh = () -> requestTemplate();
    private final Runnable retry = () -> reconcile();
    private interface Result { void accept(Bundleable value) throws Exception; }
    private interface Work { void run() throws Exception; }

    public static synchronized void install(Application app) {
        try {
            if (instance != null || !app.getPackageName().equals(Application.getProcessName())) return;
            Diagnostics.install(app);
            instance = new ClusterHost(app);
            instance.main.postDelayed(instance.retry, 3000);
        } catch (Throwable e) { Log.e(TAG, "install", e); }
    }
    public static void onClusterState(Object state) {
        // Called from a pinned observer hook after the immutable state's fields
        // are initialized. Never throw back into Waze's navigation pipeline.
        try {
            ClusterHost host = instance;
            if (host == null) return;
            final int expected = host.generation;
            final long receivedAt = SystemClock.elapsedRealtime();
            host.main.post(() -> {
                if (expected != host.generation || host.connection == null
                        || SystemClock.elapsedRealtime() - receivedAt >= GuidanceSession.STALE_MS) return;
                try {
                    if (host.stateReader == null) host.stateReader = new WazeClusterState();
                    GuidanceFrame frame = host.stateReader.frame(state, host.app, System.currentTimeMillis());
                    host.lastStateAt = receivedAt;
                    Diagnostics.event("navigation_source", "mode", frame == null ? "UNKNOWN" : "GUIDING", "providers", 1, "age_ms", 0);
                    host.guidance.update(frame);
                    Log.i(TAG, "cluster state guidance " + (frame == null ? "inactive/loading" : "available"));
                } catch (Throwable e) { host.guidance.end(); Diagnostics.failure("source", "schema_mismatch"); Log.e(TAG, "cluster state observer", e); }
            });
        } catch (Throwable e) { Log.e(TAG, "cluster state dispatch", e); }
    }
    private ClusterHost(Application app) {
        this.app = app;
        guidance = new BydGuidanceBridge(app, main);
        BroadcastReceiver shutdown = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) { Diagnostics.event("lifecycle", "state", "stopped"); close(); }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(shutdown, new IntentFilter(Intent.ACTION_SHUTDOWN), Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(shutdown, new IntentFilter(Intent.ACTION_SHUTDOWN));
        displays = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        displays.registerDisplayListener(this, main);
        Log.i(TAG, "installed test.4; stock BYD guidance bridge enabled");
    }
    private void safely(String stage, Work work) {
        try { work.run(); } catch (Throwable e) { Diagnostics.failure(stage.startsWith("surface") || stage.startsWith("area") ? "projection" : "adapter", "internal_error"); Log.e(TAG, stage, e); }
    }
    private IOnDoneCallback done(String stage, Result next) {
        final int expected = generation;
        return new IOnDoneCallback.Stub() {
            public int getInterfaceVersion() { return 1; }
            public void onSuccess(Bundleable value) {
                main.post(() -> { if (expected == generation) safely(stage, () -> {
                    Log.i(TAG, stage + " OK");
                    if (next != null) {
                        try { next.accept(value); } catch (Throwable e) {
                            Log.e(TAG, stage + " continuation", e);
                            if (!stage.startsWith("surface") && !stage.startsWith("area")) resetAndRetry();
                        }
                    }
                }); });
            }
            public void onFailure(Bundleable value) {
                main.post(() -> { if (expected == generation) {
                    Diagnostics.failure(stage.startsWith("surface") || stage.startsWith("area") ? "projection" : "adapter", "internal_error");
                    safely(stage, () -> Log.e(TAG, stage + " failed: " + (value == null ? "null" : value.get())));
                    if ("template".equals(stage)) templatePending = false;
                    else if (!stage.startsWith("surface") && !stage.startsWith("area")) resetAndRetry();
                } });
            }
        };
    }
    private void reconcile() {
        main.removeCallbacks(retry);
        // Guidance must not depend on the map display or overlay permission.
        if (connection == null) {
            try { bind(); } catch (Throwable e) { Log.e(TAG, "bind", e); resetAndRetry(); return; }
        }
        Display target = null;
        for (Display d : displays.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.getName().contains("XDJAScreenProjection")) {
                target = d; break;
            }
        }
        if (presentation != null && (target == null || presentation.getDisplay().getDisplayId() != target.getDisplayId())) dismissMap();
        if (target == null) { Log.i(TAG, "waiting for XDJAScreenProjection display"); main.postDelayed(retry, 10000); return; }
        if (presentation != null) return;
        try {
            final Display selected = target;
            presentation = new Presentation(app, selected) {
                protected void onCreate(Bundle state) {
                    super.onCreate(state);
                    getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                    view = new SurfaceView(getContext());
                    view.getHolder().addCallback(new SurfaceHolder.Callback() {
                        public void surfaceCreated(SurfaceHolder h) { sendSurface(); }
                        public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { sendSurface(); }
                        public void surfaceDestroyed(SurfaceHolder h) { destroySurface(); }
                    });
                    setContentView(view);
                }
            };
            presentation.show();
            Log.i(TAG, "display " + selected.getDisplayId() + " " + selected.getName());
            sendSurface();
        } catch (Throwable e) {
            Diagnostics.failure("projection", e instanceof SecurityException ? "permission_denied" : "internal_error");
            Log.e(TAG, "show map; guidance session remains connected", e);
            dismissMap();
            main.postDelayed(retry, 10000);
        }
    }
    private void bind() {
        final int expected = ++generation;
        sessionIntent = new Intent("androidx.car.app.CarAppService").setComponent(new ComponentName(app, "com.waze.car_lib.WazeCarAppService"));
        SessionInfoIntentEncoder.encode(new SessionInfo(1, "cluster"), sessionIntent);
        connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (expected != generation) return;
                car = ICarApp.Stub.asInterface(binder);
                safely("connect", () -> car.getAppInfo(done("appInfo", value -> {
                    AppInfo info = (AppInfo) value.get();
                    int api = Math.min(7, info.getLatestCarAppApiLevel());
                    if (api < info.getMinCarAppApiLevel()) throw new IllegalStateException("No common Car API");
                    Log.i(TAG, "API " + api);
                    car.onHandshakeCompleted(Bundleable.create(new HandshakeInfo(app.getPackageName(), api)), done("handshake", ignored -> create()));
                })));
            }
            public void onServiceDisconnected(ComponentName n) { if (expected == generation) resetAndRetry(); }
            public void onBindingDied(ComponentName n) { if (expected == generation) resetAndRetry(); }
            public void onNullBinding(ComponentName n) { if (expected == generation) resetAndRetry(); }
        };
        if (!app.bindService(sessionIntent, connection, Context.BIND_AUTO_CREATE)) throw new IllegalStateException("bindService returned false");
    }
    private void create() throws Exception {
        Configuration config = new Configuration(app.getResources().getConfiguration());
        car.onAppCreate(new CarHost(), sessionIntent, config, done("create", value ->
            car.onAppStart(done("start", started -> car.onAppResume(done("resume", resumed -> {
                ready = true;
                sendSurface();
                car.getManager("app", done("manager", result -> {
                    Object object = result.get();
                    manager = object instanceof IAppManager ? (IAppManager)object : IAppManager.Stub.asInterface((IBinder)object);
                    templateDirty = true;
                    requestTemplate();
                }));
            }))))));
    }
    private void requestTemplate() {
        if (!ready || manager == null || templatePending) return;
        templatePending = true;
        templateDirty = false;
        final long requestedAt = SystemClock.elapsedRealtime();
        safely("getTemplate", () -> manager.getTemplate(done("template", result -> {
            templatePending = false;
            Object template = result.get();
            Log.i(TAG, "template: " + template);
            if (!navigationEnded && SystemClock.elapsedRealtime() - lastTripAt >= GuidanceSession.STALE_MS
                    && SystemClock.elapsedRealtime() - lastStateAt >= GuidanceSession.STALE_MS) {
                guidance.update(template instanceof NavigationTemplate
                        && SystemClock.elapsedRealtime() - requestedAt < GuidanceSession.STALE_MS
                        ? WazeGuidance.fromTemplate((NavigationTemplate) template, System.currentTimeMillis()) : null);
            }
            sendSurface();
            if (templateDirty) { main.removeCallbacks(refresh); main.postDelayed(refresh, 100); }
        })));
    }
    private void sendSurface() {
        if (!ready || callback == null || view == null || !view.getHolder().getSurface().isValid() || view.getWidth() == 0 || view.getHeight() == 0) return;
        if (delivered != null && delivered.getSurface() == view.getHolder().getSurface() && delivered.getWidth() == view.getWidth() && delivered.getHeight() == view.getHeight()) return;
        safely("surface", () -> {
            destroySurface();
            delivered = new SurfaceContainer(view.getHolder().getSurface(), view.getWidth(), view.getHeight(), presentation.getContext().getResources().getDisplayMetrics().densityDpi);
            final ISurfaceCallback receiver = callback;
            receiver.onSurfaceAvailable(Bundleable.create(delivered), done("surface.available", value -> {
                if (receiver != callback || delivered == null) return;
                int w = view.getWidth(), h = view.getHeight();
                Rect area = new Rect(Math.round(w*.35f), Math.round(h*.16f), Math.round(w*.64f), Math.round(h*.75f));
                receiver.onVisibleAreaChanged(area, done("area.visible", null));
                receiver.onStableAreaChanged(area, done("area.stable", null));
                Diagnostics.event("projection", "state", "created", "display_id", presentation.getDisplay().getDisplayId(), "width", w, "height", h);
                Log.i(TAG, "surface " + w + "x" + h + " safe " + area);
            }));
        });
    }
    private void destroySurface() {
        SurfaceContainer old = delivered;
        if (old != null && presentation != null) Diagnostics.event("projection", "state", "closed", "display_id", presentation.getDisplay().getDisplayId(), "width", old.getWidth(), "height", old.getHeight());
        delivered = null;
        if (callback != null && old != null) safely("surface.destroy", () -> callback.onSurfaceDestroyed(Bundleable.create(old), done("surface.destroyed", null)));
    }
    private void close() {
        guidance.end();
        destroySurface();
        if (car != null && ready) {
            safely("pause", () -> car.onAppPause(done("pause", null)));
            safely("stop", () -> car.onAppStop(done("stop", null)));
        }
        ++generation;
        ready = false; templatePending = false; templateDirty = false; callback = null; manager = null; car = null;
        navigationEnded = false;
        lastTripAt = -GuidanceSession.STALE_MS;
        lastStateAt = -GuidanceSession.STALE_MS;
        main.removeCallbacks(refresh);
        main.removeCallbacks(retry);
        if (connection != null) { safely("unbind", () -> app.unbindService(connection)); connection = null; }
        dismissMap();
    }
    private void dismissMap() {
        destroySurface();
        if (presentation != null) { Presentation old = presentation; presentation = null; safely("dismiss", () -> old.dismiss()); }
        view = null;
    }
    private void resetAndRetry() { close(); main.removeCallbacks(retry); main.postDelayed(retry, 10000); }
    public void onDisplayAdded(int id) { reconcile(); }
    public void onDisplayRemoved(int id) { reconcile(); }
    public void onDisplayChanged(int id) { reconcile(); }
    private final class CarHost extends ICarHost.Stub {
        private final int expected = generation;
        private final AppHost apps = new AppHost();
        private final NavigationHost nav = new NavigationHost();
        public int getInterfaceVersion() { return 1; }
        public IBinder getHost(String type) { Log.i(TAG, "getHost " + type); return "app".equals(type) ? apps : "navigation".equals(type) ? nav : null; }
        public void startCarApp(Intent intent) { Log.i(TAG, "startCarApp requested"); }
        public void finish() { main.post(() -> { if (expected == generation) close(); }); }
    }
    private final class AppHost extends IAppHost.Stub {
        private final int expected = generation;
        public int getInterfaceVersion() { return 1; }
        public void invalidate() { main.post(() -> {
            if (expected != generation) return;
            templateDirty = true;
            main.removeCallbacks(refresh); main.postDelayed(refresh, 100);
        }); }
        public void setSurfaceCallback(ISurfaceCallback value) { main.post(() -> {
            if (expected != generation) return;
            destroySurface(); callback = value; Log.i(TAG, "surface callback " + value); sendSurface();
        }); }
        public void showToast(CharSequence text, int duration) { Log.i(TAG, "toast " + text); }
        public void sendLocation(Location value) { }
        public void showAlert(Bundleable value) { Log.i(TAG, "alert"); }
        public void dismissAlert(int id) { }
        public Bundleable openMicrophone(Bundleable value) { return null; }
    }
    private final class NavigationHost extends INavigationHost.Stub {
        private final int expected = generation;
        public int getInterfaceVersion() { return 1; }
        public void navigationStarted() { main.post(() -> {
            if (expected != generation) return;
            navigationEnded = false;
            Log.i(TAG, "navigationStarted");
        }); }
        public void navigationEnded() { main.post(() -> {
            if (expected != generation) return;
            navigationEnded = true;
            guidance.end();
            Log.i(TAG, "navigationEnded");
        }); }
        public void updateTrip(Bundleable trip) { main.post(() -> {
            if (expected != generation || navigationEnded
                    || SystemClock.elapsedRealtime() - lastStateAt < GuidanceSession.STALE_MS) return;
            try {
                Object value = trip == null ? null : trip.get();
                if (!(value instanceof Trip)) throw new IllegalArgumentException("Expected AndroidX Trip");
                lastTripAt = SystemClock.elapsedRealtime();
                guidance.update(WazeGuidance.fromTrip((Trip) value, System.currentTimeMillis()));
                Log.i(TAG, "updateTrip processed");
            } catch (Exception e) { guidance.end(); Log.e(TAG, "invalid Trip", e); }
        }); }
        public void setVoiceAssistantCapabilities(Bundleable value) { }
    }
}
