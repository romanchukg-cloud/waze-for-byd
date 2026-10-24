// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import android.content.Context;
import android.content.SharedPreferences;

/** Owner choices for the instrument cluster; private app preferences, never uploaded. */
public final class ClusterSettings {
    static final String PREFS = "local_cluster_settings", LEGACY_PREFS = "waze_for_byd_cluster";
    public static final String MAP_ENABLED = "map_enabled", VEHICLE_GUIDANCE = "vehicle_guidance",
            HUD_UNTESTED = "hud_untested_firmware", POINTER_VERTICAL = "pointer_vertical", POINTER_HORIZONTAL = "pointer_horizontal", PANEL_FULLSCREEN = "panel_fullscreen", EDGE_MARGIN = "edge_margin",
            PROFILE = "dilink51_profile", MODE = "dilink51_theme", CONTRAST = "dilink51_background";
    private static boolean migrated;
    private ClusterSettings() { }

    public static final class State {
        public final boolean mapEnabled, vehicleGuidance, hudUntested, panelFullscreen;
        public final int pointerVertical, pointerHorizontal, edgeMargin;
        public final DiLink51Layout.Profile profile;
        public final DiLink51Layout.Mode mode;
        public final DiLink51Layout.Contrast contrast;
        State(SharedPreferences p) {
            mapEnabled = p.getBoolean(MAP_ENABLED, true);
            vehicleGuidance = p.getBoolean(VEHICLE_GUIDANCE, true);
            hudUntested = p.getBoolean(HUD_UNTESTED, false);
            panelFullscreen = p.getBoolean(PANEL_FULLSCREEN, false);
            // Waze places its car marker from the visible area; 0 keeps the layout verified in test.5.
            pointerVertical = Math.max(-25, Math.min(25, p.getInt(POINTER_VERTICAL, 0)));
            pointerHorizontal = Math.max(-25, Math.min(25, p.getInt(POINTER_HORIZONTAL, 0)));
            // Extra space between Waze's buttons and the screen edges, in dp.
            edgeMargin = Math.max(0, Math.min(48, p.getInt(EDGE_MARGIN, 16)));
            profile = read(p, PROFILE, DiLink51Layout.Profile.values(), DiLink51Layout.Profile.AUTOMATIC);
            mode = read(p, MODE, DiLink51Layout.Mode.values(), DiLink51Layout.Mode.SCENARIO);
            contrast = read(p, CONTRAST, DiLink51Layout.Contrast.values(), DiLink51Layout.Contrast.DEFAULT);
        }
    }

    public static synchronized SharedPreferences prefs(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!migrated) {
            migrated = true;
            // test.5 kept the DiLink 5.1 layout choices in their own file.
            SharedPreferences old = c.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
            if (!old.getAll().isEmpty()) {
                SharedPreferences.Editor e = p.edit();
                String[][] keys = {{"profile", PROFILE}, {"mode", MODE}, {"contrast", CONTRAST}};
                for (String[] k : keys) if (old.contains(k[0]) && !p.contains(k[1])) e.putString(k[1], old.getString(k[0], null));
                if (e.commit()) old.edit().clear().commit();
            }
        }
        return p;
    }
    public static State state(Context c) { return new State(prefs(c)); }
    public static DiLink51Layout.Profile profile(Context c) { return state(c).profile; }
    public static DiLink51Layout.Mode mode(Context c) { return state(c).mode; }
    public static DiLink51Layout.Contrast contrast(Context c) { return state(c).contrast; }
    private static <T extends Enum<T>> T read(SharedPreferences p, String key, T[] values, T fallback) {
        String stored = p.getString(key, null);
        for (T value : values) if (value.name().equals(stored)) return value;
        return fallback;
    }
}
