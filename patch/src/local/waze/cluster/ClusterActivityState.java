package local.waze.cluster;

import java.util.*;

/** Visibility of the stock DiLink 5.1 cluster activities, from Usage Access events. Pure logic. */
public final class ClusterActivityState {
    public static final String FULL_MAP = "com.byd.automap.meter.MeterActivity";
    public static final String MINI_MAP = "com.byd.automap.meter.MeterSmallScreenActivity";
    public static final String SCENARIO = "com.byd.sr.cluster.ClusterActivity";
    public static final String SIMPLE = "com.byd.cluster.SimpleClusterDynastyActivity";
    // UsageEvents.Event types. A paused activity is still visible, so only RESUMED/STOPPED count.
    static final int RESUMED = 1, STOPPED = 23, SHUTDOWN = 26, STARTUP = 27;
    private final Map<String, Long> visible = new HashMap<>();

    public static boolean accepted(String pkg, String name) {
        if ("com.byd.launchermap".equals(pkg) || "com.byd.automap".equals(pkg)) return FULL_MAP.equals(name) || MINI_MAP.equals(name);
        if ("com.byd.sr".equals(pkg)) return SCENARIO.equals(name);
        if ("com.byd.cluster".equals(pkg)) return SIMPLE.equals(name);
        return false;
    }
    public static boolean reset(int type) { return type == SHUTDOWN || type == STARTUP; }
    public void clear() { visible.clear(); }
    public void event(String pkg, String name, int instance, int type, long time) {
        if (reset(type)) { visible.clear(); return; }
        if (!accepted(pkg, name)) return;
        String key = name + "#" + instance;
        if (type == RESUMED) visible.put(key, time);
        else if (type == STOPPED) visible.remove(key);
    }
    public Snapshot snapshot() {
        String newest = null;
        long newestAt = Long.MIN_VALUE;
        boolean miniMap = false;
        for (Map.Entry<String, Long> e : visible.entrySet()) {
            String name = e.getKey().substring(0, e.getKey().lastIndexOf('#'));
            if (MINI_MAP.equals(name)) miniMap = true;
            if (themeOf(name) != null && e.getValue() >= newestAt) { newest = name; newestAt = e.getValue(); }
        }
        return new Snapshot(newest == null ? null : themeOf(newest), miniMap);
    }
    static DiLink51Layout.Theme themeOf(String name) {
        if (FULL_MAP.equals(name)) return DiLink51Layout.Theme.MAP;
        if (SCENARIO.equals(name)) return DiLink51Layout.Theme.SCENARIO;
        if (SIMPLE.equals(name)) return DiLink51Layout.Theme.SIMPLE;
        return null;
    }

    public static final class Snapshot {
        public final DiLink51Layout.Theme theme;
        public final boolean miniMap;
        public Snapshot(DiLink51Layout.Theme theme, boolean miniMap) { this.theme = theme; this.miniMap = miniMap; }
        @Override public boolean equals(Object o) {
            return o instanceof Snapshot && ((Snapshot) o).theme == theme && ((Snapshot) o).miniMap == miniMap;
        }
        @Override public int hashCode() { return Objects.hash(theme, miniMap); }
        @Override public String toString() {
            return "theme " + (theme == null ? "none" : theme.name().toLowerCase(Locale.ROOT)) + ", mini-map " + (miniMap ? "visible" : "hidden");
        }
    }
}
