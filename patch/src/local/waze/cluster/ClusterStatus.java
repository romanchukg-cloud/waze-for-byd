package local.waze.cluster;

import android.content.Context;
import android.os.Build;

/** Local Support status for the cluster map and HUD; never part of diagnostics reports. */
public final class ClusterStatus {
    static volatile String map = "cluster host is not running", surface = "none", displays = "", cluster,
            theme = "Cluster map: not shown yet.", hud = "HUD transport: checking receiver.", wazeCarTheme = "";
    static volatile boolean untestedHudAvailable, standaloneActive;
    public static String theme() { return theme + (wazeCarTheme.isEmpty() ? "" : " " + wazeCarTheme); }
    public static String hud() { return hud; }
    public static boolean untestedHudAvailable() { return untestedHudAvailable; }
    public static boolean standaloneActive() { return standaloneActive; }
    private ClusterStatus() { }

    public static String describe(Context context) {
        ClusterSettings.State settings = ClusterSettings.state(context);
        boolean verified = DiLink51Layout.verified(Build.FINGERPRINT);
        boolean active = DiLink51Layout.active(settings.profile, Build.FINGERPRINT);
        StringBuilder s = new StringBuilder("Firmware: ").append(Build.FINGERPRINT)
                .append("\nCluster layout: ").append(active ? (verified ? "DiLink 5.1, verified firmware" : "DiLink 5.1, chosen for unverified firmware")
                        : "DiLink 5" + (verified ? ", chosen manually" : ""));
        if (active) s.append("\nUsage Access: ").append(ClusterActivityMonitor.hasAccess(context) ? "granted" : "not granted");
        String stock = cluster;
        if (stock != null) s.append("\nStock cluster: ").append(stock);
        s.append("\nMap on cluster: ").append(settings.mapEnabled ? "on" : "off").append(", guidance: ").append(settings.vehicleGuidance ? "on" : "off")
                .append("\n").append(theme())
                .append("\nMap: ").append(map).append("\nWaze surface: ").append(surface)
                .append("\nDisplays: ").append(displays.isEmpty() ? "unknown" : displays);
        return s.append("\n").append(hud).append("\n").append(StandaloneHudGate.describe(context)).toString();
    }
}
