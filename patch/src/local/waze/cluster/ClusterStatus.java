package local.waze.cluster;

import android.content.Context;
import android.os.Build;

/** Local Support status for the cluster map and HUD; never part of diagnostics reports. */
public final class ClusterStatus {
    static volatile String map = "cluster host is not running", surface = "none", displays = "", cluster;
    private ClusterStatus() { }

    public static String describe(Context context) {
        boolean verified = DiLink51Layout.verified(Build.FINGERPRINT);
        boolean active = DiLink51Layout.active(ClusterSettings.profile(context), Build.FINGERPRINT);
        StringBuilder s = new StringBuilder("Firmware: ").append(Build.FINGERPRINT)
                .append("\nCluster layout: ").append(active ? (verified ? "DiLink 5.1, verified firmware" : "DiLink 5.1, chosen for unverified firmware")
                        : "DiLink 5" + (verified ? ", chosen manually" : ""));
        if (active) s.append("\nUsage Access: ").append(ClusterActivityMonitor.hasAccess(context) ? "granted" : "not granted");
        String stock = cluster;
        if (stock != null) s.append("\nStock cluster: ").append(stock);
        s.append("\nMap: ").append(map).append("\nWaze surface: ").append(surface)
                .append("\nDisplays: ").append(displays.isEmpty() ? "unknown" : displays);
        return s.append("\n").append(StandaloneHudGate.describe(context)).toString();
    }
}
