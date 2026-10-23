// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import local.waze.diagnostics.Diagnostics;

/** Select one verified protocol target: gated 5.1 HUD, otherwise existing 5.0 AMap. No display ACK. */
public final class BydGuidanceBridge {
    private static final String TAG = "WazeBydGuidance";
    private final Handler main;
    private final Context context;
    private GuidanceFrame lastSent;
    private final boolean standalone;
    private final GuidanceSession session;
    private final Runnable tick = new Runnable() {
        public void run() {
            // Turning guidance off in the cluster settings ends an owned card within one tick.
            safely(() -> { if (enabled()) session.tick(SystemClock.elapsedRealtime()); else session.end(); });
            main.postDelayed(this, standalone ? 250 : 1000);
        }
    };
    public BydGuidanceBridge(Context context, Handler main) {
        this.main = main;
        this.context = context;
        String rejection = StandaloneHudGate.rejection(context);
        boolean untested = StandaloneHudGate.UNTESTED.equals(rejection);
        // Another 5.1 build may already drive its HUD through SOME/IP (for example OpenBYD): opt-in only, read at start.
        standalone = rejection == null || untested && ClusterSettings.state(context).hudUntested;
        ClusterStatus.untestedHudAvailable = untested;
        ClusterStatus.standaloneActive = standalone;
        ClusterStatus.hud = rejection == null ? "HUD transport: standalone receiver verified."
                : standalone ? "HUD transport: standalone receiver (experimental: this firmware build is untested)."
                : untested ? "HUD transport: AMap. A standalone HUD receiver passed all checks, but this firmware build is untested. You can try it below."
                : "HUD transport: AMap. Standalone unavailable (" + rejection + ").";
        // The AMap adapter is only used when the standalone HUD is not; DiLink 3 names it com.example.amapservice.
        final String adapter = standalone ? AmapAdapter.BYD : AmapAdapter.packageName(context);
        if (!standalone) ClusterStatus.hud = ClusterStatus.hud.replace("HUD transport: AMap.", "HUD transport: AMap via " + adapter + ".");
        SharedPreferences prefs = context.getSharedPreferences("waze_for_byd_guidance", Context.MODE_PRIVATE);
        if (!standalone && untested && prefs.getBoolean("standalone_hud_pending_clear", false)) {
            // The owner turned the experimental output off: clear what a killed process left on the HUD.
            safely(() -> {
                context.sendBroadcast(StandaloneHudGate.intent(StandaloneHudPackets.END));
                if (!prefs.edit().putBoolean("standalone_hud_pending_clear", false).commit()) throw new IllegalStateException("Cannot clear HUD journal");
                Log.i(TAG, "standalone HUD recovery clear sent");
            });
        }
        final StandaloneHudOutput hud = new StandaloneHudOutput(packet -> context.sendBroadcast(StandaloneHudGate.intent(packet)));
        final String marker = standalone ? "standalone_hud_pending_clear" : "guidance_shown";
        session = new GuidanceSession(new GuidanceSession.Output() {
            public void guidance(GuidanceFrame frame) {
                String reason = frame.equals(lastSent) ? "keepalive" : "changed";
                try {
                    if(standalone) hud.guidance(frame);
                    else context.sendBroadcast(guidanceIntent(frame).setPackage(adapter));
                }
                catch (RuntimeException e) { record(frame, "failed", reason); throw e; }
                record(frame, "sent", reason);
                lastSent = frame;
                Log.i(TAG, "guidance broadcast sent; icon=" + frame.icon + " type=" + frame.maneuverType + " turnMeters=" + frame.turnMeters);
            }
            public void end() {
                try { if(standalone) hud.end(); else context.sendBroadcast(endIntent().setPackage(adapter)); }
                catch (RuntimeException e) { record(null, "failed", "end"); throw e; }
                record(null, "sent", "end");
                lastSent = null;
                Log.i(TAG, "end broadcast sent");
            }
        }, new GuidanceSession.Store() {
            public boolean wasShown() { return prefs.getBoolean(marker, false); }
            public boolean setShown(boolean shown) { return prefs.edit().putBoolean(marker, shown).commit(); }
        }, standalone ? 1000 : GuidanceSession.KEEPALIVE_MS);
        Log.i(TAG, (standalone ? "Using gated DiLink 5.1 standalone HUD transport" : "Using DiLink 5 AMap transport") + " (" + (rejection == null ? "verified" : rejection) + ")");
        safely(() -> session.end()); // Recover a card left behind by process death.
        main.postDelayed(tick, standalone ? 250 : 1000);
    }
    private boolean enabled() { return ClusterSettings.state(context).vehicleGuidance; }
    public void update(GuidanceFrame frame) {
        if (!enabled()) { safely(() -> session.end()); return; }
        GuidanceFrame accepted = standalone && frame != null && StandaloneHudPackets.guidance(frame) == null ? null : frame;
        safely(() -> session.update(accepted, SystemClock.elapsedRealtime()));
    }
    public void end() { safely(() -> session.end()); }
    private void safely(Runnable work) {
        try { work.run(); } catch (RuntimeException e) { Diagnostics.failure("adapter", e instanceof SecurityException ? "permission_denied" : "internal_error"); Log.e(TAG, "broadcast/persistence failed; will retry", e); }
    }
    private static void record(GuidanceFrame frame, String outcome, String reason) {
        // Only approved numeric fields. NEXT_ROAD_NAME never enters diagnostics.
        Diagnostics.event("guidance_send", "outcome", outcome, "icon", frame == null ? -1 : frame.icon,
            "turn_meters", frame == null ? -1 : frame.turnMeters, "route_meters", frame == null ? -1 : frame.routeMeters,
            "route_seconds", frame == null ? -1 : frame.routeSeconds, "reason", reason);
    }
    private static Intent base(int type) {
        return new Intent("AUTONAVI_STANDARD_BROADCAST_SEND")
                .setPackage("com.byd.amapservice").addFlags(0x01000000)
                .putExtra("KEY_TYPE", type).putExtra("IS_BYD_MAP", true).putExtra("IS_BYD_BAIDU_MAP", false);
    }
    public static Intent guidanceIntent(GuidanceFrame frame) {
        return base(10001).putExtra("TYPE", 0).putExtra("EXTRA_STATE", 0).putExtra("EXTRA_IS_FOREGROUND", 0)
                .putExtra("NEW_ICON", frame.icon).putExtra("ROUNG_ABOUT_NUM", frame.roundaboutExit)
                .putExtra("SEG_REMAIN_DIS", frame.turnMeters).putExtra("NEXT_ROAD_NAME", frame.nextRoad)
                .putExtra("ROUTE_REMAIN_DIS", frame.routeMeters).putExtra("ROUTE_REMAIN_TIME", frame.routeSeconds);
    }
    public static Intent endIntent() {
        return base(10019).putExtra("EXTRA_STATE", 9).putExtra("EXTRA_IS_FOREGROUND", 1)
                .putExtra("NEW_ICON", -1).putExtra("SEG_REMAIN_DIS", -1).putExtra("NEXT_ROAD_NAME", "")
                .putExtra("ROUTE_REMAIN_DIS", -1).putExtra("ROUTE_REMAIN_TIME", -1);
    }
}
