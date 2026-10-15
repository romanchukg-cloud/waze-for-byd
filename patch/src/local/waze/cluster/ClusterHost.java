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
import android.widget.FrameLayout;
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
    // DiLink 5.1 layout: owner settings, optional Usage Access monitor and the shown plan.
    private final SharedPreferences.OnSharedPreferenceChangeListener clusterChanged = (prefs, key) -> settingsChanged(key);
    private ClusterActivityMonitor monitor;
    private ClusterActivityState.Snapshot cluster;
    private boolean usageAccess;
    private DiLink51Layout.Plan plan;
    private String layout;
    private final java.util.List<Presentation> cleared = new java.util.ArrayList<>();
    private final java.util.Set<Integer> flushed = new java.util.HashSet<>();
    // Cluster map colours: the night bits last given to Waze's car session.
    private int appliedNight = -1;
    private String themeState = "";
    private Location themeLocation;
    private long themeLocationAt;
    private boolean carThemeError;
    private String carThemeSeen = "";
    private final Runnable themePoll = new Runnable() {
        // BYD can change the centre-screen theme without a configuration callback, so poll while connected.
        public void run() { useCarTheme(); applyTheme("poll"); reportCarTheme(); main.postDelayed(this, 2000); }
    };
    private final Runnable refresh = () -> requestTemplate();
    private final Runnable retry = () -> reconcile();
    private interface Result { void accept(Bundleable value) throws Exception; }
    private interface Work { void run() throws Exception; }

    public static synchronized void install(Application app) {
        try {
            if (instance != null || !app.getPackageName().equals(Application.getProcessName())) return;
            Diagnostics.install(app);
            try { ClusterPanel.install(app); } catch (Throwable e) { Log.e(TAG, "cluster settings panel", e); }
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
        ClusterSettings.prefs(app).registerOnSharedPreferenceChangeListener(clusterChanged);
        updateMonitor();
        Log.i(TAG, "installed test.6; stock BYD guidance bridge enabled; DiLink 5.1 layout " + (layout51() ? "active" : "inactive"));
    }
    private void settingsChanged(String key) {
        if (ClusterSettings.POINTER_VERTICAL.equals(key)) { refreshArea(); return; }
        if (ClusterSettings.CLUSTER_THEME.equals(key)) { applyTheme("setting"); return; }
        // The guidance bridge reads its own settings; the HUD opt-in applies after a restart.
        if (ClusterSettings.VEHICLE_GUIDANCE.equals(key) || ClusterSettings.HUD_UNTESTED.equals(key)) return;
        updateMonitor();
        reconcile();
    }
    private int clusterNight() {
        int theme = ClusterSettings.state(app).clusterTheme;
        String reason;
        int night;
        java.util.Calendar now = java.util.Calendar.getInstance();
        int minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        if (theme == ClusterSettings.THEME_LIGHT) { reason = "forced light"; night = Configuration.UI_MODE_NIGHT_NO; }
        else if (theme == ClusterSettings.THEME_DARK) { reason = "forced dark"; night = Configuration.UI_MODE_NIGHT_YES; }
        else if (theme == ClusterSettings.THEME_AUTO) {
            Location where = themeLocation();
            boolean dark = ClusterTheme.night(ClusterTheme.MODE_AUTO, System.currentTimeMillis(), minute, 0, 0,
                    where == null ? null : where.getLatitude(), where == null ? null : where.getLongitude());
            reason = where == null ? "no position yet: night 18:00-06:00" : dark ? "after sunset" : "after sunrise";
            night = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        } else {
            try {
                UiModeManager manager = (UiModeManager) app.getSystemService(Context.UI_MODE_SERVICE);
                int mode = manager.getNightMode();
                if (mode < ClusterTheme.MODE_AUTO || mode > ClusterTheme.MODE_CUSTOM) return keepTheme("head unit theme unknown");
                int start = 0, end = 0;
                if (mode == ClusterTheme.MODE_CUSTOM && Build.VERSION.SDK_INT >= 31) {
                    start = manager.getCustomNightModeStart().getHour() * 60 + manager.getCustomNightModeStart().getMinute();
                    end = manager.getCustomNightModeEnd().getHour() * 60 + manager.getCustomNightModeEnd().getMinute();
                }
                Location where = mode == ClusterTheme.MODE_AUTO ? themeLocation() : null;
                boolean dark = ClusterTheme.night(mode, System.currentTimeMillis(), minute, start, end,
                        where == null ? null : where.getLatitude(), where == null ? null : where.getLongitude());
                reason = "head unit " + (mode == ClusterTheme.MODE_NO ? "light" : mode == ClusterTheme.MODE_YES ? "dark"
                        : mode == ClusterTheme.MODE_CUSTOM ? "scheduled" : "automatic");
                night = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
            } catch (Exception e) { return keepTheme("head unit theme unavailable"); }
        }
        String state = "theme=" + theme + " night=" + (night == Configuration.UI_MODE_NIGHT_YES) + " " + reason;
        if (!state.equals(themeState)) { themeState = state; Log.i(TAG, "cluster colours " + state); }
        ClusterStatus.theme = "Cluster map now: " + (night == Configuration.UI_MODE_NIGHT_NO ? "light" : "dark") + " (" + reason + ").";
        return night;
    }
    private int keepTheme(String reason) {
        int night = appliedNight > 0 ? appliedNight : Configuration.UI_MODE_NIGHT_YES;
        ClusterStatus.theme = "Cluster map now: " + (night == Configuration.UI_MODE_NIGHT_NO ? "light" : "dark") + " (" + reason + ").";
        return night;
    }
    /** Last known position for sunrise/sunset only; kept in memory, never logged or uploaded. */
    private Location themeLocation() {
        long now = SystemClock.elapsedRealtime();
        if (themeLocation != null && now - themeLocationAt < 600000) return themeLocation;
        try {
            android.location.LocationManager manager = (android.location.LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String provider : manager.getProviders(true)) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            }
            if (best != null) { themeLocation = best; themeLocationAt = now; }
        } catch (RuntimeException e) { /* Without a position the fixed 18:00-06:00 schedule applies. */ }
        return themeLocation;
    }
    private Configuration sessionConfig(int night) {
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return config;
    }
    /**
     * While this Waze flag is on, its car map follows the mobile colour setting and ignores the car's theme,
     * so the cluster colours setting had no effect. Keep it off (names pinned to Waze 5.22.90.123).
     */
    private void useCarTheme() {
        try {
            Object flag = Class.forName("com.waze.config.ConfigValues").getField("CONFIG_VALUE_ANDROID_AUTO_USE_MOBILE_THEME_MODE_ON_CAR").get(null);
            if (Boolean.TRUE.equals(flag.getClass().getMethod("a").invoke(flag))) {
                flag.getClass().getMethod("c", Boolean.class).invoke(flag, Boolean.FALSE);
                Log.i(TAG, "Waze car map set to follow the cluster colours");
            }
        } catch (Throwable e) {
            if (!carThemeError) { carThemeError = true; Log.w(TAG, "Waze car theme flag unavailable", e); }
        }
    }
    /** Reads the colours Waze actually uses for its car map, for the local status only. */
    private void reportCarTheme() {
        String seen;
        try {
            java.lang.reflect.Field field = Class.forName("com.waze.nightmode.u").getDeclaredField("g");
            field.setAccessible(true);
            Object value = Class.forName("kotlinx.coroutines.b.bi").getMethod("d").invoke(field.get(null));
            seen = value == null ? "not set yet" : Boolean.TRUE.equals(value) ? "dark" : "light";
        } catch (Throwable e) { seen = "unknown"; }
        ClusterStatus.wazeCarTheme = "Waze car map colours: " + seen + ".";
        if (!seen.equals(carThemeSeen)) { carThemeSeen = seen; Log.i(TAG, "Waze car map colours " + seen); }
    }
    private void applyTheme(String source) {
        if (!ready || car == null) { clusterNight(); return; }
        int night = clusterNight();
        if (night == appliedNight) return;
        appliedNight = night;
        final Configuration config = sessionConfig(night);
        Log.i(TAG, "cluster session theme " + (night == Configuration.UI_MODE_NIGHT_NO ? "light" : "dark") + " (" + source + ")");
        safely("theme", () -> car.onConfigurationChanged(config, done("theme", null)));
    }
    private Rect area(int width, int height, DiLink51Layout.Plan areaPlan) {
        int[] a = ClusterTheme.visibleArea(width, height, areaPlan, ClusterSettings.state(app).pointerVertical);
        return new Rect(a[0], a[1], a[2], a[3]);
    }
    private void refreshArea() {
        final ISurfaceCallback receiver = callback;
        if (receiver == null || delivered == null || view == null) return;
        final Rect next = area(view.getWidth(), view.getHeight(), plan);
        safely("area.refresh", () -> {
            receiver.onVisibleAreaChanged(next, done("area.visible", null));
            receiver.onStableAreaChanged(next, done("area.stable", null));
        });
        ClusterStatus.surface = view.getWidth() + "x" + view.getHeight() + ", visible area " + next.toShortString();
        Log.i(TAG, "visible area " + next + " (pointer offset)");
    }
    private boolean layout51() { return DiLink51Layout.active(ClusterSettings.profile(app), Build.FINGERPRINT); }
    private void updateMonitor() {
        boolean follow = layout51() && ClusterSettings.mode(app) == DiLink51Layout.Mode.FOLLOW;
        if (follow && monitor == null) {
            ClusterStatus.cluster = "no signal yet";
            monitor = new ClusterActivityMonitor(app, main, (snapshot, access) -> {
                cluster = snapshot; usageAccess = access; ClusterStatus.cluster = snapshot.toString(); reconcile();
            });
        } else if (!follow && monitor != null) {
            monitor.stop(); monitor = null; cluster = null; usageAccess = false; ClusterStatus.cluster = null;
        }
    }
    private void logDisplays() {
        StringBuilder s = new StringBuilder();
        java.util.Set<Integer> presentations = new java.util.HashSet<>();
        for (Display d : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) presentations.add(d.getDisplayId());
        for (Display d : displays.getDisplays()) {
            Point size = new Point();
            d.getRealSize(size);
            if (s.length() > 0) s.append("; ");
            s.append(d.getDisplayId()).append(' ').append(d.getName()).append(' ').append(size.x).append('x').append(size.y)
                    .append(presentations.contains(d.getDisplayId()) ? " presentation" : "");
        }
        if (!s.toString().equals(ClusterStatus.displays)) { ClusterStatus.displays = s.toString(); Log.i(TAG, "displays: " + s); }
    }
    private void safely(String stage, Work work) {
        try { work.run(); } catch (Throwable e) { Diagnostics.failure(stage.startsWith("surface") || stage.startsWith("area") ? "projection" : "adapter", "internal_error"); Log.e(TAG, stage, e); }
    }
    /** Failures here affect only the map surface or colours, never the guidance session. */
    private static boolean optional(String stage) { return stage.startsWith("surface") || stage.startsWith("area") || stage.startsWith("theme"); }
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
                            if (!optional(stage)) resetAndRetry();
                        }
                    }
                }); });
            }
            public void onFailure(Bundleable value) {
                main.post(() -> { if (expected == generation) {
                    Diagnostics.failure(stage.startsWith("surface") || stage.startsWith("area") ? "projection" : "adapter", "internal_error");
                    safely(stage, () -> Log.e(TAG, stage + " failed: " + (value == null ? "null" : value.get())));
                    if ("template".equals(stage)) templatePending = false;
                    else if (!optional(stage)) resetAndRetry();
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
        logDisplays();
        if (!ClusterSettings.state(app).mapEnabled) {
            if (presentation != null) clearMap();
            if (layout51()) flush51(displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION), null);
            ClusterStatus.map = "off in Instrument cluster settings";
            return;
        }
        if (layout51()) { reconcile51(); return; }
        Display target = null;
        for (Display d : displays.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.getName().contains("XDJAScreenProjection")) {
                target = d; break;
            }
        }
        if (presentation != null && (plan != null || target == null || presentation.getDisplay().getDisplayId() != target.getDisplayId())) dismissMap();
        if (target == null) { ClusterStatus.map = "waiting for an XDJAScreenProjection display"; Log.i(TAG, "waiting for XDJAScreenProjection display"); main.postDelayed(retry, 10000); return; }
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
                    view = surfaceView(getContext());
                    setContentView(view);
                }
            };
            presentation.show();
            releaseCleared(selected.getDisplayId());
            ClusterStatus.map = "display " + selected.getDisplayId() + " " + selected.getName() + ", DiLink 5 layout, full display";
            Log.i(TAG, "display " + selected.getDisplayId() + " " + selected.getName());
            sendSurface();
        } catch (Throwable e) {
            Diagnostics.failure("projection", e instanceof SecurityException ? "permission_denied" : "internal_error");
            Log.e(TAG, "show map; guidance session remains connected", e);
            dismissMap();
            main.postDelayed(retry, 10000);
        }
    }
    private SurfaceView surfaceView(Context context) {
        final SurfaceView surface = new SurfaceView(context);
        // A presentation being cleared must not tear down the surface of its replacement.
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { if (view == surface) sendSurface(); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { if (view == surface) sendSurface(); }
            public void surfaceDestroyed(SurfaceHolder h) { if (view == surface) destroySurface(); }
        });
        return surface;
    }
    private static void transparent(Window window) {
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    /** Once per layer: replace a frame left by a previous crash or force-stop with a transparent window. */
    private void flush51(Display[] list, Display target) {
        for (final Display d : list) {
            if (!DiLink51Layout.FULL.equals(d.getName()) && !DiLink51Layout.SIDE.equals(d.getName())) continue;
            if (!flushed.add(d.getDisplayId()) || target != null && target.getDisplayId() == d.getDisplayId()) continue;
            safely("flush", () -> {
                Presentation blank = new Presentation(app, d) {
                    protected void onCreate(Bundle state) {
                        super.onCreate(state);
                        transparent(getWindow());
                        setContentView(new View(getContext()));
                    }
                };
                blank.show();
                cleared.add(blank);
            });
        }
    }
    private void showing(String value) {
        if (!value.equals(ClusterStatus.map)) { ClusterStatus.map = value; Log.i(TAG, "DiLink 5.1 layout: " + value); }
    }
    private void reconcile51() {
        DiLink51Layout.Mode mode = ClusterSettings.mode(app);
        DiLink51Layout.Theme theme = DiLink51Layout.target(mode, cluster, usageAccess);
        Display[] list = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        String[] names = new String[list.length];
        int[] widths = new int[list.length], heights = new int[list.length];
        for (int i = 0; i < list.length; i++) {
            Point size = new Point();
            list[i].getRealSize(size);
            names[i] = list[i].getName(); widths[i] = size.x; heights[i] = size.y;
        }
        int index = DiLink51Layout.choose(names, widths, heights, theme);
        Display target = index < 0 ? null : list[index];
        DiLink51Layout.Plan next = target == null ? null : DiLink51Layout.plan(widths[index], heights[index], theme);
        boolean dark = theme != null && DiLink51Layout.dark(ClusterSettings.contrast(app), theme);
        // Scenario and Simple share one layer and geometry, so switching between them keeps the surface.
        String key = next == null ? null : next.fullMap ? (dark ? "map/dark" : "map/light") : "side";
        if (presentation != null && (target == null || presentation.getDisplay().getDisplayId() != target.getDisplayId() || !key.equals(layout))) dismissMap();
        flush51(list, target);
        if (target == null) {
            showing(theme != null ? "waiting for " + DiLink51Layout.displayName(theme) + " at 1920x720"
                    : mode != DiLink51Layout.Mode.FOLLOW ? "hidden"
                    : usageAccess ? "hidden, no stock map is visible on the cluster" : "hidden, Usage Access is not granted");
            if (theme != null) main.postDelayed(retry, 10000);
            return;
        }
        if (presentation != null) return;
        try {
            final DiLink51Layout.Plan selectedPlan = next;
            final int backdrop = dark ? Color.rgb(15, 22, 30) : Color.rgb(207, 218, 229);
            plan = next;
            layout = key;
            presentation = new Presentation(app, target) {
                protected void onCreate(Bundle state) {
                    super.onCreate(state);
                    transparent(getWindow());
                    FrameLayout root = new FrameLayout(getContext());
                    // The map theme backs the stock readouts; outside the side card nothing is drawn.
                    root.setBackgroundColor(selectedPlan.fullMap ? backdrop : Color.TRANSPARENT);
                    view = surfaceView(getContext());
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(selectedPlan.width, selectedPlan.height, Gravity.TOP | Gravity.LEFT);
                    params.leftMargin = selectedPlan.left;
                    params.topMargin = selectedPlan.top;
                    root.addView(view, params);
                    if (selectedPlan.fullMap) root.addView(new ContrastBands(getContext(), selectedPlan, backdrop), new FrameLayout.LayoutParams(-1, -1));
                    setContentView(root);
                }
            };
            presentation.show();
            releaseCleared(target.getDisplayId());
            showing("display " + target.getDisplayId() + " " + target.getName() + ", " + theme.name().toLowerCase(java.util.Locale.ROOT)
                    + (mode == DiLink51Layout.Mode.FOLLOW ? " (following the cluster)" : " (fixed)") + ", " + next
                    + (next.fullMap ? ", " + (dark ? "dark" : "light") + " background" : ""));
            sendSurface();
        } catch (Throwable e) {
            Diagnostics.failure("projection", e instanceof SecurityException ? "permission_denied" : "internal_error");
            Log.e(TAG, "show DiLink 5.1 map; guidance session remains connected", e);
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
        useCarTheme();
        appliedNight = clusterNight();
        Configuration config = sessionConfig(appliedNight);
        Log.i(TAG, "cluster session theme " + (appliedNight == Configuration.UI_MODE_NIGHT_NO ? "light" : "dark") + " (create)");
        car.onAppCreate(new CarHost(), sessionIntent, config, done("create", value ->
            car.onAppStart(done("start", started -> car.onAppResume(done("resume", resumed -> {
                ready = true;
                main.removeCallbacks(themePoll);
                main.postDelayed(themePoll, 2000);
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
            final DiLink51Layout.Plan areaPlan = plan;
            receiver.onSurfaceAvailable(Bundleable.create(delivered), done("surface.available", value -> {
                if (receiver != callback || delivered == null) return;
                int w = view.getWidth(), h = view.getHeight();
                Rect area = area(w, h, areaPlan);
                receiver.onVisibleAreaChanged(area, done("area.visible", null));
                receiver.onStableAreaChanged(area, done("area.stable", null));
                Diagnostics.event("projection", "state", "created", "display_id", presentation.getDisplay().getDisplayId(), "width", w, "height", h);
                ClusterStatus.surface = w + "x" + h + ", visible area " + area.toShortString();
                Log.i(TAG, "surface " + w + "x" + h + " safe " + area);
            }));
        });
    }
    private void destroySurface() {
        SurfaceContainer old = delivered;
        if (old != null && presentation != null) Diagnostics.event("projection", "state", "closed", "display_id", presentation.getDisplay().getDisplayId(), "width", old.getWidth(), "height", old.getHeight());
        delivered = null;
        ClusterStatus.surface = "none";
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
        main.removeCallbacks(themePoll);
        appliedNight = -1;
        if (connection != null) { safely("unbind", () -> app.unbindService(connection)); connection = null; }
        dismissMap();
    }
    private void dismissMap() {
        // A DiLink 5.1 layer is never emptied; the DiLink 5 layout keeps its test.5 behaviour.
        if (plan != null) { clearMap(); return; }
        destroySurface();
        if (presentation != null) { Presentation old = presentation; presentation = null; safely("dismiss", () -> old.dismiss()); }
        view = null;
        plan = null;
        layout = null;
    }
    /**
     * An emptied virtual display keeps showing a stale buffer. Keep the window as a transparent layer
     * (black on the DiLink 5 layout) until a replacement is shown on the same display.
     */
    private void clearMap() {
        destroySurface();
        if (presentation != null) {
            final Presentation old = presentation;
            final SurfaceView oldView = view;
            presentation = null;
            safely("clear", () -> {
                // SurfaceView keeps its own layer visible unless its own visibility changes.
                if (oldView != null) oldView.setVisibility(View.GONE);
                old.getWindow().findViewById(android.R.id.content).setVisibility(View.INVISIBLE);
            });
            cleared.add(old);
        }
        view = null;
        plan = null;
        layout = null;
    }
    private void releaseCleared(int displayId) {
        for (java.util.Iterator<Presentation> it = cleared.iterator(); it.hasNext();) {
            final Presentation old = it.next();
            if (old.getDisplay().getDisplayId() != displayId) continue;
            it.remove();
            main.postDelayed(() -> safely("dismiss", () -> old.dismiss()), 1000); // After the replacement draws.
        }
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
    /** DiPlay's measured fades behind the map theme's instrument readouts. */
    private static final class ContrastBands extends View {
        private final DiLink51Layout.Plan plan;
        private final int background;
        private final Paint paint = new Paint();
        ContrastBands(Context context, DiLink51Layout.Plan plan, int background) {
            super(context);
            this.plan = plan;
            this.background = background;
        }
        private void fade(Canvas canvas, float x0, float y0, float x1, float y1, int start, int end, float l, float t, float r, float b) {
            paint.setShader(new LinearGradient(x0, y0, x1, y1, start, end, Shader.TileMode.CLAMP));
            canvas.drawRect(l, t, r, b, paint);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = plan.left, top = plan.top, right = left + plan.width, bottom = top + plan.height;
            int clear = background & 0x00ffffff, soft = clear | (210 << 24);
            int topFade = DiLink51Layout.Plan.TOP_FADE, bottomFade = DiLink51Layout.Plan.BOTTOM_FADE, side = DiLink51Layout.Plan.SIDE_PANEL;
            fade(canvas, left, top, left, top + topFade, background, clear, left, top, right, top + topFade);
            fade(canvas, left, bottom - bottomFade, left, bottom, clear, background, left, bottom - bottomFade, right, bottom);
            // Trip and menu readouts occupy the outer panels; the route and marker stay in the clear centre.
            fade(canvas, left, top, left + side, top, soft, clear, left, top, left + side, bottom);
            fade(canvas, right - side, top, right, top, clear, soft, right - side, top, right, bottom);
            paint.setShader(null);
        }
    }
}
