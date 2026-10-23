// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.*;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.*;

/** Follows the stock cluster activities through the owner's Usage Access grant; no BYD API. */
final class ClusterActivityMonitor {
    interface Listener { void onCluster(ClusterActivityState.Snapshot snapshot, boolean access); }
    private static final String TAG = "WazeClusterTest";
    private static final long POLL_MS = 500, OVERLAP_MS = 2000;
    private final Context context;
    private final Handler main;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("waze-byd-cluster-monitor");
    private final Handler worker;
    private final ClusterActivityState state = new ClusterActivityState();
    private final Map<String, Long> seen = new HashMap<>();
    private Method instanceId;
    private long since = bootTime();
    private ClusterActivityState.Snapshot reported;
    private Boolean reportedAccess;
    private volatile boolean stopped;
    private final Runnable poll = this::poll;

    ClusterActivityMonitor(Context context, Handler main, Listener listener) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.listener = listener;
        // Hidden on some builds; without it an overlapping recreation conservatively hides the map.
        try { instanceId = UsageEvents.Event.class.getMethod("getInstanceId"); } catch (Exception e) { instanceId = null; }
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(poll);
    }
    void stop() {
        stopped = true;
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }
    static boolean hasAccess(Context context) {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }
    private static long bootTime() { return Math.max(0, System.currentTimeMillis() - SystemClock.elapsedRealtime()); }
    private void restart() { state.clear(); seen.clear(); since = bootTime(); }
    private void poll() {
        if (stopped) return;
        long now = System.currentTimeMillis();
        boolean access = false;
        try {
            access = hasAccess(context);
            if (!access) restart();
            else {
                if (now < since) restart(); // A clock correction must not leave an old overlay visible.
                UsageStatsManager usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
                UsageEvents events = usage == null ? null : usage.queryEvents(since, now);
                if (events == null) throw new IllegalStateException("Usage events unavailable");
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    int type = event.getEventType();
                    if (!ClusterActivityState.reset(type) && !ClusterActivityState.accepted(event.getPackageName(), event.getClassName())) continue;
                    int instance = 0;
                    try { if (instanceId != null) instance = (Integer) instanceId.invoke(event); } catch (Exception ignored) { }
                    String key = event.getPackageName() + "/" + event.getClassName() + "#" + instance + ":" + type + "@" + event.getTimeStamp();
                    if (seen.put(key, event.getTimeStamp()) == null)
                        state.event(event.getPackageName(), event.getClassName(), instance, type, event.getTimeStamp());
                }
                // Overlap catches asynchronously delivered events; keys deduplicate them.
                since = Math.max(bootTime(), now - OVERLAP_MS);
                final long cutoff = since;
                seen.values().removeIf(time -> time < cutoff);
            }
        } catch (RuntimeException e) {
            restart(); // No reliable signal means no overlay.
            Log.w(TAG, "cluster activity monitor", e);
        }
        final ClusterActivityState.Snapshot snapshot = state.snapshot();
        final boolean granted = access;
        if (!snapshot.equals(reported) || reportedAccess == null || reportedAccess != granted) {
            reported = snapshot;
            reportedAccess = granted;
            Log.i(TAG, "cluster activities: " + snapshot + ", usage access " + (granted ? "granted" : "missing"));
            main.post(() -> { if (!stopped) listener.onCluster(snapshot, granted); });
        }
        worker.postDelayed(poll, POLL_MS);
    }
}
