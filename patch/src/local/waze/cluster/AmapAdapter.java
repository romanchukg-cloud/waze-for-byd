// SPDX-License-Identifier: GPL-3.0-only
// Adapted from romanchukg-cloud/maps-automotive-for-byd-patches (runtime/local/maps/cluster/AmapAdapter.java).
package local.waze.cluster;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * AMap guidance goes to the adapter package that is really installed. DiLink 5 ships it as
 * com.byd.amapservice, DiLink 3 as com.example.amapservice. AmapService registers the AUTONAVI receiver
 * at runtime, so its service is the static evidence; a manifest receiver for the action also counts.
 * Android 11+ hides both packages from Waze (no manifest query), so there the result stays com.byd.amapservice.
 */
final class AmapAdapter {
    static final String BYD = "com.byd.amapservice", EXAMPLE = "com.example.amapservice", SERVICE = "com.example.amapservice.AmapService";
    static final String ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String TAG = "WazeBydGuidance";
    private static String chosen, description = "AMap adapter: not checked yet";
    private AmapAdapter() { }

    /** null when the package can receive AMap guidance, otherwise a short reason. */
    static String rejection(Context context, String name) {
        PackageManager pm = context.getPackageManager();
        ApplicationInfo app;
        try { app = pm.getApplicationInfo(name, 0); } catch (PackageManager.NameNotFoundException e) { return "not_installed"; }
        if ((app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) return "not_system";
        if (!app.enabled) return "disabled";
        boolean service;
        try { service = pm.getServiceInfo(new ComponentName(name, SERVICE), 0).isEnabled(); }
        catch (PackageManager.NameNotFoundException e) { service = false; }
        if (service || !pm.queryBroadcastReceivers(new Intent(ACTION).setPackage(name), 0).isEmpty()) return null;
        return "no_receiver";
    }
    /** com.byd.amapservice first; com.example.amapservice only when it alone is proven. */
    static String choose(String bydRejection, String exampleRejection) {
        return bydRejection != null && exampleRejection == null ? EXAMPLE : BYD;
    }
    /** Checked once per process; only package names and reason codes are logged. */
    static synchronized String packageName(Context context) {
        if (chosen != null) return chosen;
        String byd = check(context, BYD), example = check(context, EXAMPLE), name = choose(byd, example);
        description = byd == null || name.equals(EXAMPLE) ? "AMap adapter: " + name
                : "AMap adapter: " + name + " (default; " + BYD + " " + byd + ", " + EXAMPLE + " " + example + ")";
        Log.i(TAG, description);
        return chosen = name;
    }
    static synchronized String description() { return description; }
    private static String check(Context context, String name) {
        try { return rejection(context, name); } catch (RuntimeException e) { return "check_failed"; }
    }
}
