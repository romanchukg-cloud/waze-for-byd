package local.waze.cluster;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

/** Stock DiLink 5 AMap broadcast transport. A successful send is not an ACK. */
public final class BydGuidanceBridge {
    private static final String TAG = "WazeBydGuidance";
    private final Handler main;
    private final GuidanceSession session;
    private final Runnable tick = new Runnable() {
        public void run() {
            safely(() -> session.tick(SystemClock.elapsedRealtime()));
            main.postDelayed(this, 1000);
        }
    };
    public BydGuidanceBridge(Context context, Handler main) {
        this.main = main;
        SharedPreferences prefs = context.getSharedPreferences("waze_for_byd_guidance", Context.MODE_PRIVATE);
        session = new GuidanceSession(new GuidanceSession.Output() {
            public void guidance(GuidanceFrame frame) {
                context.sendBroadcast(guidanceIntent(frame));
                Log.i(TAG, "guidance broadcast sent; icon=" + frame.icon + " turnMeters=" + frame.turnMeters);
            }
            public void end() {
                context.sendBroadcast(endIntent());
                Log.i(TAG, "end broadcast sent");
            }
        }, new GuidanceSession.Store() {
            public boolean wasShown() { return prefs.getBoolean("guidance_shown", false); }
            public boolean setShown(boolean shown) { return prefs.edit().putBoolean("guidance_shown", shown).commit(); }
        });
        safely(() -> session.end()); // Recover a card left behind by process death.
        main.postDelayed(tick, 1000);
    }
    public void update(GuidanceFrame frame) {
        safely(() -> session.update(frame, SystemClock.elapsedRealtime()));
    }
    public void end() { safely(() -> session.end()); }
    private void safely(Runnable work) {
        try { work.run(); } catch (RuntimeException e) { Log.e(TAG, "broadcast/persistence failed; will retry", e); }
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
