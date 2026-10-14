package local.waze.cluster;

import android.content.Context;
import android.content.SharedPreferences;

/** Owner choices for the cluster map layout; private app preferences, never uploaded. */
public final class ClusterSettings {
    static final String PREFS = "waze_for_byd_cluster";
    public static final String PROFILE = "profile", MODE = "mode", CONTRAST = "contrast";
    private ClusterSettings() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public static DiLink51Layout.Profile profile(Context c) {
        return read(c, PROFILE, DiLink51Layout.Profile.values(), DiLink51Layout.Profile.AUTOMATIC);
    }
    public static DiLink51Layout.Mode mode(Context c) {
        return read(c, MODE, DiLink51Layout.Mode.values(), DiLink51Layout.Mode.SCENARIO);
    }
    public static DiLink51Layout.Contrast contrast(Context c) {
        return read(c, CONTRAST, DiLink51Layout.Contrast.values(), DiLink51Layout.Contrast.DEFAULT);
    }
    public static void save(Context c, String key, Enum<?> value) { prefs(c).edit().putString(key, value.name()).apply(); }
    private static <T extends Enum<T>> T read(Context c, String key, T[] values, T fallback) {
        String stored = prefs(c).getString(key, null);
        for (T value : values) if (value.name().equals(stored)) return value;
        return fallback;
    }
}
